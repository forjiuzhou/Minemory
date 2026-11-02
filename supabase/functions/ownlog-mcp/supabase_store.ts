// PostgREST + Storage adapter. Uses only SUPABASE_URL and the service role key.

import { safeRelativePath } from "./pure.ts";
import {
  EVENT_SELECT,
  type AccessLog,
  type AgentToken,
  type EventPatch,
  type EventQuery,
  type EventRow,
  type SearchQuery,
  type Store,
} from "./store.ts";

type TokenRow = {
  id: string;
  name: string;
  user_id: string;
  scopes: string[] | null;
  can_write: boolean | null;
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
      select: "id,name,user_id,scopes,can_write",
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
}
