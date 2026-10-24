package com.mylibrary.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.mylibrary.app.audio.RangePlayer
import com.mylibrary.app.data.CandidateEntity
import com.mylibrary.app.data.SpeakerEntity
import com.mylibrary.app.di.ServiceLocator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class SpeakerRow(val sp: SpeakerEntity, val segments: Int, val libraryName: String?)

/**
 * شاشة مراجعة المتحدثين قبل اعتماد النتائج: تسمية، ربط بمكتبة موجودة، دمج هويتين، وفصل كلمات نُسبت خطأً.
 * «المتحدث الأول/الثاني» معرّفات تقنية للأصوات فقط؛ لا يُستنتج اسم الشخص الحقيقي من صوته.
 */
class SpeakerReviewVm(private val jobId: String) : BaseVm() {
    private val db = ServiceLocator.db
    private val library = ServiceLocator.library

    val rows = MutableStateFlow<List<SpeakerRow>>(emptyList())
    val names = MutableStateFlow<Map<String, String>>(emptyMap())
    val libraries = library.summaries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val playing = MutableStateFlow<String?>(null)
    val dialogWords = MutableStateFlow<List<CandidateEntity>>(emptyList())
    val busy = MutableStateFlow(false)
    private var playJob: Job? = null

    init { refresh() }

    fun refresh() = launchSafe {
        val libs = db.libraries().all().associateBy { it.id }
        val out = mutableListOf<SpeakerRow>()
        for (sp in db.speakers().forJob(jobId)) {
            val n = db.candidates().forSpeaker(jobId, sp.id).size
            if (n == 0) { db.speakers().delete(sp.id); continue } // هوية فرغت بعد الدمج/الفصل
            out += SpeakerRow(sp, n, sp.libraryId?.let { libs[it]?.name })
        }
        rows.value = out
        names.value = out.associate { it.sp.id to (names.value[it.sp.id] ?: it.libraryName ?: "المتحدث ${ordinalAr(it.sp.ordinal)}") }
    }

    fun setName(spId: String, v: String) { names.value = names.value + (spId to v) }

    fun assign(spId: String, libraryId: String?, libraryName: String?) = launchSafe {
        db.speakers().assign(spId, libraryId)
        if (libraryName != null) setName(spId, libraryName)
        refresh()
    }

    fun merge(from: String, to: String) = launchSafe {
        db.candidates().moveSpeaker(jobId, from, to)
        refresh()
        say("دُمجت الهويتان. يمكنك فصل الكلمات لاحقًا إن كان الدمج خاطئًا.")
    }

    fun loadWords(spId: String) = launchSafe { dialogWords.value = db.candidates().forSpeaker(jobId, spId) }

    /** نقل كلمات محددة إلى متحدث آخر أو إلى هوية جديدة (فصل شخصين دُمجا خطأً). */
    fun move(ids: List<String>, fromSp: String, toSp: String?) = launchSafe {
        if (ids.isEmpty()) return@launchSafe
        val target = toSp ?: run {
            val all = db.speakers().forJob(jobId)
            val src = all.first { it.id == fromSp }
            val id = UUID.randomUUID().toString()
            db.speakers().upsertAll(listOf(SpeakerEntity(id, src.sourceId, jobId, "MANUAL_${all.size}", (all.maxOfOrNull { it.ordinal } ?: 0) + 1)))
            id
        }
        db.candidates().moveCandidates(ids, target)
        dialogWords.value = emptyList()
        refresh()
    }

