package com.mylibrary.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.mylibrary.app.audio.RangePlayer
import com.mylibrary.app.audio.Waveform
import com.mylibrary.app.data.AddResult
import com.mylibrary.app.data.ReviewItemEntity
import com.mylibrary.app.data.SourceEntity
import com.mylibrary.app.data.WordEntity
import com.mylibrary.app.di.ServiceLocator
import com.mylibrary.core.WavFile
import com.mylibrary.core.WordClipper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.File

/** حدود يدوية: لا قص صمت إضافي (عتبة منخفضة جدًا) كي يُحفظ بالضبط ما حدّده المستخدم. */
private fun manualClip(wav: WavFile, s: Long, e: Long) = WordClipper.clip(wav, s, e, padMs = 0, trimThresholdDb = -90.0, keepMs = 0)

class ClipEditorVm(
    private val mode: String,
    private val sourceId: String,
    private val start0: Long,
    private val end0: Long,
    libraryId0: String,
    private val wordId: String,
    private val reviewId: String,
) : BaseVm() {
    private val db = ServiceLocator.db
    private val library = ServiceLocator.library

    val loading = MutableStateFlow(true)
    val fatal = MutableStateFlow<String?>(null)
    val winStart = MutableStateFlow(0L)
    val winEnd = MutableStateFlow(0L)
    val duration = MutableStateFlow(0L)
    val peaks = MutableStateFlow(FloatArray(0))
    val selStart = MutableStateFlow(start0)
    val selEnd = MutableStateFlow(end0)
    val text = MutableStateFlow("")
    val libraryId = MutableStateFlow(libraryId0.ifEmpty { null })
    val reason = MutableStateFlow<String?>(null)
    val playing = MutableStateFlow(false)
    val duplicate = MutableStateFlow<WordEntity?>(null)
    val finished = MutableStateFlow(false)
    val libraries = library.summaries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var wav: WavFile? = null
    private var file: File? = null
    private var source: SourceEntity? = null
    private var review: ReviewItemEntity? = null
    private var targetWordId: String? = wordId.ifEmpty { null }
    private var playJob: Job? = null

    val title: String get() = when (mode) { "RECUT" -> "إعادة قص الكلمة"; "REVIEW" -> "مراجعة مقطع"; else -> "إضافة كلمة" }

    init {
        launchSafe {
            val s = db.sources().get(sourceId)
            val f = s?.workPath?.let(::File)?.takeIf { it.exists() }
            if (s == null || f == null) { fatal.value = "ملف التسجيل المؤقت غير موجود (ربما حُذف). أعد استيراد التسجيل."; loading.value = false; return@launchSafe }
            source = s; file = f
            val w = withContext(Dispatchers.IO) { WavFile.open(f) }
            wav = w; duration.value = w.durationMs
            if (reviewId.isNotEmpty()) {
                review = db.reviews().get(reviewId)?.also { r ->
                    text.value = r.text; reason.value = r.reason
                    if (libraryId.value == null) libraryId.value = r.libraryId
                    if (r.targetWordId != null) targetWordId = r.targetWordId
                    selStart.value = r.startMs; selEnd.value = r.endMs
                }
            } else if (mode == "RECUT") {
                db.words().get(wordId)?.let { text.value = it.text }
            }
            val a = selStart.value; val b = selEnd.value
            val pad = if (b - a < 3000) 2500L else 1500L
            setWindow((a - pad).coerceAtLeast(0), (b + pad).coerceAtMost(w.durationMs))
            loading.value = false
        }
    }

    private fun setWindow(a: Long, b: Long) {
        val w = wav ?: return
        val s = a.coerceIn(0, w.durationMs); val e = b.coerceIn(s + 200, w.durationMs.coerceAtLeast(s + 200))
        winStart.value = s; winEnd.value = e
        launchSafe { peaks.value = withContext(Dispatchers.IO) { Waveform.peaks(w, s, e, 600) } }
    }

    fun shift(dir: Int) { val span = winEnd.value - winStart.value; setWindow(winStart.value + dir * span / 2, winEnd.value + dir * span / 2) }
    fun zoom(factor: Double) {
        val c = (winStart.value + winEnd.value) / 2; val half = ((winEnd.value - winStart.value) * factor / 2).toLong().coerceAtLeast(150)
        setWindow(c - half, c + half)
    }

    fun setSel(a: Long, b: Long) {
        val lo = a.coerceIn(winStart.value, winEnd.value); val hi = b.coerceIn(winStart.value, winEnd.value)
        if (hi - lo >= 30) { selStart.value = lo; selEnd.value = hi }
    }

    fun reset() { selStart.value = start0; selEnd.value = end0; review?.let { selStart.value = it.startMs; selEnd.value = it.endMs } }

    fun autoTrim() = launchSafe {
        val w = wav ?: return@launchSafe
        val c = withContext(Dispatchers.IO) { WordClipper.clip(w, selStart.value, selEnd.value) }
        if (c.samples.isNotEmpty()) { selStart.value = c.startMs; selEnd.value = c.endMs } else say("لا يوجد صوت في هذا النطاق")
    }

    fun togglePlay() {
        if (playing.value) { playJob?.cancel(); playing.value = false; return }
        val f = file ?: return
        playJob = launchSafe {
            playing.value = true
            try { RangePlayer.play(f, selStart.value, selEnd.value) } finally { playing.value = false }
        }
    }

    fun approve(replaceExisting: Boolean = false) = launchSafe {
        val w = wav ?: return@launchSafe
        val clip = withContext(Dispatchers.IO) { manualClip(w, selStart.value, selEnd.value) }
        if (clip.samples.isEmpty()) { say("المقطع المحدد فارغ"); return@launchSafe }
        val q = clip.quality()
        val target = targetWordId
        val ok: Boolean = when {
            // استبدال مقطع كلمة موجودة (إعادة قص/اقتراح استبدال): لا يُحذف القديم قبل نجاح حفظ الجديد
            target != null -> {
                if (text.value.isNotBlank() && mode == "RECUT") {
                    val old = db.words().get(target)
                    if (old != null && old.text != text.value.trim()) {
                        when (val r = library.renameWord(target, text.value)) {
                            is com.mylibrary.app.data.RenameResult.Conflict -> { say("توجد كلمة بهذا الاسم مسبقًا"); return@launchSafe }
                            com.mylibrary.app.data.RenameResult.Invalid -> { say("الكلمة غير صالحة"); return@launchSafe }
                            else -> {}
                        }
                    }
                }
                library.replaceClip(target, clip, source)
            }
            else -> {
                val lib = libraryId.value ?: run { say("اختر المكتبة أولًا"); return@launchSafe }
                if (text.value.isBlank()) { say("اكتب نص الكلمة قبل الاعتماد"); return@launchSafe }
                when (val r = library.addWord(lib, text.value, clip, source, userLocked = true)) {
                    is AddResult.Added -> true
                    is AddResult.Invalid -> { say(r.reason); false }
                    is AddResult.Duplicate -> {
                        if (replaceExisting) library.replaceClip(r.existing.id, clip, source)
                        else { duplicate.value = r.existing; return@launchSafe }
                    }
                }
            }
        }
        if (ok) {
            if (reviewId.isNotEmpty()) db.reviews().delete(reviewId)
            ServiceLocator.jobs.enqueueSync()
            say(if (q.issues.isEmpty()) "تم الحفظ" else "تم الحفظ — تنبيه: " + q.issues.joinToString("، "))
            finished.value = true
        }
    }

    fun reject() = launchSafe {
        if (reviewId.isNotEmpty()) db.reviews().delete(reviewId)
        finished.value = true
    }
}

