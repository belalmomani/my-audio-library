-- اختبار محلي: يحاكي مخطط Supabase (auth.users, auth.uid, أدوار anon/authenticated) ثم يتحقق من القيود وRLS.
\set ON_ERROR_STOP on
create schema if not exists auth;
create table auth.users(id uuid primary key);
create or replace function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid $$;
do $$ begin
  if not exists (select 1 from pg_roles where rolname='anon') then create role anon; end if;
  if not exists (select 1 from pg_roles where rolname='authenticated') then create role authenticated; end if;
end $$;
grant usage on schema public, auth to anon, authenticated;
\i supabase/schema.sql
grant select, insert, update, delete on all tables in schema public to authenticated;
insert into auth.users values ('00000000-0000-0000-0000-00000000000a'),('00000000-0000-0000-0000-00000000000b');

create or replace function pg_temp.expect_fail(sql text, label text) returns void language plpgsql as $$
begin
  begin execute sql; exception when others then raise notice 'PASS (rejected): % -- %', label, sqlerrm; return; end;
  raise exception 'FAIL (was allowed): %', label;
end $$;

set role authenticated;
set request.jwt.claim.sub = '00000000-0000-0000-0000-00000000000a';
insert into libraries(id,user_id,name) values ('11111111-1111-1111-1111-111111111111','00000000-0000-0000-0000-00000000000a','المتحدث الأول');
insert into libraries(id,user_id,name) values ('22222222-2222-2222-2222-222222222222','00000000-0000-0000-0000-00000000000a','المتحدث الثاني');
insert into words(library_id,user_id,text,normalized_text,start_ms,end_ms,duration_ms) values ('11111111-1111-1111-1111-111111111111','00000000-0000-0000-0000-00000000000a','أحمد','أحمد',0,400,400);
-- نفس الكلمة في مكتبة أخرى: مسموح
insert into words(library_id,user_id,text,normalized_text,start_ms,end_ms,duration_ms) values ('22222222-2222-2222-2222-222222222222','00000000-0000-0000-0000-00000000000a','أحمد','أحمد',0,400,400);
select pg_temp.expect_fail($$insert into words(library_id,user_id,text,normalized_text,start_ms,end_ms,duration_ms) values ('11111111-1111-1111-1111-111111111111','00000000-0000-0000-0000-00000000000a','أحمد','أحمد',900,1300,400)$$, 'duplicate word in same library');
select pg_temp.expect_fail($$insert into words(library_id,user_id,text,normalized_text,start_ms,end_ms,duration_ms) values ('11111111-1111-1111-1111-111111111111','00000000-0000-0000-0000-00000000000a','أحمد،','أحمد،',900,1300,400)$$, 'unnormalized key with punctuation');
select pg_temp.expect_fail($$insert into words(library_id,user_id,text,normalized_text,start_ms,end_ms,duration_ms) values ('11111111-1111-1111-1111-111111111111','00000000-0000-0000-0000-00000000000a','x','',900,1300,400)$$, 'empty key');
select pg_temp.expect_fail($$insert into audio_files(user_id,word_id,object_key,file_name) select user_id,id,'users/other/x.wav','x.wav' from words limit 1$$, 'object key outside user prefix');

-- مستخدم آخر
set request.jwt.claim.sub = '00000000-0000-0000-0000-00000000000b';
do $$ begin
  if (select count(*) from libraries) <> 0 then raise exception 'FAIL: user B can read A libraries'; end if;
  if (select count(*) from words) <> 0 then raise exception 'FAIL: user B can read A words'; end if;
  raise notice 'PASS: user B sees nothing of user A';
end $$;
select pg_temp.expect_fail($$insert into words(library_id,user_id,text,normalized_text,start_ms,end_ms,duration_ms) values ('11111111-1111-1111-1111-111111111111','00000000-0000-0000-0000-00000000000b','x','x',0,10,10)$$, 'B writes into A library');
select pg_temp.expect_fail($$insert into words(library_id,user_id,text,normalized_text,start_ms,end_ms,duration_ms) values ('11111111-1111-1111-1111-111111111111','00000000-0000-0000-0000-00000000000a','x','x',0,10,10)$$, 'B impersonates A user_id');
do $$ declare n int; begin
  update libraries set name='hack' where id='11111111-1111-1111-1111-111111111111'; get diagnostics n = row_count;
  if n <> 0 then raise exception 'FAIL: B updated A library'; end if;
  delete from libraries where id='11111111-1111-1111-1111-111111111111'; get diagnostics n = row_count;
  if n <> 0 then raise exception 'FAIL: B deleted A library'; end if;
  raise notice 'PASS: B cannot update/delete A library';
end $$;
-- المالك يحذف مكتبة بالتسلسل
set request.jwt.claim.sub = '00000000-0000-0000-0000-00000000000a';
select delete_library_rows('11111111-1111-1111-1111-111111111111');
do $$ begin
  if (select count(*) from words) <> 1 then raise exception 'FAIL: cascade delete'; end if;
  raise notice 'PASS: delete_library_rows cascades';
end $$;
reset role;
set role anon;
select pg_temp.expect_fail($$select * from words$$, 'anon cannot read words');
