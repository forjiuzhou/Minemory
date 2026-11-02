// Supabase Edge Function entry. Function name: ownlog-mcp.
// Deploy with verify_jwt = false. The Bearer token is an olk_ agent token.

import { createHandler } from "./http.ts";
import { SupabaseStore } from "./supabase_store.ts";
import type { Store } from "./store.ts";

let handler: ((req: Request) => Promise<Response>) | undefined;

function storeFromEnv(): Store {
  const url = Deno.env.get("SUPABASE_URL");
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !key) throw new Error("缺少 SUPABASE_URL 或 SUPABASE_SERVICE_ROLE_KEY");
  return new SupabaseStore(url, key);
}

Deno.serve((req) => {
  try {
    handler ??= createHandler(storeFromEnv());
  } catch (error) {
    const message = error instanceof Error ? error.message : "配置错误";
    return new Response(JSON.stringify({
      jsonrpc: "2.0",
      error: { code: -32603, message },
      id: null,
    }), { status: 500, headers: { "content-type": "application/json" } });
  }
  return handler(req);
});
