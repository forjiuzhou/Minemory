// Pure helpers shared by the edge function and the VPS server.
// No Deno, npm, or network imports, so the tests can load this file alone.

export const DEFAULT_TZ = "Asia/Singapore";
export const LIST_CONTENT_CHARS = 800;
export const DETAIL_CONTENT_CHARS = 20_000;
export const PLACE_MERGE_METERS = 50;

export const INBOX_TYPES = ["share", "note", "photo", "voice", "screen_ocr"] as const;

const TYPE_NAME = /^[a-z][a-z0-9_]{0,40}$/;

export function parseBearer(header: string | null | undefined): string | null {
  if (!header) return null;
  const match = /^Bearer\s+(\S+)$/i.exec(header.trim());
  if (!match) return null;
  const token = match[1];
  if (!/^olk_[0-9A-Za-z]{40}$/.test(token)) return null;
  return token;
}

export function capLimit(value: unknown, fallback: number, max: number): number {
  const number = typeof value === "number" ? value : typeof value === "string" ? Number(value) : NaN;
  if (!Number.isFinite(number)) return fallback;
  return Math.min(max, Math.max(1, Math.floor(number)));
}

export function resolveTypes(
  scopes: string[],
  requested?: string[] | null,
): { types: string[] | null; error?: string } {
  const all = scopes.includes("*");
  const allowed = scopes.filter((scope) => scope !== "*");
  if (requested && requested.length > 0) {
    const bad = requested.filter((type) => !TYPE_NAME.test(type));
    if (bad.length > 0) return { types: null, error: `未知事件类型: ${bad.join(", ")}` };
    if (!all) {
      const denied = requested.filter((type) => !allowed.includes(type));
      if (denied.length > 0) {
        return { types: null, error: `令牌不能读取这些类型: ${denied.join(", ")}` };
      }
    }
    return { types: [...requested] };
  }
  if (all) return { types: null };
  return { types: [...allowed] };
}

export function typeAllowed(scopes: string[], type: string): boolean {
  return scopes.includes("*") || scopes.includes(type);
}

export function clipText(
  value: string | null | undefined,
  limit: number,
): { text: string | null; content_truncated: boolean } {
  if (value == null) return { text: null, content_truncated: false };
  if (value.length <= limit) return { text: value, content_truncated: false };
  return { text: value.slice(0, limit), content_truncated: true };
}

export function normalizeText(value: string | null | undefined): string {
  return (value ?? "").replace(/\s+/g, " ").trim();
}

export function toIso(value: string | null | undefined): string | null {
  if (!value) return null;
  const parsed = Date.parse(value);
  if (!Number.isFinite(parsed)) return value;
  return new Date(parsed).toISOString();
}

export function asRecord(value: unknown): Record<string, unknown> {
  if (value && typeof value === "object" && !Array.isArray(value)) {
    return value as Record<string, unknown>;
  }
  if (typeof value === "string") {
    try {
      const parsed = JSON.parse(value);
      if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) return parsed;
    } catch {
      return {};
    }
  }
  return {};
}

export function readNumber(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string" && value.trim() !== "") {
    const number = Number(value);
    if (Number.isFinite(number)) return number;
  }
  return null;
}

export function safeRelativePath(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const path = value.trim().replace(/^\/+/, "");
  if (!path || path.includes("\\") || path.includes("\0")) return null;
  const parts = path.split("/");
  if (parts.some((part) => part === "" || part === "." || part === "..")) return null;
  return parts.join("/");
}

export function offsetMinutes(timeZone: string, instant: Date): number {
  let formatter: Intl.DateTimeFormat;
  try {
    formatter = new Intl.DateTimeFormat("en-US", {
      timeZone,
      hourCycle: "h23",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    });
  } catch {
    throw new Error(`未知时区: ${timeZone}`);
  }
  const parts = Object.fromEntries(
    formatter.formatToParts(instant).map((part) => [part.type, part.value]),
  );
  const asUtc = Date.UTC(
    Number(parts.year),
    Number(parts.month) - 1,
    Number(parts.day),
    Number(parts.hour),
    Number(parts.minute),
    Number(parts.second),
  );
  return Math.round((asUtc - instant.getTime()) / 60_000);
}

