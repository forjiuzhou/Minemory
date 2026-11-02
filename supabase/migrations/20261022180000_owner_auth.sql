-- Owner-only access.
-- Run after 20261022160000_inbox.sql.
-- The phone signs in with Supabase Auth. External agents use a service-level
-- connection (the Supabase management connector) and bypass RLS.

alter table public.events
    add column if not exists user_id uuid;

delete from public.events where user_id is null;

do $$
begin
    if not exists (
        select 1
        from pg_constraint
        where conname = 'events_user_id_fkey'
          and conrelid = 'public.events'::regclass
    ) then
        alter table public.events
            add constraint events_user_id_fkey
            foreign key (user_id) references auth.users (id);
    end if;
end $$;

alter table public.events alter column user_id set default auth.uid();
alter table public.events alter column user_id set not null;

comment on column public.events.user_id is
    'The signed-in Supabase Auth user. Defaults to auth.uid() on insert from the phone.';

create index if not exists events_user_occurred_at_idx
    on public.events (user_id, occurred_at desc);

revoke all on table public.events from public, anon, authenticated;

grant usage on schema public to authenticated;
grant select, insert on table public.events to authenticated;
-- title, content, and extras stay writable because the phone fills OCR text and
-- page metadata after the first insert. tags, summary, and organized_at are the
-- organization columns. user_id, type, and device_id are not updatable.
grant update (title, content, extras, tags, summary, organized_at)
    on table public.events to authenticated;

alter table public.events enable row level security;

drop policy if exists events_owner_all on public.events;
drop policy if exists events_owner_select on public.events;
drop policy if exists events_owner_insert on public.events;
drop policy if exists events_owner_update on public.events;

create policy events_owner_select
    on public.events
    for select
    to authenticated
    using (user_id = (select auth.uid()));

create policy events_owner_insert
    on public.events
    for insert
    to authenticated
    with check (user_id = (select auth.uid()));

create policy events_owner_update
    on public.events
    for update
    to authenticated
    using (user_id = (select auth.uid()))
    with check (user_id = (select auth.uid()));

drop policy if exists inbox_insert on storage.objects;
drop policy if exists inbox_select on storage.objects;
drop policy if exists inbox_owner_insert on storage.objects;
drop policy if exists inbox_owner_select on storage.objects;
drop policy if exists inbox_owner_update on storage.objects;

revoke all on table storage.objects from anon;

grant select, insert, update on table storage.objects to authenticated;

create policy inbox_owner_insert
    on storage.objects
    for insert
    to authenticated
    with check (
        bucket_id = 'inbox'
        and (storage.foldername(name))[1] = (select auth.uid()::text)
    );

create policy inbox_owner_select
    on storage.objects
    for select
    to authenticated
    using (
        bucket_id = 'inbox'
        and (storage.foldername(name))[1] = (select auth.uid()::text)
    );

create policy inbox_owner_update
    on storage.objects
    for update
    to authenticated
    using (
        bucket_id = 'inbox'
        and (storage.foldername(name))[1] = (select auth.uid()::text)
    )
    with check (
        bucket_id = 'inbox'
        and (storage.foldername(name))[1] = (select auth.uid()::text)
    );
