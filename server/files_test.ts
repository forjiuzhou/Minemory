import { assert, assertEquals } from "jsr:@std/assert@1";

import { createFileAccess, presignGet } from "./files.ts";

Deno.test("local file URLs expire and stay inside the directory", async () => {
  const dir = await Deno.makeTempDir();
  let now = 1_700_000_000;
  const access = await createFileAccess({
    filesDir: dir,
    publicBaseUrl: "https://ownlog.example",
    fileUrlSecret: "test-secret",
  }, () => now);
  await Deno.mkdir(`${dir}/u/2026/10`, { recursive: true });
  await Deno.writeTextFile(`${dir}/u/2026/10/a.txt`, "hello");

  const signed = await access.sign("u/2026/10/a.txt", 3600);
  assert(signed);
  assert(signed.startsWith("https://ownlog.example/files/"));
  const ok = await access.handle(new Request(signed), new URL(signed));
  assertEquals(ok?.status, 200);
  assertEquals(await ok?.text(), "hello");

  now += 3601;
  const expired = await access.handle(new Request(signed), new URL(signed));
  assertEquals(expired?.status, 404);

  assertEquals(await access.sign("../secret.txt", 60), null);
  const escaped = new URL("https://ownlog.example/files/1800000000/abcd/../secret.txt");
  assertEquals((await access.handle(new Request(escaped), escaped))?.status, 404);
});

Deno.test("s3 presign is stable and carries the key", async () => {
  const clock = () => Date.parse("2026-10-22T00:00:00.000Z") / 1000;
  const url = await presignGet({
    endpoint: "https://s3.example.com",
    bucket: "inbox",
    accessKeyId: "AKID",
    secretAccessKey: "secret",
    region: "us-east-1",
  }, "u/2026/10/a.jpg", 3600, clock);
  const again = await presignGet({
    endpoint: "https://s3.example.com",
    bucket: "inbox",
    accessKeyId: "AKID",
    secretAccessKey: "secret",
    region: "us-east-1",
  }, "u/2026/10/a.jpg", 3600, clock);
  assertEquals(url, again);
  const parsed = new URL(url);
  assertEquals(parsed.pathname, "/inbox/u/2026/10/a.jpg");
  assertEquals(parsed.searchParams.get("X-Amz-Expires"), "3600");
  assertEquals(parsed.searchParams.get("X-Amz-Signature")?.length, 64);
  assert(parsed.searchParams.get("X-Amz-Credential")?.includes("/us-east-1/s3/aws4_request"));
});
