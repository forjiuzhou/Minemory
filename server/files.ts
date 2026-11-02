// Local-disk or S3-compatible file URLs for the VPS server.
// Keys match extras.file_path: <user>/<yyyy>/<mm>/<uuid>.<ext>

import { safeRelativePath } from "../supabase/functions/ownlog-mcp/pure.ts";

export type FileAccess = {
  sign(path: string, expiresInSeconds: number): Promise<string | null>;
  handle(req: Request, url: URL): Promise<Response | null>;
};

type Clock = () => number;

const TYPES: Record<string, string> = {
  jpg: "image/jpeg",
  jpeg: "image/jpeg",
  png: "image/png",
  webp: "image/webp",
  gif: "image/gif",
  heic: "image/heic",
  m4a: "audio/mp4",
  aac: "audio/aac",
  mp3: "audio/mpeg",
  mp4: "video/mp4",
  pdf: "application/pdf",
  txt: "text/plain; charset=utf-8",
};

export async function createFileAccess(env: {
  filesDir?: string;
  publicBaseUrl?: string;
  fileUrlSecret?: string;
  s3?: S3Config;
}, clock: Clock = () => Math.floor(Date.now() / 1000)): Promise<FileAccess> {
  if (env.s3) return s3Access(env.s3, clock);
  const root = env.filesDir ?? "./inbox";
  const secret = env.fileUrlSecret;
  const base = (env.publicBaseUrl ?? "").replace(/\/+$/, "");
  if (!secret) {
    return {
      sign: () => Promise.resolve(null),
      handle: () => Promise.resolve(null),
    };
  }
  await Deno.mkdir(root, { recursive: true });
  return localAccess(await Deno.realPath(root), secret, base, clock);
}

export type S3Config = {
  endpoint: string;
  bucket: string;
  accessKeyId: string;
  secretAccessKey: string;
  region: string;
};

function localAccess(root: string, secret: string, base: string, clock: Clock): FileAccess {
  return {
    async sign(path, expiresInSeconds) {
      const relative = safeRelativePath(path);
      if (!relative || !base) return null;
      const exp = clock() + expiresInSeconds;
      const sig = await hmacHex(secret, `${exp}\n${relative}`);
      const encoded = relative.split("/").map(encodeURIComponent).join("/");
      return `${base}/files/${exp}/${sig}/${encoded}`;
    },
    async handle(_req, url) {
      const prefix = "/files/";
      if (!url.pathname.startsWith(prefix)) return null;
      const rest = url.pathname.slice(prefix.length);
      const slash = rest.indexOf("/");
      const second = slash === -1 ? -1 : rest.indexOf("/", slash + 1);
      if (second === -1) return new Response("not found", { status: 404 });
      const exp = Number(rest.slice(0, slash));
      const sig = rest.slice(slash + 1, second);
      const path = safeRelativePath(rest.slice(second + 1));
      if (!path || !Number.isFinite(exp) || clock() > exp) {
        return new Response("not found", { status: 404 });
      }
      const expected = await hmacHex(secret, `${exp}\n${path}`);
      if (!timingSafeEqual(expected, sig)) return new Response("not found", { status: 404 });
      const file = await resolveInside(root, path);
      if (!file) return new Response("not found", { status: 404 });
      const bytes = await Deno.readFile(file);
      const ext = path.split(".").pop()?.toLowerCase() ?? "";
      return new Response(bytes, {
        status: 200,
        headers: {
          "content-type": TYPES[ext] ?? "application/octet-stream",
          "cache-control": "private, max-age=60",
        },
      });
    },
  };
}

async function resolveInside(root: string, relative: string): Promise<string | null> {
  const candidate = `${root}/${relative}`;
  try {
    const real = await Deno.realPath(candidate);
    const prefix = root.endsWith("/") ? root : `${root}/`;
    if (real !== root && !real.startsWith(prefix)) return null;
    const info = await Deno.stat(real);
    return info.isFile ? real : null;
  } catch {
    return null;
  }
}

function s3Access(config: S3Config, clock: Clock): FileAccess {
  return {
    sign(path, expiresInSeconds) {
      const relative = safeRelativePath(path);
      if (!relative) return Promise.resolve(null);
      return presignGet(config, relative, expiresInSeconds, clock);
    },
    handle() {
      return Promise.resolve(null);
    },
  };
}

export async function presignGet(
  config: S3Config,
  key: string,
  expiresInSeconds: number,
  clock: Clock = () => Math.floor(Date.now() / 1000),
): Promise<string> {
  const endpoint = new URL(config.endpoint);
  const amzDate = amzTimestamp(clock());
  const dateStamp = amzDate.slice(0, 8);
  const scope = `${dateStamp}/${config.region}/s3/aws4_request`;
  const credential = `${config.accessKeyId}/${scope}`;
  const encodedKey = key.split("/").map(encodeRfc3986).join("/");
  const canonicalUri = `${endpoint.pathname.replace(/\/+$/, "")}/${encodeRfc3986(config.bucket)}/${encodedKey}`
    .replace(/\/{2,}/g, "/");
  const params = [
    ["X-Amz-Algorithm", "AWS4-HMAC-SHA256"],
    ["X-Amz-Credential", credential],
    ["X-Amz-Date", amzDate],
    ["X-Amz-Expires", String(expiresInSeconds)],
    ["X-Amz-SignedHeaders", "host"],
  ];
  const canonicalQuery = params
    .map(([name, value]) => `${encodeRfc3986(name)}=${encodeRfc3986(value)}`)
    .sort()
    .join("&");
  const host = endpoint.host;
  const canonical = [
    "GET",
    canonicalUri,
    canonicalQuery,
    `host:${host}`,
    "",
    "host",
    "UNSIGNED-PAYLOAD",
  ].join("\n");
  const stringToSign = [
    "AWS4-HMAC-SHA256",
    amzDate,
    scope,
    await sha256Hex(canonical),
  ].join("\n");
  const signature = await signingKeyHex(config.secretAccessKey, dateStamp, config.region, stringToSign);
  return `${endpoint.origin}${canonicalUri}?${canonicalQuery}&X-Amz-Signature=${signature}`;
}

function amzTimestamp(unixSeconds: number): string {
  const iso = new Date(unixSeconds * 1000).toISOString().replace(/[-:]/g, "").replace(/\.\d{3}Z$/, "Z");
  return iso;
}

function encodeRfc3986(value: string): string {
  return encodeURIComponent(value).replace(/[!'()*]/g, (char) =>
    `%${char.charCodeAt(0).toString(16).toUpperCase()}`
  );
}

async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function hmac(key: Uint8Array, message: string): Promise<Uint8Array> {
  const cryptoKey = await crypto.subtle.importKey(
    "raw",
    key as BufferSource,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signed = await crypto.subtle.sign("HMAC", cryptoKey, new TextEncoder().encode(message));
  return new Uint8Array(signed);
}

async function signingKeyHex(
  secret: string,
  dateStamp: string,
  region: string,
  stringToSign: string,
): Promise<string> {
  const dateKey = await hmac(new TextEncoder().encode(`AWS4${secret}`), dateStamp);
  const regionKey = await hmac(dateKey, region);
  const serviceKey = await hmac(regionKey, "s3");
  const signingKey = await hmac(serviceKey, "aws4_request");
  const sig = await hmac(signingKey, stringToSign);
  return [...sig].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function hmacHex(secret: string, message: string): Promise<string> {
  const sig = await hmac(new TextEncoder().encode(secret), message);
  return [...sig].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
