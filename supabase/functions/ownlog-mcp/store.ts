// Data access for the MCP tools. SupabaseStore (edge) and PostgresStore (VPS)
// both implement this. Tool code does not know which one it has.

export type AgentToken = {
  id: string;
  name: string;
  user_id: string;
  scopes: string[];
  can_write: boolean;
  can_control: boolean;
};

export type EventRow = {
  id?: string;
  occurred_at: string;
  type: string;
  package_name?: string | null;
  app_label?: string | null;
  title?: string | null;
  content?: string | null;
  extras?: Record<string, unknown> | null;
  tags?: string[] | null;
  summary?: string | null;
  organized_at?: string | null;
  device_id?: string | null;
  created_at?: string | null;
  user_id?: string;
};

export type EventColumns = "full" | "stats" | "usage" | "places";

export type EventQuery = {
  userId: string;
  types: string[] | null;
  from: string | null;
  to: string | null;
  unorganizedOnly: boolean;
  order: "asc" | "desc";
  limit: number;
  columns: EventColumns;
};

export type SearchQuery = {
  userId: string;
  query: string;
  types: string[] | null;
  from: string | null;
  to: string | null;
  packageName: string | null;
  limit: number;
};

export type EventPatch = {
  tags?: string[];
  summary?: string;
  organized_at?: string;
};

export type AccessLog = {
  tokenId: string;
  tool: string;
  args: unknown;
  resultCount: number | null;
};

export type PhoneCommandRow = {
  id: string;
  user_id: string;
  device_id: string | null;
  action: string;
  args: Record<string, unknown>;
  status: string;
  result: Record<string, unknown> | null;
  created_at: string;
  started_at: string | null;
  finished_at: string | null;
  expires_at: string;
};

export type PhonePresence = {
  user_id: string;
  device_id: string | null;
  a11y_connected: boolean;
  remote_enabled: boolean;
  foreground_package: string | null;
  last_seen: string;
};

export type PhoneEnqueue = {
  userId: string;
  tokenId: string;
  action: string;
  args: Record<string, unknown>;
  expiresAt: string;
};

export interface Store {
  findToken(tokenHash: string): Promise<AgentToken | null>;
  touchToken(id: string, atIso: string): Promise<void>;
  logAccess(entry: AccessLog): Promise<void>;
  searchEvents(query: SearchQuery): Promise<EventRow[]>;
  listEvents(query: EventQuery): Promise<EventRow[]>;
  getEvent(userId: string, id: string): Promise<EventRow | null>;
  updateEvent(userId: string, id: string, patch: EventPatch): Promise<EventRow | null>;
  signFile(path: string, expiresInSeconds: number): Promise<string | null>;
  enqueuePhoneCommand(input: PhoneEnqueue): Promise<PhoneCommandRow>;
  getPhoneCommand(userId: string, id: string): Promise<PhoneCommandRow | null>;
  settlePhoneCommand(
    userId: string,
    id: string,
    fromStatus: string,
    toStatus: "cancelled" | "failed",
    result: Record<string, unknown>,
    atIso: string,
  ): Promise<PhoneCommandRow | null>;
  phoneStatus(userId: string, nowIso: string): Promise<{ presence: PhonePresence | null; pending: number }>;
}

export const EVENT_SELECT: Record<EventColumns, string> = {
  full: "id,occurred_at,type,package_name,app_label,title,content,extras,tags,summary,organized_at,device_id,created_at,user_id",
  stats: "type,occurred_at",
  usage: "type,package_name,app_label,occurred_at,extras",
  places: "occurred_at,extras",
};
