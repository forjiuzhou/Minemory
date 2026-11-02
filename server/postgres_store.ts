// Postgres adapter for the VPS process. The edge function does not import this.

import postgres from "npm:postgres@3.4.7";

import { safeRelativePath } from "../supabase/functions/ownlog-mcp/pure.ts";
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
} from "../supabase/functions/ownlog-mcp/store.ts";

export type Sql = ReturnType<typeof postgres>;

export function openDatabase(url: string): Sql {
  const pooled = url.includes(":6543") || url.includes("pgbouncer=true") || url.includes("pooler.supabase.com");
  return postgres(url, {
    max: 4,
    idle_timeout: 20,
    connect_timeout: 10,
    prepare: !pooled,
  });
}

export type FileSigner = {
  sign(path: string, expiresInSeconds: number): Promise<string | null>;
};

export class PostgresStore implements Store {
  constructor(
    private readonly sql: Sql,
    private readonly files: FileSigner,
  ) {}

  async findToken(tokenHash: string): Promise<AgentToken | null> {
    const rows = await this.sql<TokenRow[]>`
      select id, name, user_id, scopes, can_write, can_control
      from public.agent_tokens
      where token_hash = ${tokenHash}
        and revoked_at is null
      limit 1
    `;
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
    await this.sql`
      update public.agent_tokens
      set last_used_at = ${atIso}::timestamptz
      where id = ${id}::uuid
    `;
  }

  async logAccess(entry: AccessLog): Promise<void> {
    await this.sql`
      insert into public.agent_access_log (token_id, tool, args, result_count)
      values (
        ${entry.tokenId}::uuid,
        ${entry.tool},
        ${this.sql.json((entry.args ?? {}) as postgres.JSONValue)},
        ${entry.resultCount}
      )
    `;
  }

  async searchEvents(query: SearchQuery): Promise<EventRow[]> {
    const rows = await this.sql<Record<string, unknown>[]>`
      select *
      from public.search_events(
        ${query.userId}::uuid,
        ${query.query},
        ${query.types}::text[],
        ${query.from}::timestamptz,
        ${query.to}::timestamptz,
        ${query.packageName},
        ${query.limit}
      )
    `;
    return rows.map(normalizeRow);
  }

  async listEvents(query: EventQuery): Promise<EventRow[]> {
    if (query.types && query.types.length === 0) return [];
    const where = ["user_id = $1"];
    const params: unknown[] = [query.userId];
    if (query.types && query.types.length > 0) {
      params.push(query.types);
      where.push(`type = any($${params.length}::text[])`);
    }
    if (query.from) {
      params.push(query.from);
      where.push(`occurred_at >= $${params.length}::timestamptz`);
    }
    if (query.to) {
      params.push(query.to);
      where.push(`occurred_at < $${params.length}::timestamptz`);
    }
    if (query.unorganizedOnly) where.push("organized_at is null");
    params.push(query.limit);
    const order = query.order === "asc" ? "asc" : "desc";
    const statement = `
      select ${EVENT_SELECT[query.columns]}
      from public.events
      where ${where.join(" and ")}
      order by occurred_at ${order}
      limit $${params.length}
    `;
    const rows = await this.sql.unsafe(statement, params as never[]);
    return (rows as Record<string, unknown>[]).map(normalizeRow);
  }

  async getEvent(userId: string, id: string): Promise<EventRow | null> {
    const rows = await this.sql<Record<string, unknown>[]>`
      select id, occurred_at, type, package_name, app_label, title, content, extras,
             tags, summary, organized_at, device_id, created_at, user_id
      from public.events
      where id = ${id}::uuid
        and user_id = ${userId}::uuid
      limit 1
    `;
    return rows[0] ? normalizeRow(rows[0]) : null;
  }

  async updateEvent(userId: string, id: string, patch: EventPatch): Promise<EventRow | null> {
    const rows = await this.sql<Record<string, unknown>[]>`
      update public.events
      set
        tags = case when ${patch.tags !== undefined} then ${patch.tags ?? null}::text[] else tags end,
        summary = case when ${patch.summary !== undefined} then ${patch.summary ?? null} else summary end,
        organized_at = case
          when ${patch.organized_at !== undefined} then ${patch.organized_at ?? null}::timestamptz
          else organized_at
        end
      where id = ${id}::uuid
        and user_id = ${userId}::uuid
      returning id, occurred_at, type, package_name, app_label, title, content, extras,
                tags, summary, organized_at, device_id, created_at, user_id
    `;
    return rows[0] ? normalizeRow(rows[0]) : null;
  }

  async signFile(path: string, expiresInSeconds: number): Promise<string | null> {
    if (!safeRelativePath(path)) return null;
    return this.files.sign(path, expiresInSeconds);
  }

