-- Engagement taps: like / favorite / comment / share.
-- Run after 20261022180000_owner_auth.sql. The partial index uses user_id.
-- Do not edit earlier files.
--
-- These rows live in public.events with type = 'engagement', so the existing
-- owner policies still apply: authenticated insert/select/update only where
-- user_id = auth.uid(). No new grant. service_role and the SQL editor bypass RLS.
-- The phone does not store the share-sheet URL unless that URL is already visible.

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
            'voice',
            'engagement'
        )
    );

comment on column public.events.extras is
    'JSON object. location uses lat/lng/accuracy_m. app_bg uses duration_ms and session_id. OCR uses confidence, capture_method, reason. engagement uses action (like|favorite|comment|share), action_label, author, url, screen_hint, view_id, control, matched, private_context.';

create index if not exists events_engagement_user_action_idx
    on public.events (user_id, (extras->>'action'), occurred_at desc)
    where type = 'engagement';

comment on index public.events_engagement_user_action_idx is
    'Partial index for cross-app engagement queries filtered by extras.action.';
