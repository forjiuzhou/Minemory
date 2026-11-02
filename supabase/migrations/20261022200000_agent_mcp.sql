-- Agent tokens, access log, and trigram search for the OwnLog MCP server.
-- Run after 20261022180000_owner_auth.sql.
-- The phone never reads these tables. The edge function uses the service role.
-- A VPS process using the migration owner can use the same functions.

create schema if not exists extensions;

create extension if not exists pgcrypto with schema extensions;
create extension if not exists pg_trgm with schema extensions;

-- pg_trgm may already live in public (the first migration creates pgcrypto
-- without a schema). Resolve gin_trgm_ops wherever it was installed.
do $$
declare
    v_opclass text;
begin
    select n.nspname || '.gin_trgm_ops'
        into v_opclass
    from pg_opclass c
    join pg_namespace n on n.oid = c.opcnamespace
    join pg_am am on am.oid = c.opcmethod
    where c.opcname = 'gin_trgm_ops'
      and am.amname = 'gin'
    limit 1;

    if v_opclass is null then
        raise exception 'pg_trgm gin_trgm_ops was not installed';
    end if;

    execute format(
        $idx$create index if not exists events_text_trgm_idx
            on public.events
            using gin ((lower(coalesce(title, '') || ' ' || coalesce(content, ''))) %s)$idx$,
        v_opclass
    );
end $$;

comment on index public.events_text_trgm_idx is
    'Trigram index over title and content. Chinese ranking also uses ownlog_char_score because pg_trgm word classes may skip Han characters.';

-- TODO: nullable events.embedding (pgvector) for semantic search. Not added here.
-- See docs/mcp.md.

do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'service_role') then
        create role service_role nologin bypassrls;
    end if;
end $$;

create table if not exists public.agent_tokens (
    id uuid primary key default gen_random_uuid(),
    name text not null,
    token_hash text not null unique,
    user_id uuid not null references auth.users (id),
    scopes text[] not null default '{*}',
    can_write boolean not null default false,
    created_at timestamptz not null default now(),
    last_used_at timestamptz,
    revoked_at timestamptz
);

comment on table public.agent_tokens is
    'Bearer tokens for the MCP server. Only the sha-256 hex of the olk_ token is stored.';
comment on column public.agent_tokens.scopes is
    'Event types this token may read. {*} means every type.';
comment on column public.agent_tokens.can_write is
    'When true, the annotate tool may update tags, summary, and organized_at.';
comment on column public.agent_tokens.token_hash is
    'Lowercase hex sha-256 of the full token, including the olk_ prefix.';

create index if not exists agent_tokens_user_id_idx
    on public.agent_tokens (user_id);

create table if not exists public.agent_access_log (
    id uuid primary key default gen_random_uuid(),
    token_id uuid not null references public.agent_tokens (id),
    tool text not null,
    args jsonb,
    result_count int,
    at timestamptz not null default now()
);

comment on table public.agent_access_log is
    'One row per MCP tools/call. args never include the bearer token.';

create index if not exists agent_access_log_token_at_idx
    on public.agent_access_log (token_id, at desc);

alter table public.agent_tokens enable row level security;
alter table public.agent_access_log enable row level security;

-- No policies. service_role and the table owner bypass RLS. Everyone else
-- matches nothing, and the grants below are empty for anon/authenticated.

revoke all on table public.agent_tokens from public;
revoke all on table public.agent_access_log from public;

do $$
begin
    if exists (select 1 from pg_roles where rolname = 'anon') then
        execute 'revoke all on table public.agent_tokens from anon';
        execute 'revoke all on table public.agent_access_log from anon';
    end if;
    if exists (select 1 from pg_roles where rolname = 'authenticated') then
        execute 'revoke all on table public.agent_tokens from authenticated';
        execute 'revoke all on table public.agent_access_log from authenticated';
    end if;
end $$;

grant usage on schema public to service_role;
grant usage on schema extensions to service_role;
grant select, insert, update on table public.agent_tokens to service_role;
grant select, insert on table public.agent_access_log to service_role;
grant select, update (tags, summary, organized_at) on table public.events to service_role;

create or replace function public.ownlog_char_score(p_text text, p_query text)
returns real
language plpgsql
immutable
as $$
declare
    hay text := lower(coalesce(p_text, ''));
    needle text := lower(btrim(coalesce(p_query, '')));
    n int;
    hits int := 0;
    i int;
begin
    -- Character bigrams, so Chinese still ranks when pg_trgm ignores Han.
    if needle = '' or hay = '' then
        return 0;
    end if;
    if position(needle in hay) > 0 then
        return greatest(0.65, least(1.0, char_length(needle)::real / greatest(char_length(hay), 1)));
    end if;
    n := char_length(needle);
    if n < 2 then
        return 0;
    end if;
    for i in 1..(n - 1) loop
        if position(substr(needle, i, 2) in hay) > 0 then
            hits := hits + 1;
        end if;
    end loop;
    return hits::real / (n - 1);
end;
$$;

comment on function public.ownlog_char_score(text, text) is
    'Character bigram overlap for MCP search. Used because pg_trgm may not index Han characters.';

