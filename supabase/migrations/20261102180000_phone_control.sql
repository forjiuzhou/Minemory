-- Remote phone commands for OwnLog / 自记.
-- Run after 20261022200000_agent_mcp.sql.
-- The edge function (service role) inserts commands. The phone, signed in as the
-- owner, claims and completes them. The service role key never ships in the APK.
-- can_control is separate from can_write. Existing tokens stay unable to control.

alter table public.agent_tokens
    add column if not exists can_control boolean not null default false;

comment on column public.agent_tokens.can_control is
    'When true, MCP phone_* tools may enqueue phone_commands for this user. Independent of can_write.';

create table if not exists public.phone_commands (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users (id) on delete cascade,
    device_id text,
    action text not null,
    args jsonb not null default '{}'::jsonb,
    status text not null default 'pending',
    result jsonb,
    created_by_token_id uuid references public.agent_tokens (id) on delete set null,
    created_at timestamptz not null default now(),
    started_at timestamptz,
    finished_at timestamptz,
    expires_at timestamptz not null default (now() + interval '2 minutes'),
    constraint phone_commands_action_chk check (
        action in ('tap', 'swipe', 'back', 'home', 'recents', 'type', 'open_app', 'dump_ui', 'wait')
    ),
    constraint phone_commands_status_chk check (
        status in ('pending', 'running', 'done', 'failed', 'cancelled')
    )
);

comment on table public.phone_commands is
    'One remote control command. The phone claims pending rows with the owner JWT and writes result. Pending commands expire after 2 minutes.';

comment on column public.phone_commands.result is
    'JSON from the phone: ok, error, message, foreground_package, ui_tree (truncated text), ui_truncated, duration_ms. No screenshots.';

create index if not exists phone_commands_user_status_created_idx
    on public.phone_commands (user_id, status, created_at);

create table if not exists public.phone_presence (
    user_id uuid primary key references auth.users (id) on delete cascade,
    device_id text,
    a11y_connected boolean not null default false,
    remote_enabled boolean not null default false,
    foreground_package text,
    last_seen timestamptz not null default now()
);

comment on table public.phone_presence is
    'Latest heartbeat from the signed-in phone. One row per user for the single-device MVP.';

alter table public.phone_commands enable row level security;
alter table public.phone_presence enable row level security;

revoke all on table public.phone_commands from public;
revoke all on table public.phone_presence from public;

do $$
begin
    if exists (select 1 from pg_roles where rolname = 'anon') then
        execute 'revoke all on table public.phone_commands from anon';
        execute 'revoke all on table public.phone_presence from anon';
    end if;
    if exists (select 1 from pg_roles where rolname = 'authenticated') then
        execute 'revoke all on table public.phone_commands from authenticated';
        execute 'revoke all on table public.phone_presence from authenticated';
    end if;
end $$;

grant select on table public.phone_commands to authenticated;
grant update (status, result, started_at, finished_at, device_id)
    on table public.phone_commands to authenticated;
grant select, insert, update on table public.phone_presence to authenticated;

grant select, insert, update on table public.phone_commands to service_role;
grant select, insert, update, delete on table public.phone_presence to service_role;

drop policy if exists phone_commands_owner_select on public.phone_commands;
drop policy if exists phone_commands_owner_update on public.phone_commands;
create policy phone_commands_owner_select
    on public.phone_commands
    for select
    to authenticated
    using (user_id = (select auth.uid()));

create policy phone_commands_owner_update
    on public.phone_commands
    for update
    to authenticated
    using (user_id = (select auth.uid()))
    with check (user_id = (select auth.uid()));

drop policy if exists phone_presence_owner_select on public.phone_presence;
drop policy if exists phone_presence_owner_insert on public.phone_presence;
drop policy if exists phone_presence_owner_update on public.phone_presence;
create policy phone_presence_owner_select
    on public.phone_presence
    for select
    to authenticated
    using (user_id = (select auth.uid()));

create policy phone_presence_owner_insert
    on public.phone_presence
    for insert
    to authenticated
    with check (user_id = (select auth.uid()));

