// MCP tool definitions. They talk to a Store and do not read the environment.

import { z } from "npm:zod@4.6.7";

import {
  aggregateUsage,
  asRecord,
  capLimit,
  clipText,
  collapseConsecutiveUiText,
  DEFAULT_TZ,
  DETAIL_CONTENT_CHARS,
  formatDuration,
  INBOX_TYPES,
  LIST_CONTENT_CHARS,
  localDay,
  parseRangeBound,
  PLACE_MERGE_METERS,
  readNumber,
  resolveTypes,
  safeRelativePath,
  simplifyPlaces,
  todayRange,
  toIso,
  typeAllowed,
  zonedDayRange,
  zonedStart,
} from "./pure.ts";
import type { EventQuery, EventRow, Store } from "./store.ts";

export const FILE_URL_SECONDS = 3600;

export type ToolContext = {
  token: { id: string; user_id: string; scopes: string[]; can_write: boolean };
  store: Store;
  now: Date;
};

export type ToolResult = {
  text: string;
  count: number | null;
  isError?: boolean;
};

const tzField = z.string().optional().describe(
  `IANA 时区，或 UTC+8 这种固定偏移。默认 ${DEFAULT_TZ}（UTC+8）。只影响日期边界，返回的时间仍是 ISO 8601。`,
);
const fromField = z.string().optional().describe(
  "起点，ISO 8601，或 YYYY-MM-DD（按 tz 的当地 0 点）。",
);
const toField = z.string().optional().describe(
  "终点，ISO 8601（不含该瞬间），或 YYYY-MM-DD（含当天，直到当地次日 0 点）。",
);
const typesField = z.array(z.string()).optional().describe(
  "事件类型，例如 note、share、ui_text、screen_ocr、app_fg、location。不填则用令牌允许的全部类型。",
);

type AnyArgs = Record<string, unknown>;

function text(value: unknown): string | undefined {
  return typeof value === "string" && value.trim() !== "" ? value.trim() : undefined;
}

function stringList(value: unknown): string[] | undefined {
  if (!Array.isArray(value)) return undefined;
  return value.filter((item): item is string => typeof item === "string");
}

function flag(value: unknown): boolean | undefined {
  return typeof value === "boolean" ? value : undefined;
}

function zoneOf(args: AnyArgs): string {
  return text(args.tz) ?? DEFAULT_TZ;
}

function fail(message: string): ToolResult {
  return { text: JSON.stringify({ error: message }, null, 2), count: 0, isError: true };
}

function ok(payload: unknown, count: number): ToolResult {
  return { text: JSON.stringify(payload, null, 2), count };
}

function present(row: EventRow, limit: number) {
  const content = clipText(row.content, limit);
  return {
    id: row.id ?? null,
    occurred_at: toIso(row.occurred_at),
    type: row.type,
    package_name: row.package_name ?? null,
    app_label: row.app_label ?? null,
    title: row.title ?? null,
    content: content.text,
    content_truncated: content.content_truncated,
    extras: asRecord(row.extras),
    tags: row.tags ?? null,
    summary: row.summary ?? null,
    organized_at: toIso(row.organized_at),
  };
}

function bounds(
  args: AnyArgs,
  now: Date,
  mode: "required-or-today" | "optional",
): { from: string | null; to: string | null; timezone: string } | { error: string } {
  const timezone = zoneOf(args);
  try {
    const date = text(args.date);
    if (date) {
      const range = zonedDayRange(date, timezone);
      return { from: range.from, to: range.to, timezone };
    }
    const fromRaw = text(args.from);
    const toRaw = text(args.to);
    if (!fromRaw && !toRaw) {
      if (mode === "optional") return { from: null, to: null, timezone };
      const today = todayRange(now, timezone);
      return { from: today.from, to: now.toISOString(), timezone };
    }
    const from = fromRaw ? parseRangeBound(fromRaw, timezone, "from") : null;
    const to = toRaw ? parseRangeBound(toRaw, timezone, "to") : null;
    if (from && to && from >= to) return { error: "时间范围是空的" };
    return { from, to, timezone };
  } catch (error) {
    return { error: error instanceof Error ? error.message : "无法解析时间" };
  }
}

async function search(args: AnyArgs, ctx: ToolContext): Promise<ToolResult> {
  const query = text(args.query);
  if (!query) return fail("请提供 query");
  if (query.length > 500) return fail("query 太长");
  const range = bounds(args, ctx.now, "optional");
  if ("error" in range) return fail(range.error);
  const resolved = resolveTypes(ctx.token.scopes, stringList(args.types));
  if (resolved.error) return fail(resolved.error);
  if (resolved.types && resolved.types.length === 0) {
    return ok({ timezone: range.timezone, events: [] }, 0);
  }
  const limit = capLimit(args.limit, 20, 50);
  const rows = await ctx.store.searchEvents({
    userId: ctx.token.user_id,
    query,
    types: resolved.types,
    from: range.from,
    to: range.to,
    packageName: text(args.app) ?? null,
    limit,
  });
  return ok({
    timezone: range.timezone,
    query,
    events: rows.map((row) => present(row, LIST_CONTENT_CHARS)),
  }, rows.length);
}

