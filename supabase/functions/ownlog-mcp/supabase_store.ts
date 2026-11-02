// PostgREST + Storage adapter. Uses only SUPABASE_URL and the service role key.

import { safeRelativePath } from "./pure.ts";
import {
  EVENT_SELECT,
  type AccessLog,
  type AgentToken,
  type EventPatch,
  type EventQuery,
  type EventRow,
  type PhoneCommandRow,
  type PhoneEnqueue,
  type PhonePresence,
  type SearchQuery,
  type Store,
} from "./store.ts";

type TokenRow = {
  id: string;
  name: string;
  user_id: string;
  scopes: string[] | null;
  can_write: boolean | null;
  can_control: boolean | null;
};

export class SupabaseStore implements Store {
  readonly base: string;
  readonly key: string;

  constructor(url: string, serviceRoleKey: string) {
    this.base = url.replace(/\/+$/, "");
    this.key = serviceRoleKey;
  }

  private headers(extra?: Record<string, string>): Headers {
    return new Headers({
      apikey: this.key,
      authorization: `Bearer ${this.key}`,
      accept: "application/json",
      "content-type": "application/json",
      ...extra,
    });
  }

  private async send(path: string, init: RequestInit): Promise<Response> {
    const response = await fetch(`${this.base}${path}`, init);
    if (!response.ok) {
      const detail = (await response.text()).slice(0, 240);
      throw new Error(`Supabase ${response.status}: ${detail}`);
    }
    return response;
  }

  async findToken(tokenHash: string): Promise<AgentToken | null> {
    const query = new URLSearchParams({
      select: "id,name,user_id,scopes,can_write,can_control",
      token_hash: `eq.${tokenHash}`,
      revoked_at: "is.null",
      limit: "1",
    });
    const response = await this.send(`/rest/v1/agent_tokens?${query}`, {
      headers: this.headers(),
    });
    const rows = await response.json() as TokenRow[];
    const row = rows[0];
    if (!row) return null;
    return {
      id: row.id,
      name: row.name,
      user_id: row.user_id,
      scopes: row.scopes ?? [],
      can_write: row.can_write === true,
      can_control: row.can_control === true,
    };
  }

  async touchToken(id: string, atIso: string): Promise<void> {
    await this.send(`/rest/v1/agent_tokens?id=eq.${encodeURIComponent(id)}`, {
      method: "PATCH",
      headers: this.headers({ prefer: "return=minimal" }),
      body: JSON.stringify({ last_used_at: atIso }),
    });
  }

  async logAccess(entry: AccessLog): Promise<void> {
    await this.send("/rest/v1/agent_access_log", {
      method: "POST",
      headers: this.headers({ prefer: "return=minimal" }),
      body: JSON.stringify({
        token_id: entry.tokenId,
        tool: entry.tool,
        args: entry.args ?? {},
        result_count: entry.resultCount,
      }),
    });
  }

  async searchEvents(query: SearchQuery): Promise<EventRow[]> {
    const response = await this.send("/rest/v1/rpc/search_events", {
      method: "POST",
      headers: this.headers(),
      body: JSON.stringify({
        p_user: query.userId,
        p_query: query.query,
        p_types: query.types,
        p_from: query.from,
        p_to: query.to,
        p_package: query.packageName,
        p_limit: query.limit,
      }),
    });
    const rows = await response.json();
    return Array.isArray(rows) ? rows as EventRow[] : [];
  }

  async listEvents(query: EventQuery): Promise<EventRow[]> {
    if (query.types && query.types.length === 0) return [];
    const parts = [
      `select=${EVENT_SELECT[query.columns]}`,
      `user_id=eq.${encodeURIComponent(query.userId)}`,
      `order=occurred_at.${query.order}`,
      `limit=${query.limit}`,
    ];
    if (query.types && query.types.length > 0) {
      parts.push(`type=in.(${query.types.join(",")})`);
    }
    if (query.from) parts.push(`occurred_at=gte.${encodeURIComponent(query.from)}`);
    if (query.to) parts.push(`occurred_at=lt.${encodeURIComponent(query.to)}`);
    if (query.unorganizedOnly) parts.push("organized_at=is.null");
    const response = await this.send(`/rest/v1/events?${parts.join("&")}`, {
      headers: this.headers(),
    });
    const rows = await response.json();
    return Array.isArray(rows) ? rows as EventRow[] : [];
  }

  async getEvent(userId: string, id: string): Promise<EventRow | null> {
    const query = new URLSearchParams({
      select: EVENT_SELECT.full,
      id: `eq.${id}`,
      user_id: `eq.${userId}`,
      limit: "1",
    });
    const response = await this.send(`/rest/v1/events?${query}`, { headers: this.headers() });
    const rows = await response.json() as EventRow[];
    return rows[0] ?? null;
  }

  async updateEvent(userId: string, id: string, patch: EventPatch): Promise<EventRow | null> {
    const body: Record<string, unknown> = {};
    if (patch.tags) body.tags = patch.tags;
    if (patch.summary !== undefined) body.summary = patch.summary;
    if (patch.organized_at !== undefined) body.organized_at = patch.organized_at;
    const query = new URLSearchParams({
      id: `eq.${id}`,
      user_id: `eq.${userId}`,
      select: EVENT_SELECT.full,
    });
    const response = await this.send(`/rest/v1/events?${query}`, {
      method: "PATCH",
      headers: this.headers({ prefer: "return=representation" }),
      body: JSON.stringify(body),
    });
    const rows = await response.json() as EventRow[];
    return rows[0] ?? null;
  }