  async enqueuePhoneCommand(input: PhoneEnqueue): Promise<PhoneCommandRow> {
    const rows = await this.sql<Record<string, unknown>[]>`
      insert into public.phone_commands (user_id, action, args, status, created_by_token_id, expires_at)
      values (
        ${input.userId}::uuid,
        ${input.action},
        ${this.sql.json(input.args as postgres.JSONValue)},
        'pending',
        ${input.tokenId}::uuid,
        ${input.expiresAt}::timestamptz
      )
      returning id, user_id, device_id, action, args, status, result, created_at, started_at, finished_at, expires_at
    `;
    const row = rows[0];
    if (!row) throw new Error("phone command was not created");
    return phoneRow(row);
  }

  async getPhoneCommand(userId: string, id: string): Promise<PhoneCommandRow | null> {
    const rows = await this.sql<Record<string, unknown>[]>`
      select id, user_id, device_id, action, args, status, result, created_at, started_at, finished_at, expires_at
      from public.phone_commands
      where id = ${id}::uuid
        and user_id = ${userId}::uuid
      limit 1
    `;
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
    const rows = await this.sql<Record<string, unknown>[]>`
      update public.phone_commands
      set status = ${toStatus},
          result = ${this.sql.json(result as postgres.JSONValue)},
          finished_at = ${atIso}::timestamptz
      where id = ${id}::uuid
        and user_id = ${userId}::uuid
        and status = ${fromStatus}
      returning id, user_id, device_id, action, args, status, result, created_at, started_at, finished_at, expires_at
    `;
    return rows[0] ? phoneRow(rows[0]) : null;
  }

  async phoneStatus(userId: string, nowIso: string): Promise<{ presence: PhonePresence | null; pending: number }> {
    const presence = await this.sql<Record<string, unknown>[]>`
      select user_id, device_id, a11y_connected, remote_enabled, foreground_package, last_seen
      from public.phone_presence
      where user_id = ${userId}::uuid
      limit 1
    `;
    const pending = await this.sql<{ count: number }[]>`
      select count(*)::int as count
      from public.phone_commands
      where user_id = ${userId}::uuid
        and status = 'pending'
        and expires_at > ${nowIso}::timestamptz
    `;
    return {
      presence: presence[0] ? presenceRow(presence[0]) : null,
      pending: pending[0]?.count ?? 0,
    };
  }
}

type TokenRow = {
  id: string;
  name: string;
  user_id: string;
  scopes: string[] | null;
  can_write: boolean | null;
  can_control: boolean | null;
};

function iso(value: unknown): string | null {
  if (value == null) return null;
  if (value instanceof Date) return value.toISOString();
  const parsed = Date.parse(String(value));
  if (!Number.isFinite(parsed)) return String(value);
  return new Date(parsed).toISOString();
}

function normalizeRow(row: Record<string, unknown>): EventRow {
  const extras = row.extras;
  return {
    id: row.id == null ? undefined : String(row.id),
    occurred_at: iso(row.occurred_at) ?? "",
    type: String(row.type ?? ""),
    package_name: (row.package_name as string | null | undefined) ?? null,
    app_label: (row.app_label as string | null | undefined) ?? null,
    title: (row.title as string | null | undefined) ?? null,
    content: (row.content as string | null | undefined) ?? null,
    extras: extras && typeof extras === "object" && !Array.isArray(extras)
      ? extras as Record<string, unknown>
      : null,
    tags: Array.isArray(row.tags) ? row.tags.map(String) : null,
    summary: (row.summary as string | null | undefined) ?? null,
    organized_at: iso(row.organized_at),
    device_id: row.device_id == null ? null : String(row.device_id),
    created_at: iso(row.created_at),
    user_id: row.user_id == null ? undefined : String(row.user_id),
  };
}

function asObject(value: unknown): Record<string, unknown> | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  return value as Record<string, unknown>;
}

function phoneRow(row: Record<string, unknown>): PhoneCommandRow {
  return {
    id: String(row.id),
    user_id: String(row.user_id),
    device_id: row.device_id == null ? null : String(row.device_id),
    action: String(row.action ?? ""),
    args: asObject(row.args) ?? {},
    status: String(row.status ?? ""),
    result: asObject(row.result),
    created_at: iso(row.created_at) ?? "",
    started_at: iso(row.started_at),
    finished_at: iso(row.finished_at),
    expires_at: iso(row.expires_at) ?? "",
  };
}

function presenceRow(row: Record<string, unknown>): PhonePresence {
  return {
    user_id: String(row.user_id),
    device_id: row.device_id == null ? null : String(row.device_id),
    a11y_connected: row.a11y_connected === true,
    remote_enabled: row.remote_enabled === true,
    foreground_package: row.foreground_package == null ? null : String(row.foreground_package),
    last_seen: iso(row.last_seen) ?? "",
  };
}
