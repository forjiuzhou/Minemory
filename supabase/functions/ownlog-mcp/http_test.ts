import { assertEquals } from "jsr:@std/assert@1";

import { sha256Hex } from "./auth.ts";
import { createHandler } from "./http.ts";
import type { AccessLog, AgentToken, EventPatch, EventQuery, EventRow, PhoneCommandRow, PhoneEnqueue, PhonePresence, SearchQuery, Store } from "./store.ts";

const TOKEN = `olk_${"b".repeat(40)}`;
const USER = "11111111-1111-4111-8111-111111111111";

Deno.test("mcp http: auth, initialize, tools, and scoped calls", async () => {
  const hash = await sha256Hex(TOKEN);
  const logs: AccessLog[] = [];
  const store = new FakeStore(hash, logs);
  const handler = createHandler(store, { now: () => new Date("2026-10-22T04:00:00.000Z") });

  const missing = await handler(new Request("https://ownlog.test/mcp", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      accept: "application/json, text/event-stream",
    },
    body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "ping" }),
  }));
  assertEquals(missing.status, 401);

  const init = await rpc(handler, {
    jsonrpc: "2.0",
    id: 1,
    method: "initialize",
    params: {
      protocolVersion: "2025-03-26",
      capabilities: {},
      clientInfo: { name: "test", version: "0" },
    },
  });
  assertEquals(init.status, 200);
  const initBody = await init.json();
  assertEquals(initBody.result.protocolVersion, "2025-03-26");
  assertEquals(initBody.result.serverInfo.name, "ownlog");

  const listed = await rpc(handler, { jsonrpc: "2.0", id: 2, method: "tools/list" }, {
    accept: "application/json",
  });
  assertEquals(listed.status, 200);
  const names = (await listed.json()).result.tools.map((tool: { name: string; description: string }) => tool.name);
  assertEquals(names, [
    "search",
    "timeline",
    "get_event",
    "app_usage",
    "places",
    "inbox",
    "annotate",
    "stats",
    "phone_status",
    "phone_dump_ui",
    "phone_tap",
    "phone_swipe",
    "phone_back",
    "phone_home",
    "phone_recents",
    "phone_type",
    "phone_open_app",
    "phone_wait",
  ]);

  const ping = await rpc(handler, { jsonrpc: "2.0", id: 3, method: "ping" });
  assertEquals((await ping.json()).result, {});

  const note = await rpc(handler, { jsonrpc: "2.0", method: "notifications/initialized" });
  assertEquals(note.status, 202);

  const found = await rpc(handler, {
    jsonrpc: "2.0",
    id: 4,
    method: "tools/call",
    params: { name: "search", arguments: { query: "杭州", types: ["note"] } },
  });
  const foundBody = await found.json();
  const payload = JSON.parse(foundBody.result.content[0].text);
  assertEquals(payload.events.length, 1);
  assertEquals(payload.events[0].content, "杭州的雨");
  assertEquals(store.lastSearch?.userId, USER);
  assertEquals(store.lastSearch?.types, ["note"]);
  assertEquals(logs.at(-1)?.tool, "search");
  assertEquals(logs.at(-1)?.resultCount, 1);

  const denied = await rpc(handler, {
    jsonrpc: "2.0",
    id: 5,
    method: "tools/call",
    params: { name: "search", arguments: { query: "x", types: ["screen_ocr"] } },
  });
  const deniedBody = await denied.json();
  assertEquals(deniedBody.result.isError, true);
  assertEquals(JSON.parse(deniedBody.result.content[0].text).error.includes("screen_ocr"), true);

  const line = await rpc(handler, {
    jsonrpc: "2.0",
    id: 6,
    method: "tools/call",
    params: { name: "timeline", arguments: { date: "2026-10-22" } },
  });
  const events = JSON.parse((await line.json()).result.content[0].text).events;
  assertEquals(events[0].collapsed_count, 2);
  assertEquals(events[0].content, "同一屏");

  const write = await rpc(handler, {
    jsonrpc: "2.0",
    id: 7,
    method: "tools/call",
    params: { name: "annotate", arguments: { id: "22222222-2222-4222-8222-222222222222", summary: "记一下" } },
  });
  const writeBody = await write.json();
  assertEquals(writeBody.result.isError, true);

  const got = await rpc(handler, {
    jsonrpc: "2.0",
    id: 8,
    method: "tools/call",
    params: { name: "get_event", arguments: { id: "33333333-3333-4333-8333-333333333333" } },
  });
  const full = JSON.parse((await got.json()).result.content[0].text);
  assertEquals(full.file_url, "https://files.example/signed");
  assertEquals(full.file_url_expires_in, 3600);
  assertEquals(full.content_truncated, false);

  const stats = await rpc(handler, {
    jsonrpc: "2.0",
    id: 9,
    method: "tools/call",
    params: { name: "stats", arguments: { days: 1 } },
  });
  const counts = JSON.parse((await stats.json()).result.content[0].text);
  assertEquals(counts.timezone, "Asia/Singapore");
  assertEquals(counts.by_type.note, 1);

  const gone = await handler(new Request("https://ownlog.test/mcp", { method: "GET" }));
  assertEquals(gone.status, 405);

  const usage = await rpc(handler, {
    jsonrpc: "2.0",
    id: 10,
    method: "tools/call",
    params: { name: "app_usage", arguments: { from: "2026-10-22", to: "2026-10-22" } },
  });
  const apps = JSON.parse((await usage.json()).result.content[0].text).apps;
  assertEquals(apps[0].duration_ms, 5000);
  assertEquals(apps[0].sessions, 1);

  const blocked = await rpc(handler, {
    jsonrpc: "2.0",
    id: 11,
    method: "tools/call",
    params: { name: "phone_tap", arguments: { text: "搜索" } },
  });
  const blockedBody = await blocked.json();
  assertEquals(blockedBody.result.isError, true);
  assertEquals(JSON.parse(blockedBody.result.content[0].text).error.includes("can_control"), true);
  assertEquals(store.enqueued.length, 0);
});