  async signFile(path: string, expiresInSeconds: number): Promise<string | null> {
    const relative = safeRelativePath(path);
    if (!relative) return null;
    const encoded = relative.split("/").map((part) => encodeURIComponent(part)).join("/");
    const response = await fetch(`${this.base}/storage/v1/object/sign/inbox/${encoded}`, {
      method: "POST",
      headers: this.headers(),
      body: JSON.stringify({ expiresIn: expiresInSeconds }),
    });
    if (!response.ok) return null;
    const payload = await response.json() as { signedURL?: string; signedUrl?: string };
    const signed = payload.signedURL ?? payload.signedUrl;
    if (!signed) return null;
    if (signed.startsWith("http://") || signed.startsWith("https://")) return signed;
    if (signed.startsWith("/storage/v1")) return `${this.base}${signed}`;
    return `${this.base}/storage/v1${signed}`;
  }

  async enqueuePhoneCommand(input: PhoneEnqueue): Promise<PhoneCommandRow> {
    const response = await this.send("/rest/v1/phone_commands", {
      method: "POST",
      headers: this.headers({ prefer: "return=representation" }),
      body: JSON.stringify({
        user_id: input.userId,
        action: input.action,
        args: input.args,
        status: "pending",
        created_by_token_id: input.tokenId,
        expires_at: input.expiresAt,
      }),
    });
    const rows = await response.json() as Record<string, unknown>[];
    const row = rows[0];
    if (!row) throw new Error("phone command was not created");
    return phoneRow(row);
  }

  async getPhoneCommand(userId: string, id: string): Promise<PhoneCommandRow | null> {
    const query = new URLSearchParams({
      select: PHONE_COLUMNS,
      id: `eq.${id}`,
      user_id: `eq.${userId}`,
      limit: "1",
    });
    const response = await this.send(`/rest/v1/phone_commands?${query}`, { headers: this.headers() });
    const rows = await response.json() as Record<string, unknown>[];
    return rows[0] ? phoneRow(rows[0]) : null;
  }

  async settlePhoneCommand(
    userId: string,
    id: string,
    fromStatus: string,
    toStatus: "cancelled" | "failed",
    result: Record<string, unknown>,
    atIso: string,
  ): Promise<PhoneCommandRow | null> {
    const query = new URLSearchParams({
      id: `eq.${id}`,
      user_id: `eq.${userId}`,
      status: `eq.${fromStatus}`,
      select: PHONE_COLUMNS,
    });
    const response = await this.send(`/rest/v1/phone_commands?${query}`, {
      method: "PATCH",
      headers: this.headers({ prefer: "return=representation" }),
      body: JSON.stringify({ status: toStatus, result, finished_at: atIso }),
    });
    const rows = await response.json() as Record<string, unknown>[];
    return rows[0] ? phoneRow(rows[0]) : null;
  }

  async phoneStatus(userId: string, nowIso: string): Promise<{ presence: PhonePresence | null; pending: number }> {
    const presenceQuery = new URLSearchParams({
      select: "user_id,device_id,a11y_connected,remote_enabled,foreground_package,last_seen",
      user_id: `eq.${userId}`,
      limit: "1",
    });
    const pendingQuery = new URLSearchParams({
      select: "id",
      user_id: `eq.${userId}`,
      status: "eq.pending",
      expires_at: `gt.${nowIso}`,
      limit: "100",
    });
    const [presenceResponse, pendingResponse] = await Promise.all([
      this.send(`/rest/v1/phone_presence?${presenceQuery}`, { headers: this.headers() }),
      this.send(`/rest/v1/phone_commands?${pendingQuery}`, { headers: this.headers() }),
    ]);
    const presenceRows = await presenceResponse.json() as Record<string, unknown>[];
    const pendingRows = await pendingResponse.json() as unknown[];
    return {
      presence: presenceRows[0] ? presenceRow(presenceRows[0]) : null,
      pending: Array.isArray(pendingRows) ? pendingRows.length : 0,
    };
  }
}

const PHONE_COLUMNS = "id,user_id,device_id,action,args,status,result,created_at,started_at,finished_at,expires_at";

function asObject(value: unknown): Record<string, unknown> | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  return value as Record<string, unknown>;
}

function textOrNull(value: unknown): string | null {
  if (value == null) return null;
  return String(value);
}

function phoneRow(row: Record<string, unknown>): PhoneCommandRow {
  return {
    id: String(row.id),
    user_id: String(row.user_id),
    device_id: textOrNull(row.device_id),
    action: String(row.action ?? ""),
    args: asObject(row.args) ?? {},
    status: String(row.status ?? ""),
    result: asObject(row.result),
    created_at: textOrNull(row.created_at) ?? "",
    started_at: textOrNull(row.started_at),
    finished_at: textOrNull(row.finished_at),
    expires_at: textOrNull(row.expires_at) ?? "",
  };
}

function presenceRow(row: Record<string, unknown>): PhonePresence {
  return {
    user_id: String(row.user_id),
    device_id: textOrNull(row.device_id),
    a11y_connected: row.a11y_connected === true,
    remote_enabled: row.remote_enabled === true,
    foreground_package: textOrNull(row.foreground_package),
    last_seen: textOrNull(row.last_seen) ?? "",
  };
}
