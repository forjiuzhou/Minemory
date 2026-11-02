// VPS entry.
//   deno run -A server/standalone.ts
//   deno serve -A --port=8788 --host=127.0.0.1 server/standalone.ts
//
// DATABASE_URL is a Postgres connection string. Files come from FILES_DIR
// (signed local URLs) or from S3-compatible storage. See docs/self-host.md.

import { createHandler } from "../supabase/functions/ownlog-mcp/http.ts";
import { createFileAccess, type S3Config } from "./files.ts";
import { openDatabase, PostgresStore } from "./postgres_store.ts";

function required(name: string): string {
  const value = Deno.env.get(name);
  if (!value) throw new Error(`缺少环境变量 ${name}`);
  return value;
}

function s3FromEnv(): S3Config | undefined {
  const bucket = Deno.env.get("S3_BUCKET");
  if (!bucket) return undefined;
  return {
    endpoint: required("S3_ENDPOINT"),
    bucket,
    accessKeyId: required("S3_ACCESS_KEY_ID"),
    secretAccessKey: required("S3_SECRET_ACCESS_KEY"),
    region: Deno.env.get("S3_REGION") ?? "us-east-1",
  };
}

function launchedByDenoServe(): boolean {
  try {
    const cmdline = new TextDecoder().decode(Deno.readFileSync("/proc/self/cmdline"));
    return cmdline.split("\0").includes("serve");
  } catch {
    return false;
  }
}

const databaseUrl = required("DATABASE_URL");
const sql = openDatabase(databaseUrl);
const files = await createFileAccess({
  filesDir: Deno.env.get("FILES_DIR") ?? undefined,
  publicBaseUrl: Deno.env.get("PUBLIC_BASE_URL") ?? undefined,
  fileUrlSecret: Deno.env.get("FILE_URL_SECRET") ?? undefined,
  s3: s3FromEnv(),
});
const store = new PostgresStore(sql, files);
const handler = createHandler(store, { fileAccess: files });

export default { fetch: handler };

if (import.meta.main) {
  await sql`select 1 as ok`;
  if (!launchedByDenoServe()) {
    const hostname = Deno.env.get("BIND") ?? "127.0.0.1";
    const port = Number(Deno.env.get("PORT") ?? "8788");
    if (!Number.isFinite(port) || port <= 0) throw new Error("PORT 无效");
    Deno.serve({ hostname, port }, handler);
  }
}
