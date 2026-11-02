// Streamable HTTP MCP endpoint. Stateless JSON responses.
// The caller supplies a Store; this file does not read environment variables.

import { McpServer } from "npm:@modelcontextprotocol/sdk@1.30.0/server/mcp.js";
import { WebStandardStreamableHTTPServerTransport } from "npm:@modelcontextprotocol/sdk@1.30.0/server/webStandardStreamableHttp.js";

import { parseBearer, sha256Hex } from "./auth.ts";
import { redactLoggedArgs } from "./phone.ts";
import { shrinkArgs } from "./pure.ts";
import type { AgentToken, Store } from "./store.ts";
import { TOOLS } from "./tools.ts";

const CORS: Record<string, string> = {
  "access-control-allow-origin": "*",
  "access-control-allow-headers":
    "authorization, content-type, accept, mcp-protocol-version, mcp-session-id, last-event-id",
  "access-control-allow-methods": "POST, OPTIONS, GET, DELETE",
  "access-control-expose-headers": "mcp-protocol-version, mcp-session-id",
};

export type FileAccess = {
  handle(req: Request, url: URL): Promise<Response | null>;
};

export type HandlerOptions = {
  now?: () => Date;
  fileAccess?: FileAccess;
};

export function createHandler(store: Store, options: HandlerOptions = {}) {
  const now = options.now ?? (() => new Date());
  return async (req: Request): Promise<Response> => {
    const url = new URL(req.url);
    if (req.method === "OPTIONS") return withCors(new Response(null, { status: 204 }));
    if (req.method === "GET" || req.method === "HEAD") {
      if (options.fileAccess && req.method === "GET") {
        const file = await options.fileAccess.handle(req, url);
        if (file) return withCors(file);
      }
      return withCors(methodNotAllowed());
    }
    if (req.method === "DELETE") return withCors(methodNotAllowed());
    if (req.method !== "POST") return withCors(methodNotAllowed());

    const tokenText = parseBearer(req.headers.get("authorization"));
    if (!tokenText) return withCors(unauthorized());
    let token;
    try {
      token = await store.findToken(await sha256Hex(tokenText));
    } catch (error) {
      console.error("findToken failed", error instanceof Error ? error.message : error);
      return withCors(jsonRpc(500, -32603, "鉴权存储不可用"));
    }
    if (!token) return withCors(unauthorized());

    const at = now().toISOString();
    store.touchToken(token.id, at).catch((error) => {
      console.error("touchToken failed", error instanceof Error ? error.message : error);
    });

    const response = await dispatchMcp(withJsonAccept(req), store, token, now);
    return withCors(response);
  };
}

async function dispatchMcp(
  req: Request,
  store: Store,
  token: AgentToken,
  now: () => Date,
): Promise<Response> {
  const server = new McpServer({ name: "ownlog", version: "1.0.0" });
  for (const tool of TOOLS) {
    server.registerTool(tool.name, {
      title: tool.title,
      description: tool.description,
      inputSchema: tool.inputSchema,
      annotations: tool.annotations,
    }, async (args: Record<string, unknown>) => {
      const loggedArgs = redactLoggedArgs(tool.name, shrinkArgs(args));
      try {
        const result = await tool.call(args, { token, store, now: now() });
        await store.logAccess({
          tokenId: token.id,
          tool: tool.name,
          args: loggedArgs,
          resultCount: result.count,
        }).catch((error) => console.error("logAccess failed", error));
        return {
          content: [{ type: "text" as const, text: result.text }],
          isError: result.isError === true,
        };
      } catch (error) {
        const message = error instanceof Error ? error.message : "工具调用失败";
        await store.logAccess({
          tokenId: token.id,
          tool: tool.name,
          args: loggedArgs,
          resultCount: null,
        }).catch(() => {});
        return {
          content: [{ type: "text" as const, text: JSON.stringify({ error: message }, null, 2) }],
          isError: true,
        };
      }
    });
  }

  const transport = new WebStandardStreamableHTTPServerTransport({
    sessionIdGenerator: undefined,
    enableJsonResponse: true,
  });
  await server.connect(transport);
  try {
    return await transport.handleRequest(req);
  } finally {
    await server.close();
  }
}

function withJsonAccept(req: Request): Request {
  const accept = req.headers.get("accept") ?? "";
  if (accept.includes("application/json") && accept.includes("text/event-stream")) return req;
  const headers = new Headers(req.headers);
  headers.set("accept", "application/json, text/event-stream");
  return new Request(req, { headers });
}

function withCors(response: Response): Response {
  const headers = new Headers(response.headers);
  for (const [key, value] of Object.entries(CORS)) headers.set(key, value);
  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers,
  });
}

function unauthorized(): Response {
  return jsonRpc(401, -32001, "Unauthorized");
}

function methodNotAllowed(): Response {
  return new Response(JSON.stringify({
    jsonrpc: "2.0",
    error: { code: -32000, message: "Method not allowed" },
    id: null,
  }), {
    status: 405,
    headers: {
      allow: "POST, OPTIONS",
      "content-type": "application/json",
    },
  });
}

function jsonRpc(status: number, code: number, message: string): Response {
  return new Response(JSON.stringify({
    jsonrpc: "2.0",
    error: { code, message },
    id: null,
  }), {
    status,
    headers: { "content-type": "application/json" },
  });
}
