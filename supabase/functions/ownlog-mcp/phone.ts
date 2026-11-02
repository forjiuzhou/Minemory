// Remote phone tools. They enqueue a row and wait for the signed-in phone to finish it.

import { z } from "npm:zod@4.6.7";

import type { PhoneCommandRow, PhonePresence, Store } from "./store.ts";

export const COMMAND_TTL_MS = 120_000;
export const TOOL_WAIT_MS = 25_000;
export const POLL_MS = 400;
export const PRESENCE_FRESH_MS = 20_000;

const CONTROL_DENIED = "这个令牌不能操作手机。签发时可设 p_can_control := true。";
const OFFLINE = "手机不在线。确认无障碍已连接、设置里「允许远程控制」开着，并且自记已登录。";

export type PhoneToolResult = {
  text: string;
  count: number | null;
  isError?: boolean;
};

export type PhoneHost = {
  token: { id: string; user_id: string; can_control: boolean };
  store: Store;
  now: Date;
  sleep?: (ms: number) => Promise<void>;
  nowMs?: () => number;
};

type AnyArgs = Record<string, unknown>;

const readOnlyPhone = {
  readOnlyHint: true,
  destructiveHint: false,
  idempotentHint: true,
  openWorldHint: true,
};

const actPhone = {
  readOnlyHint: false,
  destructiveHint: false,
  idempotentHint: false,
  openWorldHint: true,
};

function fail(message: string, extra?: Record<string, unknown>): PhoneToolResult {
  return {
    text: JSON.stringify({ error: message, ...extra }, null, 2),
    count: 0,
    isError: true,
  };
}

function textOf(value: unknown): string | undefined {
  return typeof value === "string" && value.trim() !== "" ? value.trim() : undefined;
}

function emptyArgs(args: AnyArgs): boolean {
  return Object.keys(args).length === 0;
}

export function isPhoneOnline(presence: PhonePresence | null, now: Date, freshMs = PRESENCE_FRESH_MS): boolean {
  if (!presence?.a11y_connected || !presence.remote_enabled) return false;
  const seen = Date.parse(presence.last_seen);
  if (!Number.isFinite(seen)) return false;
  return now.getTime() - seen <= freshMs;
}

export function buildPhoneCall(
  tool: string,
  args: AnyArgs,
): { action: string; args: Record<string, unknown> } | { error: string } {
  switch (tool) {
    case "phone_dump_ui":
      return emptyArgs(args) ? { action: "dump_ui", args: {} } : { error: "这条命令不带参数" };
    case "phone_back":
      return emptyArgs(args) ? { action: "back", args: {} } : { error: "这条命令不带参数" };
    case "phone_home":
      return emptyArgs(args) ? { action: "home", args: {} } : { error: "这条命令不带参数" };
    case "phone_recents":
      return emptyArgs(args) ? { action: "recents", args: {} } : { error: "这条命令不带参数" };
    case "phone_tap":
      return buildTap(args);
    case "phone_swipe":
      return buildSwipe(args);
    case "phone_type":
      return buildType(args);
    case "phone_open_app":
      return buildOpen(args);
    case "phone_wait":
      return buildWait(args);
    default:
      return { error: "认不出这条命令" };
  }
}

function buildTap(args: AnyArgs): { action: string; args: Record<string, unknown> } | { error: string } {
  const text = textOf(args.text);
  const viewId = textOf(args.view_id);
  if (text && viewId) return { error: "tap 只能给文字或 view_id 其中一个" };
  if (viewId) {
    if (viewId.length > 200 || !/^[^\s]+:id\/[^\s]+$/.test(viewId)) {
      return { error: "view id 要写成 包名:id/名字" };
    }
    return { action: "tap", args: { view_id: viewId } };
  }
  if (!text || text.length > 200) return { error: "tap 需要 text 或 view_id" };
  return { action: "tap", args: { text } };
}

function buildSwipe(args: AnyArgs): { action: string; args: Record<string, unknown> } | { error: string } {
  const direction = textOf(args.direction)?.toLowerCase();
  const coords = [args.x1, args.y1, args.x2, args.y2];
  const hasCoord = coords.some((value) => value !== undefined);
  if (direction && hasCoord) return { error: "swipe 只能给方向或坐标其中一个" };
  if (direction) {
    if (!["up", "down", "left", "right"].includes(direction)) return { error: "swipe 需要 direction，或 x1 y1 x2 y2" };
    if (args.duration_ms !== undefined) return { error: "方向滑动不带 duration_ms" };
    return { action: "swipe", args: { direction } };
  }
  if (coords.some((value) => typeof value !== "number" || !Number.isFinite(value))) {
    return { error: "swipe 需要 direction，或 x1 y1 x2 y2" };
  }
  const [x1, y1, x2, y2] = coords as number[];
  if ([x1, y1, x2, y2].some((value) => value < 0 || value > 20_000)) return { error: "滑动坐标无效" };
  if (x1 === x2 && y1 === y2) return { error: "滑动坐标无效" };
  let duration = 320;
  if (args.duration_ms !== undefined) {
    if (typeof args.duration_ms !== "number" || !Number.isInteger(args.duration_ms)) return { error: "滑动坐标无效" };
    if (args.duration_ms < 50 || args.duration_ms > 3_000) return { error: "滑动坐标无效" };
    duration = args.duration_ms;
  }
  return { action: "swipe", args: { x1, y1, x2, y2, duration_ms: duration } };
}

