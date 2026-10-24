# الإعداد

## 1) المتطلبات
Android Studio (حديث) مع JDK 17 وAndroid SDK 35. إصدارات المكتبات في `gradle/libs.versions.toml` **لم تُبنَ فعليًا** في بيئة إنشاء المشروع (لا وصول لمستودعات Maven)، فاقبل اقتراحات Android Studio للتوافق (AGP ↔ Gradle ↔ Kotlin ↔ KSP ↔ Compose) عند أول مزامنة.
ملف `gradlew` غير مرفق؛ ينشئه Android Studio أو الأمر `gradle wrapper`.

## 2) القيم السرية والإعدادات
انسخ `.env.example` إلى `local.properties` (غير مرفوع إلى Git) أو عرّفها كمتغيرات بيئة. **لا تضع `service_role` ولا مفاتيح R2 في التطبيق أبدًا**؛ هذه للخادم فقط (`server/.env`).

## 3) Supabase
1. أنشئ مشروعًا، وفعّل Email auth (Authentication → Providers).
2. SQL Editor ← الصق `supabase/schema.sql` ونفّذه. ينشئ الجداول والقيود وسياسات RLS ودالتي `library_object_keys` و`delete_library_rows`.
3. من Project Settings → API: انسخ `URL` و`anon key` إلى `local.properties`. و`JWKS URL` (أو `JWT secret` لمشاريع HS256 القديمة) و`service_role` إلى `server/.env`.
4. تأكد أن `anon` لا يقرأ شيئًا (المخطط يسحب صلاحياته).

## 4) Cloudflare R2
1. أنشئ Bucket **خاصًا** (بدون وصول عام).
2. أنشئ API Token (S3) بصلاحية Object Read & Write على هذا الـ Bucket فقط، وضع قيمه في `server/.env`.
3. الملفات تُرفع بروابط موقّعة قصيرة العمر يصدرها الخادم، ومفاتيح الكائنات محصورة في `users/<uid>/...` (يتحقق الخادم وقيد قاعدة البيانات).
4. التطبيق **لا** يتصل بـ R2 بمفاتيح؛ بدون `BACKEND_URL` تتعطل المزامنة فقط ويبقى العمل المحلي.

## 5) Start.io
1. أنشئ التطبيق في لوحة Start.io وانسخ App ID إلى `STARTIO_APP_ID`.
2. القيمة الافتراضية `REPLACE_WITH_STARTIO_APP_ID` تعطّل الإعلانات تمامًا؛ لا يعتمد تشغيل التطبيق عليها.
3. البانر في شريط ثابت أسفل الشاشة ولا يغطي أزرار التشغيل/الحفظ/التصدير، ويُخفى أثناء المعالجة إن فعّلت ذلك من الإعدادات.
4. تحقق من سياسات Start.io وGoogle Play (إفصاح Data safety، موافقة الإعلانات المخصصة). واجهة SDK المستخدمة (`StartAppSDK.init/setTestAdsEnabled/setUserConsent`) وفق توثيق الإصدار 5.x لكنها **لم تُترجم هنا**؛ تحقق منها عند أول بناء.

## 6) بناء APK
- تطوير: `gradle :app:assembleDebug` → `app/build/outputs/apk/debug/`.
- إصدار: ضع `KEYSTORE_PATH/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD` ثم `gradle :app:bundleRelease`. أو شغّل workflow «Release APK» في GitHub بعد ضبط الأسرار: `KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD, SUPABASE_URL, SUPABASE_ANON_KEY, BACKEND_URL, STARTIO_APP_ID, PRIVACY_POLICY_URL`.

## 7) رفع المشروع إلى GitHub
```bash
git init && git add . && git commit -m "init"
git branch -M main && git remote add origin <URL> && git push -u origin main
```
تأكد أن `git status` لا يُظهر `.env` أو `local.properties` أو `*.jks`.

## 8) الخصوصية والحذف
- عدّل `docs/PRIVACY_POLICY.template.md` وانشره، وضع رابطه في `PRIVACY_POLICY_URL`.
- حذف كلمة/مكتبة: يحذف ملفات R2 ثم الصفوف (`DELETE /v1/words/{id}`، `/v1/libraries/{id}`).
- حذف الحساب: من الإعدادات ← يستدعي `DELETE /v1/account` فيحذف كل كائنات `users/<uid>/` من R2 ثم المستخدم في Supabase (وتُحذف صفوفه بالتسلسل). يتطلب `SUPABASE_SERVICE_ROLE_KEY` على الخادم.
