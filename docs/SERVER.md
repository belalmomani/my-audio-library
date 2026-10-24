# خادم المعالجة (اختياري)

مشروع مستقل في `server/` (FastAPI + مهام خلفية). يعالج التسجيلات **بعد موافقة المستخدم الصريحة** فقط، ويصدر روابط R2 الموقّعة، ويتولى الحذف.

## التثبيت
```bash
cd server
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt             # الخادم الأساسي
pip install -r requirements-ml.txt          # اختياري: faster-whisper, pyannote.audio, demucs
sudo apt install ffmpeg                     # مطلوب لفك ترميز أي صيغة
cp .env.example .env                        # عدّل القيم
uvicorn app.main:app --host 0.0.0.0 --port 8000
```
Docker: `docker build -t mylib-server --build-arg WITH_ML=true server && docker run -p 8000:8000 --env-file server/.env -v mylib-data:/data mylib-server`.
ضع الخادم خلف HTTPS (Caddy/Nginx/Cloudflare Tunnel). التطبيق يرفض الاتصال بغير HTTPS في الإصدار.

## النماذج والتراخيص
| الوظيفة | المكتبة | ملاحظة ترخيص |
|---|---|---|
| كلمات + توقيت + ثقة | faster-whisper (`WHISPER_MODEL`) | الكود والأوزان MIT |
| المتحدثون + البصمات | pyannote.audio 3.x | الكود MIT؛ النماذج على Hugging Face تتطلب **قبول الشروط** وتوكنًا في `HF_TOKEN` |
| فصل الموسيقى | Demucs | MIT؛ راجع ترخيص الأوزان التي تنزّلها |
تُنزَّل الأوزان تلقائيًا عند أول تشغيل إلى ذاكرة Hugging Face المؤقتة. فعّل الميزات بـ `ENABLE_ASR / ENABLE_DIARIZATION / ENABLE_SEPARATION`. ما لم يُفعَّل يُبلَّغ عنه في `/health` ولا يدّعي التطبيق وجوده.

## الإعدادات (`.env`)
انظر `server/.env.example`. أهمها: `MAX_UPLOAD_MB` (الحجم المقبول)، `MAX_AUDIO_MINUTES`، `MAX_CONCURRENT_JOBS`، `PRESIGN_TTL_SECONDS`، `JOB_TTL_HOURS` (حذف الملفات المؤقتة تلقائيًا).

## الحماية
- كل طلب يتطلب JWT من Supabase (يُتحقق منه بـ JWKS أو HS256).
- مفاتيح الكائنات تُقبل فقط تحت `users/<uid>/`؛ والمهام لا يراها غير مالكها.
- `POST /v1/jobs` يرفض الطلب دون `consent: true`.
- مفاتيح R2 و`service_role` تبقى على الخادم فقط.

## الواجهات
`GET /health` · `POST /v1/storage/presign` · `POST /v1/jobs` · `GET /v1/jobs/{id}` · `POST /v1/jobs/{id}/cancel` · `POST /v1/jobs/{id}/resume` · `DELETE /v1/jobs/{id}` · `DELETE /v1/libraries/{id}` · `DELETE /v1/words/{id}` · `DELETE /v1/account`.

## التسجيلات الطويلة، الإعادة، الإيقاف
يُرفع المصدر إلى R2 ويُفكّ بـ ffmpeg إلى WAV أحادي (16kHz للتعرف، ومعدل أصلي حتى 44.1kHz لما يُعاد للتطبيق). faster-whisper ينافذ الملف داخليًا مع `vad_filter` فلا يحمّله كله دفعة واحدة في الذاكرة، ويحفظ **نقطة استئناف** (كلمة بكلمة) لتكمل `resume` من آخر كلمة بدل البداية. `cancel` يوقف المهمة، والملفات المؤقتة تُحذف عند الحذف أو بعد `JOB_TTL_HOURS`.
أما تقسيم التسجيلات عند أهدأ نقطة بتراكب صغير (`ChunkPlanner`) فيستعمله التطبيق في المسار المحلي عند توصيل نموذج محلي.

## التكلفة (تقدير يجب أن تتحقق منه بنفسك)
- **لا توجد معالجة سحابية مجانية فعليًا:** تحتاج جهازًا/استضافة. Whisper large-v3 يستحسن له GPU بذاكرة ≥ 8–10 GB؛ على CPU يعمل بسرعة أبطأ بكثير من الزمن الحقيقي (جرّب `medium`/`small` + `int8`).
- التكلفة ≈ (سعر ساعة الجهاز) × (زمن المعالجة). مثال: إن كان GPU بسعر X$/ساعة ويعالج ساعة صوت في ~0.2 ساعة فالتكلفة ≈ 0.2·X$ لكل ساعة صوت، مضافًا إليها pyannote/Demucs.
- R2: تخزين بالغيغابايت-شهر، ولا رسوم خروج بيانات (egress) وفق تسعيرها المعلنة؛ تحقق من التسعير الحالي. الملف WAV لكلمة واحدة صغير (عشرات الكيلوبايت).