function buildType(args: AnyArgs): { action: string; args: Record<string, unknown> } | { error: string } {
  const text = typeof args.text === "string" ? args.text : "";
  if (text.length === 0) return { error: "type 需要 text" };
  if (text.length > 2000) return { error: "文字太长" };
  return { action: "type", args: { text } };
}

function buildOpen(args: AnyArgs): { action: string; args: Record<string, unknown> } | { error: string } {
  const packageName = textOf(args.package);
  const name = textOf(args.name);
  if (!packageName && !name) return { error: "open_app 需要 package 或 name" };
  if (packageName && !/^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/.test(packageName)) {
    return { error: "包名不像 Android 包名" };
  }
  if (name && name.length > 80) return { error: "应用名太长" };
  return {
    action: "open_app",
    args: {
      ...(packageName ? { package: packageName } : {}),
      ...(name ? { name } : {}),
    },
  };
}

function buildWait(args: AnyArgs): { action: string; args: Record<string, unknown> } | { error: string } {
  const ms = args.ms;
  if (typeof ms !== "number" || !Number.isInteger(ms) || ms < 1 || ms > 10_000) {
    return { error: "等待时间要在 1 到 10000 毫秒" };
  }
  return { action: "wait", args: { ms } };
}

export function presentPhoneCommand(row: PhoneCommandRow): Record<string, unknown> {
  const result = row.result && typeof row.result === "object" ? row.result : {};
  return {
    ...result,
    command_id: row.id,
    status: row.status,
    action: row.action,
  };
}

export function redactLoggedArgs(tool: string, args: unknown): unknown {
  if (tool !== "phone_type" || args == null || typeof args !== "object" || Array.isArray(args)) return args;
  const copy = { ...(args as Record<string, unknown>) };
  if (typeof copy.text === "string") copy.text = `[${copy.text.length} chars]`;
  return copy;
}

export async function awaitPhoneCommand(
  store: Pick<Store, "getPhoneCommand" | "settlePhoneCommand">,
  userId: string,
  id: string,
  deadlineMs: number,
  sleep: (ms: number) => Promise<void>,
  nowMs: () => number,
): Promise<{ kind: "finished"; row: PhoneCommandRow } | { kind: "timeout"; row: PhoneCommandRow | null }> {
  let latest: PhoneCommandRow | null = null;
  while (nowMs() < deadlineMs) {
    const row = await store.getPhoneCommand(userId, id);
    latest = row;
    if (!row) return { kind: "timeout", row: null };
    if (row.status === "done" || row.status === "failed" || row.status === "cancelled") {
      return { kind: "finished", row };
    }
    const expires = Date.parse(row.expires_at);
    if (row.status === "pending" && Number.isFinite(expires) && expires <= nowMs()) {
      await store.settlePhoneCommand(userId, id, "pending", "cancelled", {
        ok: false,
        error: "命令已过期",
      }, new Date(nowMs()).toISOString());
      return { kind: "timeout", row: { ...row, status: "cancelled" } };
    }
    const pause = Math.min(POLL_MS, deadlineMs - nowMs());
    if (pause <= 0) break;
    await sleep(pause);
  }
  if (latest?.status === "pending") {
    const settled = await store.settlePhoneCommand(userId, id, "pending", "cancelled", {
      ok: false,
      error: "手机没有在 25 秒内完成",
    }, new Date(nowMs()).toISOString());
    if (!settled) {
      const again = await store.getPhoneCommand(userId, id);
      if (again && (again.status === "done" || again.status === "failed" || again.status === "cancelled")) {
        return { kind: "finished", row: again };
      }
    }
  }
  return { kind: "timeout", row: latest };
}

function presentStatus(
  presence: PhonePresence | null,
  pending: number,
  now: Date,
): PhoneToolResult {
  const online = isPhoneOnline(presence, now);
  return {
    text: JSON.stringify({
      online,
      a11y_connected: presence?.a11y_connected === true,
      remote_enabled: presence?.remote_enabled === true,
      last_seen: presence?.last_seen ?? null,
      foreground_package: presence?.foreground_package ?? null,
      device_id: presence?.device_id ?? null,
      pending,
      stale_after_ms: PRESENCE_FRESH_MS,
    }, null, 2),
    count: pending,
  };
}

