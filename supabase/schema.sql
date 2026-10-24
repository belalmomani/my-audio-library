-- مكتبتي الصوتية — مخطط PostgreSQL لـ Supabase
-- التشغيل: SQL Editor في لوحة Supabase، أو: supabase db push
-- كل الجداول محمية بـ RLS؛ لا يوجد حساب إداري مشترك، والملكية = auth.uid().

create extension if not exists pgcrypto;

-- ───────── إعدادات المستخدم ─────────
create table if not exists public.user_settings (
  user_id uuid primary key references auth.users(id) on delete cascade,
  language text not null default 'ar',
  processing_mode text not null default 'local_only' check (processing_mode in ('local_only','cloud_allowed')),
  keep_original_sources boolean not null default true,
  cloud_sync_enabled boolean not null default false,
  ads_during_processing boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

-- ───────── المكتبات (مكتبة لكل صوت/متحدث) ─────────
create table if not exists public.libraries (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  name text not null check (length(btrim(name)) between 1 and 120),
  voice_embedding real[],                       -- بصمة صوت اختيارية لاقتراح المطابقة (ليست هوية)
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists libraries_user_idx on public.libraries(user_id);

-- ───────── التسجيلات المصدرية ─────────
create table if not exists public.source_recordings (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  name text not null,
  duration_ms bigint check (duration_ms >= 0),
  size_bytes bigint check (size_bytes >= 0),
  original_key text,                            -- مفتاح R2 إن اختار المستخدم حفظ الأصل
  processed_key text,                           -- مفتاح R2 للنسخة المعالجة (فصل الموسيقى)
  created_at timestamptz not null default now()
);
create index if not exists sources_user_idx on public.source_recordings(user_id);

-- ───────── المتحدثون التقنيون داخل تسجيل (SPEAKER_00 ...) ─────────
-- هوية تقنية فقط؛ لا تعني اسم الشخص. تُربط بمكتبة بعد مراجعة المستخدم.
create table if not exists public.speakers (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  source_id uuid not null references public.source_recordings(id) on delete cascade,
  label text not null,
  library_id uuid references public.libraries(id) on delete set null,
  created_at timestamptz not null default now(),
  unique (source_id, label)
);
create index if not exists speakers_user_idx on public.speakers(user_id);

-- ───────── مهام المعالجة ─────────
create table if not exists public.processing_jobs (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  source_id uuid not null references public.source_recordings(id) on delete cascade,
  stage text not null default 'QUEUED',
  progress real not null default 0 check (progress between 0 and 1),
  mode text not null default 'local' check (mode in ('local','cloud')),
  speakers_found int not null default 0,
  words_found int not null default 0,
  words_new int not null default 0,
  duplicates_skipped int not null default 0,
  needs_review int not null default 0,
  files_saved int not null default 0,
  error_count int not null default 0,
  last_error text,
  started_at timestamptz not null default now(),
  finished_at timestamptz
);
create index if not exists jobs_user_idx on public.processing_jobs(user_id);

-- ───────── الكلمات ─────────
create table if not exists public.words (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  library_id uuid not null references public.libraries(id) on delete cascade,
  source_id uuid references public.source_recordings(id) on delete set null,
  text text not null,
  -- المفتاح المُطبَّع وفق WordNormalizer v1 (تحسبه الواجهة؛ تتحقق القيود أدناه من سلامة شكله)
  normalized_text text not null,
  start_ms bigint not null check (start_ms >= 0),
  end_ms bigint not null,
  duration_ms bigint not null check (duration_ms > 0),
  review_status text not null default 'APPROVED' check (review_status in ('APPROVED','NEEDS_REVIEW','REJECTED')),
  quality real not null default 0 check (quality between 0 and 1),
  user_locked boolean not null default false,
  processing_version text not null default 'norm1',
  extracted_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint words_time_ok check (end_ms > start_ms),
  constraint words_key_nonempty check (length(normalized_text) > 0),
  constraint words_key_clean check (normalized_text = lower(normalized_text) and normalized_text !~ '[[:space:][:punct:]،؛؟«»…“”‘’]'),
  -- القاعدة الأساسية: كلمة فريدة داخل المكتبة الواحدة، ويُسمح بتكرارها بين مكتبات مختلفة
  constraint words_unique_per_library unique (library_id, normalized_text)
);
create index if not exists words_library_idx on public.words(library_id);
create index if not exists words_user_idx on public.words(user_id);

-- ───────── ملفات الصوت (بيانات وصفية؛ الملفات نفسها في R2 لا في PostgreSQL) ─────────
create table if not exists public.audio_files (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  word_id uuid not null unique references public.words(id) on delete cascade,
  file_name text not null check (length(file_name) > 0),
  object_key text not null unique,              -- users/<uid>/words/<word>.wav (يطابق CloudSync.kt)
  constraint audio_key_prefix check (object_key like 'users/' || user_id::text || '/%'),
  format text not null default 'wav' check (format in ('wav','ogg','mp3')),
  size_bytes bigint check (size_bytes >= 0),
  sha256 text check (sha256 ~ '^[0-9a-f]{64}$'),
  upload_state text not null default 'PENDING' check (upload_state in ('PENDING','UPLOADING','DONE','FAILED')),
  created_at timestamptz not null default now()
);
create index if not exists audio_user_idx on public.audio_files(user_id);

-- ───────── سجل المراجعة ─────────
create table if not exists public.audit_log (
  id bigint generated always as identity primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  entity text not null,
  entity_id uuid,
  action text not null,
  detail jsonb,
  created_at timestamptz not null default now()
);
create index if not exists audit_user_idx on public.audit_log(user_id, created_at desc);

-- ───────── تحديث updated_at ─────────
create or replace function public.touch_updated_at() returns trigger language plpgsql as $$
begin new.updated_at = now(); return new; end $$;

do $$ declare t text; begin
  foreach t in array array['user_settings','libraries','words'] loop
    execute format('drop trigger if exists trg_touch on public.%I', t);
    execute format('create trigger trg_touch before update on public.%I for each row execute function public.touch_updated_at()', t);
  end loop;
end $$;

-- ───────── منع ربط صفوف بمالكين مختلفين (سلامة الملكية على مستوى القاعدة) ─────────
create or replace function public.enforce_same_owner() returns trigger language plpgsql as $$
declare owner_ok boolean := true;
begin
  if tg_table_name = 'words' then
    select user_id = new.user_id into owner_ok from public.libraries where id = new.library_id;
  elsif tg_table_name = 'audio_files' then
    select user_id = new.user_id into owner_ok from public.words where id = new.word_id;
  elsif tg_table_name = 'speakers' then
    select user_id = new.user_id into owner_ok from public.source_recordings where id = new.source_id;
    if owner_ok and new.library_id is not null then
      select user_id = new.user_id into owner_ok from public.libraries where id = new.library_id;
    end if;
  elsif tg_table_name = 'processing_jobs' then
    select user_id = new.user_id into owner_ok from public.source_recordings where id = new.source_id;
  end if;
  if not coalesce(owner_ok, false) then
    raise exception 'owner mismatch on %', tg_table_name using errcode = '42501';
  end if;
  return new;
end $$;

do $$ declare t text; begin
  foreach t in array array['words','audio_files','speakers','processing_jobs'] loop
    execute format('drop trigger if exists trg_owner on public.%I', t);
    execute format('create trigger trg_owner before insert or update on public.%I for each row execute function public.enforce_same_owner()', t);
  end loop;
end $$;

-- ───────── RLS ─────────
do $$ declare t text; begin
  foreach t in array array['libraries','source_recordings','speakers','processing_jobs','words','audio_files','audit_log'] loop
    execute format('alter table public.%I enable row level security', t);
    execute format('drop policy if exists owner_all on public.%I', t);
    execute format('create policy owner_all on public.%I for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid())', t);
  end loop;
end $$;

alter table public.user_settings enable row level security;
drop policy if exists owner_all on public.user_settings;
create policy owner_all on public.user_settings for all to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());

-- المستخدم المجهول (anon) لا يصل إلى أي جدول.
revoke all on all tables in schema public from anon;


-- ───────── صلاحيات ─────────
grant usage on schema public to authenticated;
grant select, insert, update, delete on all tables in schema public to authenticated;
grant usage, select on all sequences in schema public to authenticated;

-- ───────── دوال يستدعيها الخادم بتوكن المستخدم نفسه (SECURITY INVOKER ⇒ تمرّ عبر RLS) ─────────
create or replace function public.library_object_keys(p_library uuid)
returns setof text language sql stable security invoker as $$
  select a.object_key from public.audio_files a join public.words w on w.id = a.word_id where w.library_id = p_library
$$;

create or replace function public.delete_library_rows(p_library uuid)
returns void language sql security invoker as $$
  delete from public.libraries where id = p_library   -- الكلمات وملفاتها تُحذف بالتسلسل
$$;
grant execute on function public.library_object_keys(uuid), public.delete_library_rows(uuid) to authenticated;
revoke execute on function public.library_object_keys(uuid), public.delete_library_rows(uuid) from anon, public;

-- ───────── حذف الحساب/البيانات ─────────
-- حذف صف من auth.users (من الخادم بمفتاح الخدمة فقط) يحذف كل البيانات تسلسليًا (ON DELETE CASCADE).
-- الملفات في R2 يحذفها الخادم: DELETE /v1/account (انظر server/README.md) قبل حذف المستخدم.
-- حذف مكتبة أو كلمة من التطبيق يحذف صفوفها تسلسليًا؛ ويستدعي التطبيق DELETE /v1/storage لمفاتيح R2 المرتبطة.