create policy phone_presence_owner_update
    on public.phone_presence
    for update
    to authenticated
    using (user_id = (select auth.uid()))
    with check (user_id = (select auth.uid()));

-- Authenticated updates may only move a command forward. service_role
-- (the MCP connection) and the migration owner can cancel freely.
-- Triggers still run for service_role; RLS does not skip them.
create or replace function public.phone_commands_status_guard()
returns trigger
language plpgsql
set search_path = public
as $$
begin
    if new.id is distinct from old.id
       or new.user_id is distinct from old.user_id
       or new.action is distinct from old.action
       or new.args is distinct from old.args
       or new.created_by_token_id is distinct from old.created_by_token_id
       or new.created_at is distinct from old.created_at
       or new.expires_at is distinct from old.expires_at
    then
        raise exception 'phone_commands immutable columns';
    end if;

    if current_user in ('service_role', 'postgres', 'supabase_admin', 'supabase_auth_admin')
       or session_user in ('postgres', 'supabase_admin', 'supabase_auth_admin')
    then
        return new;
    end if;

    if old.status = 'pending' and new.status in ('running', 'failed', 'cancelled') then
        return new;
    end if;
    if old.status = 'running' and new.status in ('done', 'failed', 'cancelled') then
        return new;
    end if;

    raise exception 'illegal phone_commands status change from % to %', old.status, new.status;
end;
$$;

drop trigger if exists phone_commands_status_guard on public.phone_commands;
create trigger phone_commands_status_guard
    before update on public.phone_commands
    for each row
    execute function public.phone_commands_status_guard();

revoke all on function public.phone_commands_status_guard() from public;

do $$
begin
    if exists (select 1 from pg_roles where rolname = 'anon') then
        execute 'revoke all on function public.phone_commands_status_guard() from anon';
    end if;
    if exists (select 1 from pg_roles where rolname = 'authenticated') then
        execute 'revoke all on function public.phone_commands_status_guard() from authenticated';
    end if;
end $$;

-- Replace the 4-arg mint function. The new 5th argument defaults to false,
-- so existing create_agent_token(name, user) and (name, user, scopes, can_write)
-- calls keep can_control off.
drop function if exists public.create_agent_token(text, uuid, text[], boolean);

create or replace function public.create_agent_token(
    p_name text,
    p_user_id uuid,
    p_scopes text[] default '{*}',
    p_can_write boolean default false,
    p_can_control boolean default false
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
    if session_user in ('postgres', 'supabase_admin', 'supabase_auth_admin')
       or coalesce(current_setting('request.jwt.claim.role', true), '') = 'service_role'
    then
        null;
    else
        begin
            if coalesce(current_setting('request.jwt.claims', true)::json->>'role', '') <> 'service_role' then
                raise exception 'create_agent_token is restricted to service_role';
            end if;
        exception
            when invalid_text_representation then
                raise exception 'create_agent_token is restricted to service_role';
        end;
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

    insert into public.agent_tokens (name, token_hash, user_id, scopes, can_write, can_control)
    values (
        btrim(p_name),
        v_hash,
        p_user_id,
        coalesce(p_scopes, '{*}'),
        coalesce(p_can_write, false),
        coalesce(p_can_control, false)
    );

    return v_token;
end;
$$;

comment on function public.create_agent_token(text, uuid, text[], boolean, boolean) is
    'Mint an olk_ token. Returns the plaintext once. p_can_control defaults to false.';

revoke all on function public.create_agent_token(text, uuid, text[], boolean, boolean) from public;

do $$
begin
    if exists (select 1 from pg_roles where rolname = 'anon') then
        execute 'revoke all on function public.create_agent_token(text, uuid, text[], boolean, boolean) from anon';
    end if;
    if exists (select 1 from pg_roles where rolname = 'authenticated') then
        execute 'revoke all on function public.create_agent_token(text, uuid, text[], boolean, boolean) from authenticated';
    end if;
end $$;

grant execute on function public.create_agent_token(text, uuid, text[], boolean, boolean) to service_role;