export async function runPhoneTool(tool: string, args: AnyArgs, host: PhoneHost): Promise<PhoneToolResult> {
  const safeArgs = args && typeof args === "object" ? args : {};
  if (!host.token.can_control) return fail(CONTROL_DENIED);
  if (tool === "phone_status") {
    const snap = await host.store.phoneStatus(host.token.user_id, host.now.toISOString());
    return presentStatus(snap.presence, snap.pending, host.now);
  }
  const built = buildPhoneCall(tool, safeArgs);
  if ("error" in built) return fail(built.error);
  const snap = await host.store.phoneStatus(host.token.user_id, host.now.toISOString());
  if (!isPhoneOnline(snap.presence, host.now)) return fail(OFFLINE);
  const row = await host.store.enqueuePhoneCommand({
    userId: host.token.user_id,
    tokenId: host.token.id,
    action: built.action,
    args: built.args,
    expiresAt: new Date(host.now.getTime() + COMMAND_TTL_MS).toISOString(),
  });
  const sleep = host.sleep ?? ((ms: number) => new Promise((resolve) => setTimeout(resolve, ms)));
  const nowMs = host.nowMs ?? (() => Date.now());
  const waited = await awaitPhoneCommand(
    host.store,
    host.token.user_id,
    row.id,
    nowMs() + TOOL_WAIT_MS,
    sleep,
    nowMs,
  );
  if (waited.kind === "finished") {
    const payload = presentPhoneCommand(waited.row);
    const ok = waited.row.status === "done" && payload.ok === true;
    return { text: JSON.stringify(payload, null, 2), count: ok ? 1 : 0, isError: !ok };
  }
  return fail("手机没有在 25 秒内完成", {
    command_id: row.id,
    status: waited.row?.status ?? "pending",
  });
}

export function phoneToolSpecs() {
  const call = (name: string) => (args: AnyArgs, host: PhoneHost) => runPhoneTool(name, args, host);
  return [
    {
      name: "phone_status",
      title: "手机状态",
      description:
        "这台已登录手机现在能不能被操作：无障碍是否连着、允许远程控制是否开着、最后心跳、前台包名、还没被领走的命令数。超过 20 秒没有心跳视为不在线。需要令牌 can_control。",
      inputSchema: {},
      annotations: readOnlyPhone,
      call: call("phone_status"),
    },
    {
      name: "phone_dump_ui",
      title: "读取界面",
      description:
        "让手机把当前界面的无障碍树交回来：包名、文字、contentDescription、resource-id、是否可点、bounds。密码框的文字会留空。不截图。大约等 25 秒。",
      inputSchema: {},
      annotations: readOnlyPhone,
      call: call("phone_dump_ui"),
    },
    {
      name: "phone_tap",
      title: "点击",
      description:
        "按可见文字或完整 resource-id（包名:id/名字）点击。不点自记自己的控件。返回里带点击之后的界面树。",
      inputSchema: {
        text: z.string().optional().describe("要点的文字或内容说明。和 view_id 二选一。"),
        view_id: z.string().optional().describe("完整 resource-id，例如 com.xingin.xhs:id/search。"),
      },
      annotations: actPhone,
      call: call("phone_tap"),
    },
    {
      name: "phone_swipe",
      title: "滑动",
      description: "direction 为 up、down、left、right，或给出 x1 y1 x2 y2（像素）。返回滑动之后的界面树。",
      inputSchema: {
        direction: z.enum(["up", "down", "left", "right"]).optional(),
        x1: z.number().optional(),
        y1: z.number().optional(),
        x2: z.number().optional(),
        y2: z.number().optional(),
        duration_ms: z.number().int().optional().describe("坐标滑动的时长，50 到 3000。方向滑动忽略此项。"),
      },
      annotations: actPhone,
      call: call("phone_swipe"),
    },
    {
      name: "phone_back",
      title: "返回",
      description: "系统返回。返回之后带上界面树。",
      inputSchema: {},
      annotations: actPhone,
      call: call("phone_back"),
    },
    {
      name: "phone_home",
      title: "主页",
      description: "回到主屏。返回之后带上界面树。",
      inputSchema: {},
      annotations: actPhone,
      call: call("phone_home"),
    },
    {
      name: "phone_recents",
      title: "最近任务",
      description: "打开最近任务。返回之后带上界面树。",
      inputSchema: {},
      annotations: actPhone,
      call: call("phone_recents"),
    },
    {
      name: "phone_type",
      title: "输入",
      description:
        "往当前焦点上的可编辑控件写入文字。密码框会在手机上被拒绝。不会写入自记自己的输入框。访问日志只记字数，不记正文。",
      inputSchema: {
        text: z.string().describe("要写入的文字。"),
      },
      annotations: actPhone,
      call: call("phone_type"),
    },
    {
      name: "phone_open_app",
      title: "打开应用",
      description:
        "按包名或应用名打开。例如 name 填小红书、微信、拼多多，或 package 填 com.xingin.xhs。不会打开自记自己。返回打开之后的界面树。",
      inputSchema: {
        package: z.string().optional().describe("Android 包名。"),
        name: z.string().optional().describe("应用显示名，或微信、小红书、拼多多。"),
      },
      annotations: actPhone,
      call: call("phone_open_app"),
    },
    {
      name: "phone_wait",
      title: "等待",
      description: "让手机先等若干毫秒（1 到 10000），再把当时的界面树交回来。",
      inputSchema: {
        ms: z.number().int().describe("等待毫秒。"),
      },
      annotations: actPhone,
      call: call("phone_wait"),
    },
  ];
}