function fixedOffsetMinutes(timeZone: string): number | null {
  const match = /^(?:UTC|GMT)?([+-])(\d{1,2})(?::?(\d{2}))?$/i.exec(timeZone.trim());
  if (!match) return null;
  const sign = match[1] === "-" ? -1 : 1;
  const hours = Number(match[2]);
  const minutes = Number(match[3] ?? "0");
  if (hours > 14 || minutes > 59) return null;
  return sign * (hours * 60 + minutes);
}

export function zoneOffsetMinutes(timeZone: string, instant: Date): number {
  const fixed = fixedOffsetMinutes(timeZone);
  if (fixed != null) return fixed;
  if (timeZone === "Z" || timeZone.toUpperCase() === "UTC" || timeZone.toUpperCase() === "GMT") {
    return 0;
  }
  return offsetMinutes(timeZone, instant);
}

function nextDate(date: string): string {
  const [year, month, day] = date.split("-").map(Number);
  const utc = new Date(Date.UTC(year, month - 1, day));
  utc.setUTCDate(utc.getUTCDate() + 1);
  return utc.toISOString().slice(0, 10);
}

export function zonedStart(date: string, timeZone: string): string {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) throw new Error("日期需要 YYYY-MM-DD");
  let utc = Date.parse(`${date}T00:00:00Z`);
  for (let i = 0; i < 3; i++) {
    const offset = zoneOffsetMinutes(timeZone, new Date(utc));
    utc = Date.parse(`${date}T00:00:00Z`) - offset * 60_000;
  }
  return new Date(utc).toISOString();
}

export function zonedDayRange(date: string, timeZone: string): { from: string; to: string } {
  return { from: zonedStart(date, timeZone), to: zonedStart(nextDate(date), timeZone) };
}

export function localDay(iso: string, timeZone: string): string {
  const instant = new Date(iso);
  const formatter = new Intl.DateTimeFormat("en-US", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  });
  const parts = Object.fromEntries(
    formatter.formatToParts(instant).map((part) => [part.type, part.value]),
  );
  return `${parts.year}-${parts.month}-${parts.day}`;
}

export function todayRange(now: Date, timeZone: string): { from: string; to: string } {
  return zonedDayRange(localDay(now.toISOString(), timeZone), timeZone);
}

export function parseRangeBound(
  value: string,
  timeZone: string,
  bound: "from" | "to",
): string {
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    const range = zonedDayRange(value, timeZone);
    return bound === "from" ? range.from : range.to;
  }
  const parsed = Date.parse(value);
  if (!Number.isFinite(parsed)) throw new Error(`无法解析时间: ${value}`);
  return new Date(parsed).toISOString();
}

export type TimelineItem = {
  type: string;
  package_name?: string | null;
  content?: string | null;
};

export function collapseConsecutiveUiText<T extends TimelineItem>(
  rows: T[],
): Array<T & { collapsed_count: number }> {
  const collapsed: Array<T & { collapsed_count: number }> = [];
  for (const row of rows) {
    const previous = collapsed[collapsed.length - 1];
    if (
      previous &&
      previous.type === "ui_text" &&
      row.type === "ui_text" &&
      (previous.package_name ?? "") === (row.package_name ?? "") &&
      normalizeText(previous.content) === normalizeText(row.content)
    ) {
      previous.collapsed_count += 1;
      continue;
    }
    collapsed.push({ ...row, collapsed_count: 1 });
  }
  return collapsed;
}

export type PlacePoint = {
  lat: number;
  lng: number;
  at: string;
  accuracy_m?: number | null;
};

export type PlaceCluster = {
  lat: number;
  lng: number;
  first_seen: string;
  last_seen: string;
  points: number;
  accuracy_m: number | null;
};

export function haversineMeters(lat1: number, lng1: number, lat2: number, lng2: number): number {
  const earth = 6_371_000;
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLng = ((lng2 - lng1) * Math.PI) / 180;
  const a = Math.sin(dLat / 2) ** 2 +
    Math.cos((lat1 * Math.PI) / 180) * Math.cos((lat2 * Math.PI) / 180) * Math.sin(dLng / 2) ** 2;
  return 2 * earth * Math.asin(Math.min(1, Math.sqrt(a)));
}

