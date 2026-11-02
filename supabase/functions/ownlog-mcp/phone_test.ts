import { assertEquals } from "jsr:@std/assert@1";

import {
  awaitPhoneCommand,
  buildPhoneCall,
  isPhoneOnline,
  redactLoggedArgs,
  runPhoneTool,
} from "./phone.ts";
import type { PhoneCommandRow, PhoneEnqueue, PhonePresence, Store } from "./store.ts";

const USER = "11111111-1111-4111-8111-111111111111";
const TOKEN = "44444444-4444-4444-8444-444444444444";

Deno.test("phone calls reject a token without can_control and bad arguments", async () => {
  const store = new MemoryPhone();
  const denied = await runPhoneTool("phone_tap", { text: "搜索" }, {
    token: { id: TOKEN, user_id: USER, can_control: false },
    store: store as unknown as Store,
    now: new Date("2026-11-02T00:00:00.000Z"),
  });
  assertEquals(denied.isError, true);
  assertEquals(JSON.parse(denied.text).error.includes("can_control"), true);
  assertEquals(store.enqueued.length, 0);

  assertEquals("error" in buildPhoneCall("phone_tap", {}), true);
  assertEquals(buildPhoneCall("phone_tap", { text: "搜索", view_id: "com.x:id/a" }), {
    error: "tap 只能给文字或 view_id 其中一个",
  });
  assertEquals(buildPhoneCall("phone_swipe", { direction: "up" }), {
    action: "swipe",
    args: { direction: "up" },
  });
  assertEquals(buildPhoneCall("phone_open_app", { name: "小红书" }), {
    action: "open_app",
    args: { name: "小红书" },
  });
  assertEquals(buildPhoneCall("phone_type", { text: "" }), { error: "type 需要 text" });
  assertEquals(buildPhoneCall("phone_wait", { ms: 0 }), { error: "等待时间要在 1 到 10000 毫秒" });
  assertEquals(buildPhoneCall("phone_wait", { ms: 400 }), { action: "wait", args: { ms: 400 } });
  assertEquals(redactLoggedArgs("phone_type", { text: "你好" }), { text: "[2 chars]" });
  assertEquals(redactLoggedArgs("phone_tap", { text: "搜索" }), { text: "搜索" });
});

Deno.test("phone wait cancels a command the device never claims", async () => {
  const store = new MemoryPhone();
  let clock = 1_000;
  const row: PhoneCommandRow = {
    id: "99999999-9999-4999-8999-999999999999",
    user_id: USER,
    device_id: null,
    action: "tap",
    args: { text: "搜索" },
    status: "pending",
    result: null,
    created_at: new Date(clock).toISOString(),
    started_at: null,
    finished_at: null,
    expires_at: new Date(clock + 120_000).toISOString(),
  };
  store.rows.set(row.id, row);
  const waited = await awaitPhoneCommand(
    store,
    USER,
    row.id,
    clock + 1_000,
    (ms) => {
      clock += ms;
      return Promise.resolve();
    },
    () => clock,
  );
  assertEquals(waited.kind, "timeout");
  assertEquals(store.rows.get(row.id)?.status, "cancelled");
  assertEquals(isPhoneOnline(null, new Date(clock)), false);
  const fresh: PhonePresence = {
    user_id: USER,
    device_id: "d",
    a11y_connected: true,
    remote_enabled: true,
    foreground_package: "com.xingin.xhs",
    last_seen: new Date(clock).toISOString(),
  };
  assertEquals(isPhoneOnline(fresh, new Date(clock)), true);
  assertEquals(isPhoneOnline({ ...fresh, remote_enabled: false }, new Date(clock)), false);
});

class MemoryPhone {
  readonly enqueued: PhoneEnqueue[] = [];
  readonly rows = new Map<string, PhoneCommandRow>();

  enqueuePhoneCommand(input: PhoneEnqueue): Promise<PhoneCommandRow> {
    this.enqueued.push(input);
    const row: PhoneCommandRow = {
      id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      user_id: input.userId,
      device_id: null,
      action: input.action,
      args: input.args,
      status: "pending",
      result: null,
      created_at: input.expiresAt,
      started_at: null,
      finished_at: null,
      expires_at: input.expiresAt,
    };
    this.rows.set(row.id, row);
    return Promise.resolve(row);
  }

  getPhoneCommand(_userId: string, id: string): Promise<PhoneCommandRow | null> {
    return Promise.resolve(this.rows.get(id) ?? null);
  }

  settlePhoneCommand(
    _userId: string,
    id: string,
    fromStatus: string,
    toStatus: "cancelled" | "failed",
    result: Record<string, unknown>,
  ): Promise<PhoneCommandRow | null> {
    const row = this.rows.get(id);
    if (!row || row.status !== fromStatus) return Promise.resolve(null);
    const next = { ...row, status: toStatus, result };
    this.rows.set(id, next);
    return Promise.resolve(next);
  }

  phoneStatus(): Promise<{ presence: PhonePresence | null; pending: number }> {
    return Promise.resolve({ presence: null, pending: 0 });
  }
}