async function timeline(args: AnyArgs, ctx: ToolContext): Promise<ToolResult> {
  const range = bounds(args, ctx.now, "required-or-today");
  if ("error" in range) return fail(range.error);
  const resolved = resolveTypes(ctx.token.scopes, stringList(args.types));
  if (resolved.error) return fail(resolved.error);
  if (resolved.types && resolved.types.length === 0) {
    return ok({ timezone: range.timezone, from: range.from, to: range.to, events: [] }, 0);
  }
  const limit = capLimit(args.limit, 80, 200);
  const fetchLimit = Math.min(1000, limit * 6);
  const rows = await ctx.store.listEvents(listQuery(ctx, {
    types: resolved.types,
    from: range.from,
    to: range.to,
    order: "asc",
    limit: fetchLimit,
    columns: "full",
  }));
  const collapsed = collapseConsecutiveUiText(rows.map((row) => present(row, LIST_CONTENT_CHARS)));
  const events = collapsed.slice(0, limit);
  return ok({
    timezone: range.timezone,
    from: range.from,
    to: range.to,
    truncated: rows.length >= fetchLimit || collapsed.length > limit,
    events,
  }, events.length);
}

async function getEvent(args: AnyArgs, ctx: ToolContext): Promise<ToolResult> {
  const id = text(args.id);
  if (!id || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)) {
    return fail("id 需要是 uuid");
  }
  const row = await ctx.store.getEvent(ctx.token.user_id, id);
  if (!row || !typeAllowed(ctx.token.scopes, row.type)) return fail("没有这条记录");
  const event = present(row, DETAIL_CONTENT_CHARS);
  const path = safeRelativePath(asRecord(row.extras).file_path);
  let fileUrl: string | null = null;
  if (path) fileUrl = await ctx.store.signFile(path, FILE_URL_SECONDS);
  return ok({
    ...event,
    device_id: row.device_id ?? null,
    created_at: toIso(row.created_at),
    file_url: fileUrl,
    file_url_expires_in: fileUrl ? FILE_URL_SECONDS : null,
  }, 1);
}

async function appUsage(args: AnyArgs, ctx: ToolContext): Promise<ToolResult> {
  const range = bounds(args, ctx.now, "required-or-today");
  if ("error" in range) return fail(range.error);
  const resolved = resolveTypes(ctx.token.scopes, ["app_fg", "app_bg"]);
  if (resolved.error) return fail("令牌不能读取 app_fg / app_bg");
  const types = (resolved.types ?? ["app_fg", "app_bg"]).filter((type) =>
    type === "app_fg" || type === "app_bg"
  );
  if (types.length === 0) return fail("令牌不能读取 app_fg / app_bg");
  const rows = await ctx.store.listEvents(listQuery(ctx, {
    types,
    from: range.from,
    to: range.to,
    order: "asc",
    limit: 5000,
    columns: "usage",
  }));
  const apps = aggregateUsage(rows).map((row) => ({
    ...row,
    duration: formatDuration(row.duration_ms),
  }));
  return ok({
    timezone: range.timezone,
    from: range.from,
    to: range.to,
    truncated: rows.length >= 5000,
    apps,
  }, apps.length);
}

async function places(args: AnyArgs, ctx: ToolContext): Promise<ToolResult> {
  const range = bounds(args, ctx.now, "required-or-today");
  if ("error" in range) return fail(range.error);
  const resolved = resolveTypes(ctx.token.scopes, ["location"]);
  if (resolved.error) return fail(resolved.error);
  const rows = await ctx.store.listEvents(listQuery(ctx, {
    types: ["location"],
    from: range.from,
    to: range.to,
    order: "asc",
    limit: 2000,
    columns: "places",
  }));
  const points = rows.flatMap((row) => {
    const extras = asRecord(row.extras);
    const lat = readNumber(extras.lat);
    const lng = readNumber(extras.lng);
    if (lat == null || lng == null) return [];
    if (lat < -90 || lat > 90 || lng < -180 || lng > 180) return [];
    return [{
      lat,
      lng,
      at: toIso(row.occurred_at) ?? row.occurred_at,
      accuracy_m: readNumber(extras.accuracy_m),
    }];
  });
  const clusters = simplifyPlaces(points, PLACE_MERGE_METERS);
  const shown = clusters.slice(0, 200);
  return ok({
    timezone: range.timezone,
    from: range.from,
    to: range.to,
    merge_meters: PLACE_MERGE_METERS,
    truncated: rows.length >= 2000 || clusters.length > shown.length,
    places: shown,
  }, shown.length);
}