Deno.test("mcp http: can_control token waits for the phone result", async () => {
  const hash = await sha256Hex(TOKEN);
  const logs: AccessLog[] = [];
  const store = new FakeStore(hash, logs, { canControl: true, autoFinish: true });
  const handler = createHandler(store, { now: () => new Date("2026-10-22T04:00:00.000Z") });
  const tapped = await rpc(handler, {
    jsonrpc: "2.0",
    id: 12,
    method: "tools/call",
    params: { name: "phone_open_app", arguments: { name: "小红书" } },
  });
  const body = await tapped.json();
  const payload = JSON.parse(body.result.content[0].text);
  assertEquals(body.result.isError ?? false, false);
  assertEquals(payload.ok, true);
  assertEquals(payload.foreground_package, "com.xingin.xhs");
  assertEquals(String(payload.ui_tree).includes("package=com.xingin.xhs"), true);
  assertEquals(store.enqueued[0]?.action, "open_app");
  assertEquals(store.enqueued[0]?.args, { name: "小红书" });
  assertEquals(logs.at(-1)?.tool, "phone_open_app");

  const typed = await rpc(handler, {
    jsonrpc: "2.0",
    id: 13,
    method: "tools/call",
    params: { name: "phone_type", arguments: { text: "不要记进日志" } },
  });
  assertEquals((await typed.json()).result.isError ?? false, false);
  assertEquals(logs.at(-1)?.args, { text: "[6 chars]" });
});

function post(body: unknown, headers: Record<string, string> = {}): Request {
  return new Request("https://ownlog.test/mcp", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      accept: "application/json, text/event-stream",
      authorization: `Bearer ${TOKEN}`,
      ...headers,
    },
    body: JSON.stringify(body),
  });
}

function rpc(handler: (req: Request) => Promise<Response>, body: unknown, headers: Record<string, string> = {}) {
  return handler(post(body, headers));
}

class FakeStore implements Store {
  lastSearch: SearchQuery | null = null;
  readonly enqueued: PhoneEnqueue[] = [];
  constructor(
    private readonly hash: string,
    private readonly logs: AccessLog[],
    private readonly options: { canControl?: boolean; autoFinish?: boolean } = {},
  ) {}

  findToken(tokenHash: string): Promise<AgentToken | null> {
    if (tokenHash !== this.hash) return Promise.resolve(null);
    return Promise.resolve({
      id: "44444444-4444-4444-8444-444444444444",
      name: "test",
      user_id: USER,
      scopes: ["note", "photo", "ui_text", "app_fg", "app_bg", "location"],
      can_write: false,
      can_control: this.options.canControl === true,
    });
  }

  touchToken(): Promise<void> {
    return Promise.resolve();
  }

  logAccess(entry: AccessLog): Promise<void> {
    this.logs.push(entry);
    return Promise.resolve();
  }

