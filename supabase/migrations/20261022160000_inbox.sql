-- Inbox: share / note / photo / voice, agent organization columns, private storage bucket.
-- Run after 20261022000000_events.sql. Do not edit that file.

alter table public.events
    add column if not exists tags text[],
    add column if not exists summary text,
    add column if not exists organized_at timestamptz;

comment on column public.events.tags is
    'Agent-written labels. Null until something outside the phone organizes the row.';
comment on column public.events.summary is
    'Agent-written short summary. The phone does not fill this.';
comment on column public.events.organized_at is
    'When an external agent finished organizing this row. Null means it is still in the inbox.';

do $$
declare
    constraint_name text;
begin
    select con.conname into constraint_name
    from pg_constraint con
    join pg_class rel on rel.oid = con.conrelid
    join pg_namespace nsp on nsp.oid = rel.relnamespace
    where nsp.nspname = 'public'
      and rel.relname = 'events'
      and con.contype = 'c'
      and pg_get_constraintdef(con.oid) ilike '%type%';

    if constraint_name is not null then
        execute format('alter table public.events drop constraint %I', constraint_name);
    end if;
end $$;

alter table public.events
    drop constraint if exists events_type_check;

alter table public.events
    add constraint events_type_check check (
        type in (
            'app_fg',
            'app_bg',
            'ui_text',
            'tap',
            'notification',
            'screen_ocr',
            'location',
            'share',
            'note',
            'photo',
            'voice'
        )
    );

grant update (tags, summary, organized_at) on table public.events to anon, authenticated;

insert into storage.buckets (id, name, public)
values ('inbox', 'inbox', false)
on conflict (id) do update set public = excluded.public;

drop policy if exists inbox_insert on storage.objects;
drop policy if exists inbox_select on storage.objects;

create policy inbox_insert
    on storage.objects
    for insert
    to anon, authenticated
    with check (bucket_id = 'inbox');

create policy inbox_select
    on storage.objects
    for select
    to anon, authenticated
    using (bucket_id = 'inbox');