create or replace function public.search_events(
    p_user uuid,
    p_query text,
    p_types text[] default null,
    p_from timestamptz default null,
    p_to timestamptz default null,
    p_package text default null,
    p_limit int default 20
)
returns setof public.events
language plpgsql
stable
security invoker
set search_path = public, extensions
as $$
declare
    v_limit int := least(greatest(coalesce(p_limit, 20), 1), 50);
    v_query text := nullif(btrim(coalesce(p_query, '')), '');
    v_like text;
    v_package text := nullif(btrim(coalesce(p_package, '')), '');
    v_package_like text;
begin
    if p_user is null or v_query is null then
        return;
    end if;
    v_like := replace(replace(replace(v_query, '\', '\\'), '%', '\%'), '_', '\_');
    v_package_like := replace(replace(replace(coalesce(v_package, ''), '\', '\\'), '%', '\%'), '_', '\_');

    return query
    select e.*
    from public.events e
    where e.user_id = p_user
      and (p_types is null or cardinality(p_types) = 0 or e.type = any (p_types))
      and (p_from is null or e.occurred_at >= p_from)
      and (p_to is null or e.occurred_at < p_to)
      and (
          v_package is null
          or e.package_name = v_package
          or e.app_label ilike '%' || v_package_like || '%' escape '\'
      )
      and greatest(
          similarity(lower(coalesce(e.title, '') || ' ' || coalesce(e.content, '')), lower(v_query)),
          public.ownlog_char_score(coalesce(e.title, '') || ' ' || coalesce(e.content, ''), v_query),
          case
              when lower(coalesce(e.title, '') || ' ' || coalesce(e.content, ''))
                   ilike '%' || v_like || '%' escape '\'
              then 0.55
              else 0
          end
      ) >= 0.2
    order by greatest(
        similarity(lower(coalesce(e.title, '') || ' ' || coalesce(e.content, '')), lower(v_query)),
        public.ownlog_char_score(coalesce(e.title, '') || ' ' || coalesce(e.content, ''), v_query),
        case
            when lower(coalesce(e.title, '') || ' ' || coalesce(e.content, ''))
                 ilike '%' || v_like || '%' escape '\'
            then 0.55
            else 0
        end
    ) desc,
    e.occurred_at desc
    limit v_limit;
end;
$$;

comment on function public.search_events(uuid, text, text[], timestamptz, timestamptz, text, int) is
    'Owner-scoped search. Blends pg_trgm similarity, Chinese character score, and ILIKE.';

create or replace function public.create_agent_token(
    p_name text,
    p_user_id uuid,
    p_scopes text[] default '{*}',
    p_can_write boolean default false
)
returns text
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
    v_alphabet constant text := '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz';
    v_body text := '';
    v_bytes bytea;
    v_byte int;
    i int;
    v_token text;
    v_hash text;
begin
    -- session_user stays the login role inside a security definer function.
    -- PostgREST keeps session_user = authenticator and sets request.jwt.claim.role.
    if session_user not in ('postgres', 'supabase_admin', 'supabase_auth_admin')
       and coalesce(current_setting('request.jwt.claim.role', true), '') <> 'service_role'
    then
        raise exception 'create_agent_token is restricted to service_role';
    end if;

    if p_user_id is null then
        raise exception 'p_user_id is required';
    end if;
    if p_name is null or length(btrim(p_name)) = 0 then
        raise exception 'p_name is required';
    end if;

    <<fill>>
    while length(v_body) < 40 loop
        v_bytes := gen_random_bytes(32);
        for i in 0..31 loop
            v_byte := get_byte(v_bytes, i);
            if v_byte < 248 then
                v_body := v_body || substr(v_alphabet, (v_byte % 62) + 1, 1);
                exit fill when length(v_body) = 40;
            end if;
        end loop;
    end loop;

    v_token := 'olk_' || v_body;
    v_hash := encode(digest(v_token, 'sha256'), 'hex');

    insert into public.agent_tokens (name, token_hash, user_id, scopes, can_write)
    values (
        btrim(p_name),
        v_hash,
        p_user_id,
        coalesce(p_scopes, '{*}'),
        coalesce(p_can_write, false)
    );

    return v_token;
end;
$$;

comment on function public.create_agent_token(text, uuid, text[], boolean) is
    'Mint an olk_ token. Returns the plaintext once. Stores only the sha-256 hex.';

revoke all on function public.ownlog_char_score(text, text) from public;
revoke all on function public.search_events(uuid, text, text[], timestamptz, timestamptz, text, int) from public;
revoke all on function public.create_agent_token(text, uuid, text[], boolean) from public;

do $$
begin
    if exists (select 1 from pg_roles where rolname = 'anon') then
        execute 'revoke all on function public.ownlog_char_score(text, text) from anon';
        execute 'revoke all on function public.search_events(uuid, text, text[], timestamptz, timestamptz, text, int) from anon';
        execute 'revoke all on function public.create_agent_token(text, uuid, text[], boolean) from anon';
    end if;
    if exists (select 1 from pg_roles where rolname = 'authenticated') then
        execute 'revoke all on function public.ownlog_char_score(text, text) from authenticated';
        execute 'revoke all on function public.search_events(uuid, text, text[], timestamptz, timestamptz, text, int) from authenticated';
        execute 'revoke all on function public.create_agent_token(text, uuid, text[], boolean) from authenticated';
    end if;
end $$;

grant execute on function public.ownlog_char_score(text, text) to service_role;
grant execute on function public.search_events(uuid, text, text[], timestamptz, timestamptz, text, int) to service_role;
grant execute on function public.create_agent_token(text, uuid, text[], boolean) to service_role;
