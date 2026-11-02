---
name: ownlog-organize
description: >-
  Organize an OwnLog inbox in the owner's Supabase project. Use when asked to
  sort, tag, summarize, or file captures (notes, photos, voice, shares) that
  the phone stored and left untouched. OwnLog does not run a model and does
  not organize anything itself.
---

# Organize an OwnLog inbox

OwnLog is a box. The phone captures quickly and stores the original. You are the general-purpose agent that organizes it afterwards. There is no in-app summary, no in-app classifier, and no model endpoint to call.

Connect with the Supabase management connector (service-level). That credential bypasses RLS, so the queries below see the owner's rows. The publishable key shipped in the phone cannot. Do not copy that key out of the app, and do not put a secret key on the phone. The phone's own reads and writes go through the signed-in user's access token, limited to `user_id = auth.uid()`.

Work in the owner's Supabase project. Read `docs/agent-queries.md` for the rest of the log (app sessions, notifications, location). This skill is only the inbox.

Set the session time zone before any "yesterday" wording:

```sql
set time zone 'Asia/Shanghai';
```

## Find what is still unorganized

Inbox rows are `note`, `photo`, `voice`, and `share`. `organized_at is null` means nobody has filed them yet. Other event types are raw logs. Leave them alone unless the owner asks.

```sql
select
    id,
    occurred_at,
    type,
    title,
    content,
    extras,
    package_name,
    app_label,
    device_id
from events
where organized_at is null
  and type in ('note', 'photo', 'voice', 'share')
order by occurred_at asc
limit 40;
```

Read `content` first.

- `note`: the text the owner typed. `extras.note` is an optional aside.
- `photo`: `content` is on-device Chinese OCR when it succeeded. The original image is the file.
- `voice`: `content` is an on-device transcript only when `extras.transcribed` is true. When it is false or missing, `content` is empty and you transcribe the audio file.
- `share`: `content` is the shared text (Douyin and Xiaohongshu include the title). `extras.url` is the extracted link. `extras.source_package` is the sending app when the phone could tell. `extras.og_title` and `extras.og_description` are best-effort and may be absent. A shared image also has OCR in `content` and the original file.

Take the next batch after you write this one back. Stop when the query returns no rows.

## Download a file

Private bucket `inbox`. The object key is `extras.file_path`, and its first segment is the owner's auth user id: `<user_id>/<yyyy>/<mm>/<uuid>.<ext>`. `extras.mime` and `extras.bytes` describe it. The phone does not upload image bytes into `content`.

Download through the same management connector, which already has service-level access:

```http
GET https://PROJECT.supabase.co/storage/v1/object/inbox/{file_path}
```

The phone's publishable key is not enough. A signed-in user token can read only objects under that user's own id. Your connector bypasses that check; still only touch this owner's project.

Skip the download when `content` is already enough to file the row. Download when the transcript is missing, the OCR is empty or garbled, or the owner asked you to look at the original.

## Write the organization back

Update only `tags`, `summary`, and `organized_at`. The management connector can write those columns directly. Do not rewrite `content`, `extras`, or the file. The owner's capture stays as captured.

The signed-in phone role may also update `title`, `content`, and `extras` (that is how OCR and page titles land after the first save). It cannot change `user_id`. You are not using that role.

If you insert a new event yourself, set `user_id` to the owner's auth user id. Service role has `auth.uid()` null, and the column is `not null`.

```sql
update events
set tags = array['article', 'person:王小波', 'link:reading-2026-10'],
    summary = '微信文章，讲……',
    organized_at = now()
where id = '00000000-0000-0000-0000-000000000000'
  and organized_at is null;
```

`organized_at is null` in the `where` clause keeps a second pass from clobbering a row you already filed. Clear it only when the owner asks you to refile.

PostgREST, if you are not in the SQL editor, goes through the same service-level connector. Send only those three fields. Do not attach the phone's publishable key.

Write `summary` in the owner's language, one or two sentences, grounded in the row. If you are unsure, say what is missing instead of inventing a place, amount, or person.

## Category vocabulary

Reference only. The database does not enforce it. Put one category on `tags`, then add finer tags beside it.

| Tag | Use when the row is mainly |
| --- | --- |
| `task` | something to do |
| `routine` | a repeated habit |
| `event` | something that happened at a time |
| `duration` | a span of time, a session, a wait |
| `progress` | movement on something already started |
| `article` | a piece of writing the owner saved |
| `snippet` | a short fragment, not a whole piece |
| `quote` | words worth keeping verbatim |
| `link` | a URL, when the link itself is the point |
| `conversation` | a chat, a voice memo of a talk |
| `person` | about a specific person |
| `place` | about a specific place |
| `metric` | a number being tracked |
| `rating` | a judgment or score |
| `transaction` | money moving |
| `spec` | a requirement, a decision, a how-it-works |
| `gallery` | an image kept for itself |

A row can carry several. `transaction` plus `place` is normal. Do not invent a parallel taxonomy when one of these fits.

## Entities and links

Hang entities off the category tags so later queries can join rows without a second table.

- People: `person:妈妈`, `person:王小波`
- Places: `place:杭州`, `place:公司`
- A shared thread: `link:` plus a short stable slug, for example `link:hangzhou-2026-05` or `link:reading-2026-10`

Rows that belong together get the same `link:` tag. That is the link. Do not create another table unless the owner asks.

Find them again:

```sql
select id, occurred_at, type, summary, tags
from events
where 'link:hangzhou-2026-05' = any(tags)
order by occurred_at;
```

People and places the same way: `'person:妈妈' = any(tags)`.

Keep slugs stable across runs. Reuse a slug you already wrote instead of minting a near-duplicate (`hangzhou-trip` and `hangzhou-2026-05` for the same trip). Read tags you have already used before coining a new `link:`.

```sql
select distinct unnest(tags) as tag
from events
where organized_at is not null
  and tags is not null
order by 1;
```

## What not to do

- Do not look for an organize API inside the app. It does not have one.
- Do not put a secret key on the phone, and do not ask the owner to.
- Password fields are already dropped. Do not try to recover them.
- Payment, bill, and OTP notifications are stored on purpose. File a `transaction` or leave the raw notification row alone. Do not delete it.
- If a file download fails, file the row from `content` when that is enough, and leave `organized_at` null when it is not, so a later run can retry.