@Composable
fun ClipEditorScreen(nav: NavController, a: EditorArgs) {
    val vm = rememberVm(key = "ed-${a.sourceId}-${a.reviewId}-${a.wordId}-${a.start}") {
        ClipEditorVm(a.mode, a.sourceId, a.start, a.end, a.libraryId, a.wordId, a.reviewId)
    }
    val loading by vm.loading.collectAsState()
    val fatal by vm.fatal.collectAsState()
    val ws by vm.winStart.collectAsState()
    val we by vm.winEnd.collectAsState()
    val peaks by vm.peaks.collectAsState()
    val s by vm.selStart.collectAsState()
    val e by vm.selEnd.collectAsState()
    val text by vm.text.collectAsState()
    val lib by vm.libraryId.collectAsState()
    val libs by vm.libraries.collectAsState()
    val playing by vm.playing.collectAsState()
    val reason by vm.reason.collectAsState()
    val dup by vm.duplicate.collectAsState()
    val finished by vm.finished.collectAsState()
    var libMenu by remember { mutableStateOf(false) }

    LaunchedEffect(finished) { if (finished) nav.popBackStack() }

    ScreenScaffold(title = vm.title, onBack = { nav.popBackStack() }, messages = vm.messages, showAds = false) { pad ->
        when {
            loading -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            fatal != null -> EmptyState(fatal!!, Modifier.padding(pad))
            else -> Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                reason?.let { Text("سبب المراجعة: " + reasonLabel(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

                WaveformEditor(peaks, ws, we, s, e) { a2, b2 -> vm.setSel(a2, b2) }

                Text("البداية ${formatTime(s)} — النهاية ${formatTime(e)} — المدة ${"%.2f".format((e - s) / 1000.0)} ث",
                    style = MaterialTheme.typography.bodySmall)

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    // الموجة تتجه زمنيًا من اليسار إلى اليمين، فالسهم الأيسر = الأقدم
                    IconButton(onClick = { vm.shift(-1) }) { Icon(Icons.Filled.ChevronLeft, "إلى الخلف") }
                    IconButton(onClick = { vm.shift(1) }) { Icon(Icons.Filled.ChevronRight, "إلى الأمام") }
                    IconButton(onClick = { vm.zoom(0.6) }) { Icon(Icons.Filled.ZoomIn, "تقريب") }
                    IconButton(onClick = { vm.zoom(1.7) }) { Icon(Icons.Filled.ZoomOut, "تبعيد") }
                    Spacer(Modifier.weight(1f))
                    FilledTonalButton(onClick = { vm.togglePlay() }) {
                        Icon(if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (playing) "إيقاف" else "استمع")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.autoTrim() }) { Text("إزالة الصمت تلقائيًا") }
                    OutlinedButton(onClick = { vm.reset() }) { Text("إعادة الاستخراج") }
                }

                OutlinedTextField(value = text, onValueChange = { vm.text.value = it }, label = { Text("نص الكلمة") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                if (a.wordId.isEmpty()) {
                    Box {
                        OutlinedButton(onClick = { libMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("المكتبة: " + (libs.firstOrNull { it.id == lib }?.name ?: "اختر المكتبة"))
                        }
                        DropdownMenu(expanded = libMenu, onDismissRequest = { libMenu = false }) {
                            libs.forEach { l -> DropdownMenuItem(text = { Text(l.name) }, onClick = { libMenu = false; vm.libraryId.value = l.id }) }
                        }
                    }
                }

                Button(onClick = { vm.approve() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(if (a.wordId.isNotEmpty() || a.mode == "RECUT") "اعتماد المقطع الجديد" else "اعتماد وإضافة إلى المكتبة")
                }
                if (a.reviewId.isNotEmpty()) TextButton(onClick = { vm.reject() }, modifier = Modifier.fillMaxWidth()) { Text("رفض هذا المقطع") }
            }
        }
    }

    dup?.let { d ->
        AlertDialog(
            onDismissRequest = { vm.duplicate.value = null },
            title = { Text("الكلمة موجودة مسبقًا") },
            text = { Text("لهذا المتحدث مقطع محفوظ لكلمة «${d.text}». هل تستبدله بالمقطع الجديد؟ لا يُحذف القديم إلا بعد نجاح حفظ الجديد.") },
            confirmButton = { TextButton(onClick = { vm.duplicate.value = null; vm.approve(replaceExisting = true) }) { Text("استبدال") } },
            dismissButton = { TextButton(onClick = { vm.duplicate.value = null }) { Text("إبقاء القديم") } },
        )
    }
}

private fun formatTime(ms: Long): String = "%d:%02d.%03d".format(ms / 60000, (ms / 1000) % 60, ms % 1000)

/** موجة صوتية مع مقبضين قابلين للسحب لضبط بداية الكلمة ونهايتها. الاتجاه الزمني دائمًا من اليسار إلى اليمين. */
@Composable
private fun WaveformEditor(peaks: FloatArray, ws: Long, we: Long, s: Long, e: Long, onSel: (Long, Long) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.outlineVariant
    val handle = MaterialTheme.colorScheme.tertiary
    var width by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableIntStateOf(0) } // 1 = بداية، 2 = نهاية
    val curS by rememberUpdatedState(s)
    val curE by rememberUpdatedState(e)
    // الموجة تُرسم دائمًا باتجاه الزمن (يسار→يمين) حتى في واجهة RTL
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Canvas(
            Modifier.fillMaxWidth().height(150.dp).onSizeChanged { width = it.width.coerceAtLeast(1) }
                .pointerInput(ws, we) {
                    detectDragGestures(
                        onDragStart = { o ->
                            val span = (we - ws).toDouble()
                            val xs = (curS - ws) / span * width; val xe = (curE - ws) / span * width
                            dragging = if (kotlin.math.abs(o.x - xs) <= kotlin.math.abs(o.x - xe)) 1 else 2
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val t = ws + (change.position.x / width * (we - ws)).toLong()
                            if (dragging == 1) onSel(t, curE) else onSel(curS, t)
                        },
                        onDragEnd = { dragging = 0 }, onDragCancel = { dragging = 0 },
                    )
                },
        ) {
            val w = size.width; val h = size.height; val mid = h / 2
            val span = (we - ws).coerceAtLeast(1).toDouble()
            val xs = ((s - ws) / span * w).toFloat(); val xe = ((e - ws) / span * w).toFloat()
            drawRect(dim.copy(alpha = 0.15f), Offset.Zero, Size(w, h))
            drawRect(primary.copy(alpha = 0.15f), Offset(xs, 0f), Size((xe - xs).coerceAtLeast(0f), h))
            if (peaks.isNotEmpty()) {
                val step = w / peaks.size
                for (i in peaks.indices) {
                    val x = i * step + step / 2
                    val inside = x in xs..xe
                    val amp = peaks[i] * (h / 2 - 4)
                    drawLine(if (inside) primary else dim, Offset(x, mid - amp), Offset(x, mid + amp), strokeWidth = (step * 0.8f).coerceAtLeast(1f))
                }
            }
            drawLine(handle, Offset(xs, 0f), Offset(xs, h), strokeWidth = 6f)
            drawLine(handle, Offset(xe, 0f), Offset(xe, h), strokeWidth = 6f)
        }
    }
}

// ----------------------------------------------------------------------------- قائمة المقاطع التي تحتاج مراجعة

class ReviewListVm(private val libraryId: String) : BaseVm() {
    private val db = ServiceLocator.db
    val items = (if (libraryId.isEmpty()) db.reviews().observeAll() else db.reviews().observeForLibrary(libraryId))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun reject(id: String) = launchSafe { db.reviews().delete(id) }
}

@Composable
fun ReviewListScreen(nav: NavController, libraryId: String) {
    val vm = rememberVm(key = "rev-$libraryId") { ReviewListVm(libraryId) }
    val items by vm.items.collectAsState()
    ScreenScaffold(title = "مراجعة المقاطع", onBack = { nav.popBackStack() }, messages = vm.messages) { pad ->
        if (items.isEmpty()) EmptyState("لا توجد مقاطع بانتظار المراجعة.", Modifier.padding(pad))
        else LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { r ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.text.ifBlank { "(نص غير معروف)" }, style = MaterialTheme.typography.titleMedium)
                            Text(reasonLabel(r.reason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            Text("عند ${formatDuration(r.startMs)}" + (r.confidence?.let { " · ثقة ${percent(it)}" } ?: ""), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { vm.reject(r.id) }) { Text("رفض") }
                        Button(onClick = {
                            nav.navigate(Routes.editor("REVIEW", r.sourceId, r.startMs, r.endMs, r.libraryId.orEmpty(), "", r.id))
                        }) { Text("مراجعة") }
                    }
                }
            }
        }
    }
}