async function inbox(args: AnyArgs, ctx: ToolContext): Promise<ToolResult> {
  const unorganized = flag(args.unorganized) ?? true;
  const requested = stringList(args.types) ?? [...INBOX_TYPES];
  const resolved = resolveTypes(ctx.token.scopes, requested);
  if (resolved.error) return fail(resolved.error);
  if (resolved.types && resolved.types.length === 0) {
    return ok({ unorganized, events: [] }, 0);
  }
  const limit = capLimit(args.limit, 30, 50);
  const rows = await ctx.store.listEvents(listQuery(ctx, {
    types: resolved.types,
    from: null,
    to: null,
    unorganizedOnly: unorganized,
    order: "desc",
    limit,
    columns: "full",
  }));
  return ok({
    unorganized,
    events: rows.map((row) => present(row, LIST_CONTENT_CHARS)),
  }, rows.length);
}

async function annotate(args: AnyArgs, ctx: ToolContext): Promise<ToolResult> {
  if (!ctx.token.can_write) return fail("这个令牌是只读的，不能标注。签发时可设 p_can_write := true。");
  const id = text(args.id);
  if (!id || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)) {
    return fail("id 需要是 uuid");
  }
  const tags = stringList(args.tags);
  const hasSummary = typeof args.summary === "string";
  const summary = hasSummary ? (args.summary as string).slice(0, 2000) : undefined;
  const mark = flag(args.mark_organized) === true;
  if (!tags && summary === undefined && !mark) return fail("没有要写的内容");
  if (tags && (tags.length > 30 || tags.some((tag) => tag.length > 40))) {
    return fail("tags 太多或太长");
  }
  const existing = await ctx.store.getEvent(ctx.token.user_id, id);
  if (!existing || !typeAllowed(ctx.token.scopes, existing.type)) return fail("没有这条记录");
  const updated = await ctx.store.updateEvent(ctx.token.user_id, id, {
    tags,
    summary,
    organized_at: mark ? ctx.now.toISOString() : undefined,
  });
  if (!updated) return fail("没有这条记录");
  return ok(present(updated, LIST_CONTENT_CHARS), 1);
}

async function stats(args: AnyArgs, ctx: ToolContext): Promise<ToolResult> {
  const timezone = zoneOf(args);
  const days = capLimit(args.days, 7, 90);
  let from: string;
  let to: string;
  try {
    const today = localDay(ctx.now.toISOString(), timezone);
    const start = shiftDate(today, -(days - 1));
    from = zonedStart(start, timezone);
    to = zonedDayRange(today, timezone).to;
  } catch (error) {
    return fail(error instanceof Error ? error.message : "无法解析时区");
  }
  const resolved = resolveTypes(ctx.token.scopes, null);
  if (resolved.types && resolved.types.length === 0) {
    return ok({ timezone, days, from, to, total: 0, by_type: {}, by_day: [] }, 0);
  }
  const rows = await ctx.store.listEvents(listQuery(ctx, {
    types: resolved.types,
    from,
    to,
    order: "asc",
    limit: 20000,
    columns: "stats",
  }));
  const byType: Record<string, number> = {};
  const byDay = new Map<string, { date: string; total: number; by_type: Record<string, number> }>();
  for (const row of rows) {
    byType[row.type] = (byType[row.type] ?? 0) + 1;
    let day: string;
    try {
      day = localDay(row.occurred_at, timezone);
    } catch {
      day = row.occurred_at.slice(0, 10);
    }
    const bucket = byDay.get(day) ?? { date: day, total: 0, by_type: {} };
    bucket.total += 1;
    bucket.by_type[row.type] = (bucket.by_type[row.type] ?? 0) + 1;
    byDay.set(day, bucket);
  }
  return ok({
    timezone,
    days,
    from,
    to,
    total: rows.length,
    truncated: rows.length >= 20000,
    by_type: byType,
    by_day: [...byDay.values()],
  }, rows.length);
}

function shiftDate(date: string, days: number): string {
  const [year, month, day] = date.split("-").map(Number);
  const utc = new Date(Date.UTC(year, month - 1, day));
  utc.setUTCDate(utc.getUTCDate() + days);
  return utc.toISOString().slice(0, 10);
}