    fun togglePlay(row: SpeakerRow) {
        if (playing.value == row.sp.id) { playJob?.cancel(); playing.value = null; return }
        playJob?.cancel()
        playJob = launchSafe {
            val job = db.jobs().get(jobId) ?: return@launchSafe
            val source = db.sources().get(job.sourceId)
            val file = job.processedPath?.takeIf { job.useProcessedAudio }?.let(::File)?.takeIf { it.exists() }
                ?: source?.workPath?.let(::File)?.takeIf { it.exists() }
            if (file == null) { say("ملف الصوت المؤقت غير موجود"); return@launchSafe }
            val c = db.candidates().forSpeaker(jobId, row.sp.id)
            if (c.isEmpty()) return@launchSafe
            // عينة: أطول تتابع متقارب من كلام المتحدث (حتى 8 ثوانٍ) بدءًا من منتصف كلامه تقريبًا
            var i = c.size / 2
            val start = c[i].startMs
            var end = c[i].endMs
            while (i + 1 < c.size && c[i + 1].startMs - end < 600 && end - start < 8000) { i++; end = c[i].endMs }
            playing.value = row.sp.id
            try { RangePlayer.play(file, (start - 100).coerceAtLeast(0), end + 100) } finally { if (playing.value == row.sp.id) playing.value = null }
        }
    }

    fun approve(onDone: () -> Unit) = launchSafe {
        busy.value = true
        try {
            playJob?.cancel()
            for (r in rows.value) {
                val name = names.value[r.sp.id].orEmpty().trim().ifEmpty { "المتحدث ${ordinalAr(r.sp.ordinal)}" }
                val lib = r.sp.libraryId
                if (lib == null) db.speakers().assign(r.sp.id, library.createLibrary(name))
                else if (name != r.libraryName) library.renameLibrary(lib, name)
            }
            ServiceLocator.jobs.approveSpeakers(jobId)
            onDone()
        } finally { busy.value = false }
    }
}