export function simplifyPlaces(points: PlacePoint[], minMeters = PLACE_MERGE_METERS): PlaceCluster[] {
  const sorted = points
    .filter((point) => Number.isFinite(point.lat) && Number.isFinite(point.lng))
    .sort((a, b) => Date.parse(a.at) - Date.parse(b.at));
  const clusters: PlaceCluster[] = [];
  for (const point of sorted) {
    const last = clusters[clusters.length - 1];
    if (last && haversineMeters(last.lat, last.lng, point.lat, point.lng) < minMeters) {
      if (point.at < last.first_seen) last.first_seen = point.at;
      if (point.at > last.last_seen) last.last_seen = point.at;
      last.points += 1;
      continue;
    }
    clusters.push({
      lat: point.lat,
      lng: point.lng,
      first_seen: point.at,
      last_seen: point.at,
      points: 1,
      accuracy_m: point.accuracy_m ?? null,
    });
  }
  return clusters;
}

export type UsageEvent = {
  type: string;
  package_name?: string | null;
  app_label?: string | null;
  occurred_at: string;
  extras?: unknown;
};

export type AppUsage = {
  package_name: string;
  app_label: string | null;
  duration_ms: number;
  sessions: number;
};

function sessionIdOf(event: UsageEvent): string | null {
  const id = asRecord(event.extras).session_id;
  return typeof id === "string" && id.length > 0 ? id : null;
}

function durationOf(event: UsageEvent): number | null {
  const raw = readNumber(asRecord(event.extras).duration_ms);
  if (raw == null || raw < 0) return null;
  return raw;
}

export function aggregateUsage(events: UsageEvent[]): AppUsage[] {
  const sorted = [...events].sort((a, b) => Date.parse(a.occurred_at) - Date.parse(b.occurred_at));
  const byPackage = new Map<string, {
    label: string | null;
    duration: number;
    sessions: number;
    open: { at: string; session: string | null } | null;
  }>();

  const ensure = (packageName: string, label?: string | null) => {
    let row = byPackage.get(packageName);
    if (!row) {
      row = { label: label ?? null, duration: 0, sessions: 0, open: null };
      byPackage.set(packageName, row);
    } else if (label && !row.label) {
      row.label = label;
    }
    return row;
  };

  for (const event of sorted) {
    const packageName = event.package_name;
    if (!packageName) continue;
    if (event.type === "app_fg") {
      ensure(packageName, event.app_label).open = {
        at: event.occurred_at,
        session: sessionIdOf(event),
      };
      continue;
    }
    if (event.type !== "app_bg") continue;
    const row = ensure(packageName, event.app_label);
    row.sessions += 1;
    const duration = durationOf(event);
    if (duration != null) {
      row.duration += duration;
      row.open = null;
      continue;
    }
    const backgroundSession = sessionIdOf(event);
    const open = row.open;
    if (open && (!open.session || !backgroundSession || open.session === backgroundSession)) {
      const elapsed = Date.parse(event.occurred_at) - Date.parse(open.at);
      if (Number.isFinite(elapsed) && elapsed > 0) row.duration += elapsed;
    }
    row.open = null;
  }

  return [...byPackage.entries()]
    .map(([packageName, row]) => ({
      package_name: packageName,
      app_label: row.label,
      duration_ms: Math.round(row.duration),
      sessions: row.sessions,
    }))
    .sort((a, b) => b.duration_ms - a.duration_ms || a.package_name.localeCompare(b.package_name));
}

export function formatDuration(ms: number): string {
  const total = Math.max(0, Math.round(ms / 1000));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  if (hours > 0) return `${hours}小时${minutes}分`;
  if (minutes > 0) return `${minutes}分${seconds}秒`;
  return `${seconds}秒`;
}

export function shrinkArgs(value: unknown, max = 4000): unknown {
  let text: string;
  try {
    text = JSON.stringify(value) ?? "null";
  } catch {
    return { _truncated: true };
  }
  if (text.length <= max) return value;
  return { _truncated: true, preview: text.slice(0, max) };
}