function listQuery(
  ctx: ToolContext,
  query: Omit<EventQuery, "userId" | "unorganizedOnly"> & { unorganizedOnly?: boolean },
): EventQuery {
  return {
    userId: ctx.token.user_id,
    types: query.types,
    from: query.from,
    to: query.to,
    unorganizedOnly: query.unorganizedOnly ?? false,
    order: query.order,
    limit: query.limit,
    columns: query.columns,
  };
}

const readOnly = {
  readOnlyHint: true,
  destructiveHint: false,
  idempotentHint: true,
  openWorldHint: false,
};

export const TOOLS = [
  {
    name: "search",
    title: "搜索记录",
    description:
      "在这个令牌所属用户的自记里搜索标题和正文。中文和英文都可以。排序混合了相似度和原文包含。可按类型、时间、应用（包名或应用名）缩小范围。长正文会截断，并带 content_truncated。",
    inputSchema: {
      query: z.string().describe("要找的词或句子。"),
      types: typesField,
      from: fromField,
      to: toField,
      app: z.string().optional().describe("包名（如 com.tencent.mm）或应用显示名的一部分。"),
      limit: z.number().int().optional().describe("条数，默认 20，最多 50。"),
      tz: tzField,
    },
    annotations: readOnly,
    call: search,
  },
  {
    name: "timeline",
    title: "时间线",
    description:
      "按时间正序返回一段时间里的事件。给 date（YYYY-MM-DD）或 from/to。连续、同一应用、正文相同的 ui_text 会收成一条，collapsed_count 是合并条数。默认时区 Asia/Singapore。",
    inputSchema: {
      date: z.string().optional().describe("当地某一天，YYYY-MM-DD。给出时忽略 from/to。"),
      from: fromField,
      to: toField,
      types: typesField,
      limit: z.number().int().optional().describe("合并后的条数，默认 80，最多 200。"),
      tz: tzField,
    },
    annotations: readOnly,
    call: timeline,
  },
  {
    name: "get_event",
    title: "读取一条",
    description:
      "按 id 返回完整一行。正文上限更高。若 extras.file_path 指向 inbox 里的文件，附带 1 小时有效的签名地址 file_url。",
    inputSchema: {
      id: z.string().describe("events.id，uuid。"),
    },
    annotations: readOnly,
    call: getEvent,
  },
  {
    name: "app_usage",
    title: "应用使用时长",
    description:
      "按应用汇总前台时长和会话次数。优先用 app_bg 的 extras.duration_ms；没有时长时，用同一 session_id 的 app_fg / app_bg 配对。from/to 省略时是 tz 下的今天到现在。",
    inputSchema: { from: fromField, to: toField, tz: tzField },
    annotations: readOnly,
    call: appUsage,
  },
  {
    name: "places",
    title: "地点",
    description:
      "返回 location 点。和上一个保留点相距不到 50 米的点并进同一簇，给出 first_seen 和 last_seen。坐标取这一簇的第一个点。",
    inputSchema: { from: fromField, to: toField, tz: tzField },
    annotations: readOnly,
    call: places,
  },
  {
    name: "inbox",
    title: "收件箱",
    description:
      "待整理的 share、note、photo、voice、screen_ocr。默认只看 organized_at 为空的。要看已整理的，把 unorganized 设为 false。原文件用 get_event 拿签名地址。",
    inputSchema: {
      unorganized: z.boolean().optional().describe("默认 true，只看还没整理的。"),
      types: typesField,
      limit: z.number().int().optional().describe("条数，默认 30，最多 50。"),
    },
    annotations: readOnly,
    call: inbox,
  },
  {
    name: "annotate",
    title: "标注",
    description:
      "给一条记录写 tags、summary，或把 mark_organized 设为 true 来填写 organized_at。只在令牌 can_write 为 true 时可用。mark_organized 为 false 或省略时不会清空 organized_at。tags 和 summary 在给出时会替换原值。",
    inputSchema: {
      id: z.string().describe("events.id，uuid。"),
      tags: z.array(z.string()).optional().describe("替换后的标签。"),
      summary: z.string().optional().describe("替换后的短摘要。"),
      mark_organized: z.boolean().optional().describe("true 时把 organized_at 写成现在。"),
    },
    annotations: {
      readOnlyHint: false,
      destructiveHint: false,
      idempotentHint: true,
      openWorldHint: false,
    },
    call: annotate,
  },
  {
    name: "stats",
    title: "计数",
    description:
      "最近 N 天（默认 7，最多 90）按类型和当地日期计数。一天的边界用 tz，默认 Asia/Singapore。",
    inputSchema: {
      days: z.number().int().optional().describe("天数，默认 7，最多 90。含今天。"),
      tz: tzField,
    },
    annotations: readOnly,
    call: stats,
  },
] as const;
