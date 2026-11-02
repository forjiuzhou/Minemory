-- OwnLog / 自记
-- Run this once in the Supabase SQL editor (or as the project's first migration).
-- The phone uploads with the anon key. Agents can query from the SQL editor,
-- which uses a role that bypasses RLS.

create extension if not exists pgcrypto;

create table if not exists public.events (
    id uuid primary key default gen_random_uuid(),
    occurred_at timestamptz not null,
    type text not null check (
        type in (
            'app_fg',
            'app_bg',
            'ui_text',
            'tap',
            'notification',
            'screen_ocr',
            'location'
        )
    ),
    package_name text,
    app_label text,
    title text,
    content text,
    extras jsonb,
    device_id text not null,
    created_at timestamptz not null default now()
);

comment on table public.events is
    'Personal phone activity log. One owner, one project. The phone inserts rows; agents select them.';
comment on column public.events.occurred_at is 'When it happened on the phone.';
comment on column public.events.created_at is 'When the phone inserted the local row, which may be later if it was offline.';
comment on column public.events.extras is
    'JSON object. Location uses lat/lng/accuracy_m. app_bg uses duration_ms and session_id. OCR uses confidence, capture_method, reason.';

create index if not exists events_occurred_at_idx
    on public.events (occurred_at desc);
create index if not exists events_type_occurred_at_idx
    on public.events (type, occurred_at desc);
create index if not exists events_package_occurred_at_idx
    on public.events (package_name, occurred_at desc);
create index if not exists events_device_occurred_at_idx
    on public.events (device_id, occurred_at desc);

revoke all on table public.events from public, anon, authenticated;
grant usage on schema public to anon, authenticated;
grant select, insert on table public.events to anon, authenticated;

alter table public.events enable row level security;

drop policy if exists events_owner_all on public.events;
create policy events_owner_all
    on public.events
    for all
    to anon, authenticated
    using (true)
    with check (true);

-- Single-owner note
-- The anon key is the credential. Anyone who has it can read and insert events.
-- Do not ship this project as a multi-tenant service, and do not put the
-- service_role key in the phone.
--
-- After the app shows a device id, you can tighten the policy:
--
--   create table if not exists public.owner_settings (
--       id int primary key default 1 check (id = 1),
--       device_id text not null
--   );
--   insert into public.owner_settings (id, device_id)
--   values (1, '<device-id-from-the-app>')
--   on conflict (id) do update set device_id = excluded.device_id;
--   grant select on public.owner_settings to anon, authenticated;
--
--   drop policy events_owner_all on public.events;
--   create policy events_owner_device
--       on public.events
--       for all
--       to anon, authenticated
--       using (device_id = (select s.device_id from public.owner_settings s where s.id = 1))
--       with check (device_id = (select s.device_id from public.owner_settings s where s.id = 1));
--
-- The SQL editor and the service_role key bypass RLS, so agent queries keep working.