@Composable
fun SpeakerReviewScreen(nav: NavController, jobId: String) {
    val vm = rememberVm(key = "spk-$jobId") { SpeakerReviewVm(jobId) }
    val rows by vm.rows.collectAsState()
    val names by vm.names.collectAsState()
    val libs by vm.libraries.collectAsState()
    val playing by vm.playing.collectAsState()
    val busy by vm.busy.collectAsState()
    var wordsFor by remember { mutableStateOf<SpeakerRow?>(null) }
    var mergeFrom by remember { mutableStateOf<SpeakerRow?>(null) }
    var mergeTo by remember { mutableStateOf<SpeakerRow?>(null) }

    ScreenScaffold(title = "مراجعة المتحدثين", onBack = { nav.popBackStack() }, messages = vm.messages) { pad ->
        Column(Modifier.padding(pad)) {
            Text(
                "عدد الأصوات المكتشفة: ${rows.size}. سمِّ كل صوت أو اربطه بمكتبة موجودة (للمتحدث نفسه في تسجيل سابق)، ثم اعتمد. " +
                    "قد يُقسَّم الشخص الواحد إلى هويتين أو يُدمج شخصان، فصحّح ذلك هنا قبل الاعتماد.",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
            )
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(rows, key = { it.sp.id }) { r ->
                    var menu by remember { mutableStateOf(false) }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("المتحدث ${ordinalAr(r.sp.ordinal)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Text("${r.segments} كلمة", style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedTextField(
                                value = names[r.sp.id].orEmpty(), onValueChange = { vm.setName(r.sp.id, it) },
                                label = { Text("اسم المكتبة") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                if (r.sp.libraryId != null) "ستُضاف كلماته الجديدة إلى المكتبة الموجودة «${r.libraryName ?: ""}»"
                                else "ستُنشأ مكتبة جديدة لهذا الصوت",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                FilledTonalIconButton(onClick = { vm.togglePlay(r) }) {
                                    Icon(if (playing == r.sp.id) Icons.Filled.Stop else Icons.Filled.PlayArrow, "عينة صوتية")
                                }
                                Box {
                                    OutlinedButton(onClick = { menu = true }) { Text("ربط بمكتبة") }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(text = { Text("مكتبة جديدة") }, onClick = {
                                            menu = false; vm.assign(r.sp.id, null, "المتحدث ${ordinalAr(r.sp.ordinal)}")
                                        })
                                        libs.forEach { l ->
                                            DropdownMenuItem(text = { Text("${l.name} (${l.wordCount})") }, onClick = {
                                                menu = false; vm.assign(r.sp.id, l.id, l.name)
                                            })
                                        }
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { vm.loadWords(r.sp.id); wordsFor = r }) { Text("عرض الكلمات") }
                                TextButton(onClick = { vm.loadWords(r.sp.id); wordsFor = r }) { Text("تصحيح المقاطع") }
                                if (rows.size > 1) TextButton(onClick = { mergeFrom = r }) { Text("دمج هويتين") }
                            }
                        }
                    }
                }
            }
            Button(
                onClick = { vm.approve { nav.navigate(Routes.JOBS) { popUpTo(Routes.HOME) } } },
                enabled = rows.isNotEmpty() && !busy,
                modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 52.dp),
            ) { Text(if (busy) "جارٍ الاعتماد…" else "اعتماد واستخراج الكلمات") }
        }
    }

    // اختيار الهوية المراد الدمج معها
    mergeFrom?.let { from ->
        AlertDialog(
            onDismissRequest = { mergeFrom = null },
            title = { Text("دمج المتحدث ${ordinalAr(from.sp.ordinal)} مع…") },
            text = {
                Column {
                    rows.filter { it.sp.id != from.sp.id }.forEach { o ->
                        TextButton(onClick = { mergeTo = o }) { Text("المتحدث ${ordinalAr(o.sp.ordinal)} (${o.segments} كلمة)") }
                    }
                }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { mergeFrom = null }) { Text("إلغاء") } },
        )
    }
    if (mergeFrom != null && mergeTo != null) {
        val f = mergeFrom!!; val t = mergeTo!!
        ConfirmDialog("تأكيد الدمج", "ادمج هاتين الهويتين فقط إذا كنت متأكدًا أنهما الشخص نفسه. ستنتقل كل كلمات المتحدث ${ordinalAr(f.sp.ordinal)} إلى المتحدث ${ordinalAr(t.sp.ordinal)}.",
            "دمج", { vm.merge(f.sp.id, t.sp.id) }, { mergeFrom = null; mergeTo = null })
    }

    wordsFor?.let { r -> WordsDialog(vm, r, rows, onClose = { wordsFor = null }) }
}

@Composable
private fun WordsDialog(vm: SpeakerReviewVm, r: SpeakerRow, rows: List<SpeakerRow>, onClose: () -> Unit) {
    val words by vm.dialogWords.collectAsState()
    var selected by remember(r.sp.id) { mutableStateOf(setOf<String>()) }
    var menu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("كلمات المتحدث ${ordinalAr(r.sp.ordinal)}") },
        text = {
            Column {
                Text("حدّد الكلمات التي نُسبت إلى هذا الصوت خطأً ثم انقلها.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(words, key = { it.id }) { c ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = c.id in selected, onCheckedChange = { selected = if (it) selected + c.id else selected - c.id })
                            Text(c.text, Modifier.weight(1f))
                            Text(formatDuration(c.startMs), style = MaterialTheme.typography.bodySmall)
                            c.confidence?.let { Text(" ${percent(it)}", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Box {
                TextButton(onClick = { menu = true }, enabled = selected.isNotEmpty()) { Text("نقل المحدد (${selected.size})") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("إلى متحدث جديد") }, onClick = { menu = false; vm.move(selected.toList(), r.sp.id, null); onClose() })
                    rows.filter { it.sp.id != r.sp.id }.forEach { o ->
                        DropdownMenuItem(text = { Text("إلى المتحدث ${ordinalAr(o.sp.ordinal)}") }, onClick = {
                            menu = false; vm.move(selected.toList(), r.sp.id, o.sp.id); onClose()
                        })
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("إغلاق") } },
    )
}