  searchEvents(query: SearchQuery): Promise<EventRow[]> {
    this.lastSearch = query;
    return Promise.resolve([{
      id: "55555555-5555-4555-8555-555555555555",
      occurred_at: "2026-10-22T01:00:00+00:00",
      type: "note",
      content: "杭州的雨",
      extras: {},
    }]);
  }

  listEvents(query: EventQuery): Promise<EventRow[]> {
    if (query.columns === "stats") {
      return Promise.resolve([{ type: "note", occurred_at: "2026-10-22T01:00:00.000Z" }]);
    }
    if (query.columns === "usage") {
      return Promise.resolve([{
        type: "app_bg",
        package_name: "com.tencent.mm",
        app_label: "微信",
        occurred_at: "2026-10-22T01:00:05.000Z",
        extras: { duration_ms: 5000, session_id: "s1" },
      }]);
    }
    if (query.types?.includes("ui_text") || query.columns === "full" && query.order === "asc") {
      return Promise.resolve([
        {
          id: "66666666-6666-4666-8666-666666666666",
          type: "ui_text",
          package_name: "com.tencent.mm",
          occurred_at: "2026-10-22T01:00:00.000Z",
          content: "同一屏",
          extras: {},
        },
        {
          id: "77777777-7777-4777-8777-777777777777",
          type: "ui_text",
          package_name: "com.tencent.mm",
          occurred_at: "2026-10-22T01:00:02.000Z",
          content: "同一屏",
          extras: {},
        },
      ]);
    }
    return Promise.resolve([]);
  }

  getEvent(): Promise<EventRow | null> {
    return Promise.resolve({
      id: "33333333-3333-4333-8333-333333333333",
      type: "photo",
      occurred_at: "2026-10-22T02:00:00.000Z",
      content: "门口",
      extras: { file_path: "uid/2026/10/a.jpg" },
      user_id: USER,
    });
  }

  updateEvent(_userId: string, _id: string, _patch: EventPatch): Promise<EventRow | null> {
    return Promise.resolve(null);
  }

  signFile(path: string): Promise<string | null> {
    assertEquals(path, "uid/2026/10/a.jpg");
    return Promise.resolve("https://files.example/signed");
  }

  enqueuePhoneCommand(input: PhoneEnqueue): Promise<PhoneCommandRow> {
    this.enqueued.push(input);
    const done = this.options.autoFinish === true;
    return Promise.resolve({
      id: "88888888-8888-4888-8888-888888888888",
      user_id: input.userId,
      device_id: null,
      action: input.action,
      args: input.args,
      status: done ? "done" : "pending",
      result: done
        ? { ok: true, message: "已打开", foreground_package: "com.xingin.xhs", ui_tree: "package=com.xingin.xhs", ui_truncated: false, duration_ms: 20 }
        : null,
      created_at: "2026-10-22T04:00:00.000Z",
      started_at: done ? "2026-10-22T04:00:00.000Z" : null,
      finished_at: done ? "2026-10-22T04:00:01.000Z" : null,
      expires_at: input.expiresAt,
    });
  }

  getPhoneCommand(_userId: string, id: string): Promise<PhoneCommandRow | null> {
    const created = this.enqueued.at(-1);
    if (!created) return Promise.resolve(null);
    const done = this.options.autoFinish === true;
    return Promise.resolve({
      id,
      user_id: created.userId,
      device_id: null,
      action: created.action,
      args: created.args,
      status: done ? "done" : "pending",
      result: done
        ? {
          ok: true,
          message: "已打开",
          foreground_package: "com.xingin.xhs",
          ui_tree: "package=com.xingin.xhs",
          ui_truncated: false,
          duration_ms: 20,
        }
        : null,
      created_at: "2026-10-22T04:00:00.000Z",
      started_at: null,
      finished_at: done ? "2026-10-22T04:00:01.000Z" : null,
      expires_at: created.expiresAt,
    });
  }

  settlePhoneCommand(): Promise<PhoneCommandRow | null> {
    return Promise.resolve(null);
  }

  phoneStatus(): Promise<{ presence: PhonePresence | null; pending: number }> {
    if (this.options.canControl !== true) return Promise.resolve({ presence: null, pending: 0 });
    return Promise.resolve({
      presence: {
        user_id: USER,
        device_id: "device-1",
        a11y_connected: true,
        remote_enabled: true,
        foreground_package: "com.xingin.xhs",
        last_seen: "2026-10-22T04:00:00.000Z",
      },
      pending: 0,
    });
  }
}
