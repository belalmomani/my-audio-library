package com.mylibrary.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.mylibrary.app.audio.AudioDecoder
import com.mylibrary.app.audio.OggOpusEncoder
import com.mylibrary.app.audio.RangePlayer
import com.mylibrary.app.data.*
import com.mylibrary.app.di.ServiceLocator
import com.mylibrary.core.ConflictResolution
import com.mylibrary.core.MergePlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

// ----------------------------------------------------------------------------- قائمة المكتبات

class LibraryListVm : BaseVm() {
    private val library = ServiceLocator.library
    private val sync = ServiceLocator.sync
    val query = MutableStateFlow("")
    val items = combine(library.summaries(), query) { l, q ->
        if (q.isBlank()) l else l.filter { it.name.contains(q.trim(), ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val allLibraries = library.summaries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val mergePlan = MutableStateFlow<MergePlan?>(null)

    fun create(name: String) = launchSafe { library.createLibrary(name) }
    fun rename(id: String, name: String) = launchSafe { library.renameLibrary(id, name) }

    /** يحذف السحابة أولًا؛ إن فشل لا يُحذف شيء محليًا فلا تبقى نسخة يتيمة. */
    fun delete(id: String) = launchSafe {
        sync.deleteLibraryRemote(id)
        library.deleteLibrary(id)
        say("حُذفت المكتبة")
    }

    fun planMerge(target: String, source: String) = launchSafe { mergePlan.value = library.mergePlan(target, source) }

    fun merge(target: String, source: String, how: ConflictResolution) = launchSafe {
        library.mergeLibraries(target, source, how)
        runCatching { sync.deleteLibraryRemote(source) }
        ServiceLocator.jobs.enqueueSync()
        mergePlan.value = null
        say("تم دمج المكتبتين")
    }

    fun export(uri: Uri, ids: List<String>?, o: Exporter.Options) = launchSafe {
        val n = ServiceLocator.exporter.exportLibraries(ids, uri, o)
        say(if (n == 0) "لا توجد كلمات للتصدير بهذه الخيارات" else "صُدِّرت $n كلمة")
    }
}

@Composable
fun LibraryListScreen(nav: NavController) {
    val vm = rememberVm { LibraryListVm() }
    val items by vm.items.collectAsState()
    val all by vm.allLibraries.collectAsState()
    val query by vm.query.collectAsState()
    val plan by vm.mergePlan.collectAsState()

    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<LibrarySummary?>(null) }
    var deleting by remember { mutableStateOf<LibrarySummary?>(null) }
    var mergeSource by remember { mutableStateOf<LibrarySummary?>(null) }
    var mergeTarget by remember { mutableStateOf<LibrarySummary?>(null) }
    var exportIds by remember { mutableStateOf<List<String>?>(emptyList()) } // emptyList = لا حوار؛ null = كل المكتبات
    var pending by remember { mutableStateOf<Pair<List<String>?, Exporter.Options>?>(null) }

    val createZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val p = pending
        if (uri != null && p != null) vm.export(uri, p.first, p.second)
        pending = null
    }

    ScreenScaffold(
        title = "مكتبة الأصوات", onBack = { nav.popBackStack() }, messages = vm.messages,
        actions = { IconButton(onClick = { exportIds = null }) { Icon(Icons.Filled.Archive, "تصدير كل المكتبات") } },
        fab = { ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("مكتبة جديدة") }) },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(
                value = query, onValueChange = { vm.query.value = it }, singleLine = true,
                label = { Text("البحث عن متحدث") }, leadingIcon = { Icon(Icons.Filled.Search, null) },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            if (items.isEmpty()) EmptyState(if (query.isBlank()) "لا توجد مكتبات بعد." else "لا نتائج.")
            else LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items, key = { it.id }) { l ->
                    var menu by remember { mutableStateOf(false) }
                    Card(Modifier.fillMaxWidth().clickable { nav.navigate("library/${l.id}") }) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(l.name, style = MaterialTheme.typography.titleMedium)
                                Text("${l.wordCount} كلمة فريدة · ${formatBytes(l.sizeBytes)} · أُنشئت ${formatDate(l.createdAt)}", style = MaterialTheme.typography.bodySmall)
                            }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "المزيد") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("تعديل الاسم") }, onClick = { menu = false; renaming = l })
                                    DropdownMenuItem(text = { Text("تصدير") }, onClick = { menu = false; exportIds = listOf(l.id) })
                                    if (all.size > 1) DropdownMenuItem(text = { Text("دمج في مكتبة أخرى…") }, onClick = { menu = false; mergeSource = l })
                                    DropdownMenuItem(text = { Text("حذف") }, onClick = { menu = false; deleting = l })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating) TextInputDialog("مكتبة جديدة", label = "الاسم", onOk = { vm.create(it) }, onDismiss = { creating = false })
    renaming?.let { l -> TextInputDialog("تعديل الاسم", l.name, "الاسم", { vm.rename(l.id, it) }, { renaming = null }) }
    deleting?.let { l ->
        ConfirmDialog("حذف المكتبة", "ستُحذف «${l.name}» بكل كلماتها وملفاتها (ومن السحابة إن كانت مزامَنة). لا يمكن التراجع.", "حذف",
            { vm.delete(l.id) }, { deleting = null })
    }

    mergeSource?.let { src ->
        if (mergeTarget == null) {
            AlertDialog(
                onDismissRequest = { mergeSource = null }, title = { Text("دمج «${src.name}» في…") },
                text = { Column { all.filter { it.id != src.id }.forEach { t ->
                    TextButton(onClick = { mergeTarget = t; vm.planMerge(t.id, src.id) }) { Text(t.name) }
                } } },
                confirmButton = {}, dismissButton = { TextButton(onClick = { mergeSource = null }) { Text("إلغاء") } },
            )
        } else {
            MergeDialog(src, mergeTarget!!, plan, onMerge = { how -> vm.merge(mergeTarget!!.id, src.id, how) },
                onDismiss = { mergeSource = null; mergeTarget = null; vm.mergePlan.value = null })
        }
    }

    if (exportIds?.isEmpty() != true) {
        ExportDialog(
            onConfirm = { o -> pending = exportIds to o; createZip.launch("مكتبتي_الصوتية.zip"); exportIds = emptyList() },
            onDismiss = { exportIds = emptyList() },
        )
    }
}

@Composable
private fun MergeDialog(src: LibrarySummary, target: LibrarySummary, plan: MergePlan?, onMerge: (ConflictResolution) -> Unit, onDismiss: () -> Unit) {
    var how by remember { mutableStateOf(ConflictResolution.KEEP_BEST) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("دمج صريح بين مكتبتين") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("ستنتقل كلمات «${src.name}» إلى «${target.name}» ثم تُحذف «${src.name}».")
                if (plan == null) Text("جارٍ فحص التعارضات…")
                else {
                    Text("كلمات ستُنقل مباشرة: ${plan.movable.size}")
                    Text("كلمات متكررة في المكتبتين: ${plan.conflicts.size}")
                    if (plan.conflicts.isNotEmpty()) {
                        Text("عند التكرار:", style = MaterialTheme.typography.labelLarge)
                        listOf(
                            ConflictResolution.KEEP_BEST to "الاحتفاظ بالأفضل جودة",
                            ConflictResolution.KEEP_TARGET to "الاحتفاظ بمقطع «${target.name}»",
                            ConflictResolution.USE_SOURCE to "استخدام مقطع «${src.name}»",
                        ).forEach { (k, label) ->
                            Row(Modifier.fillMaxWidth().clickable { how = k }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = how == k, onClick = { how = k }); Text(label)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onMerge(how); onDismiss() }, enabled = plan != null) { Text("دمج") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

@Composable
fun ExportDialog(onConfirm: (Exporter.Options) -> Unit, onDismiss: () -> Unit) {
    var fmt by remember { mutableStateOf(ServiceLocator.settings.current.exportFormat) }
    var newOnly by remember { mutableStateOf(false) }
    var meta by remember { mutableStateOf(true) }
    val oggOk = remember { OggOpusEncoder.isSupported() }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("تصدير ZIP") },
        text = {
            Column {
                Text("مجلد لكل متحدث، وملف لكل كلمة، مع ملف بيانات وREADME. لا يُدمج الصوت في ملف واحد.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(fmt == "wav", { fmt = "wav" }); Text("WAV (جودة عالية)") }
                if (oggOk) Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(fmt == "ogg", { fmt = "ogg" }); Text("OGG (Opus) للألعاب") }
                else Text("OGG غير متاح على هذا الجهاز (يتطلب Android 10+ ومرمّز Opus).", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(newOnly, { newOnly = it }); Text("الكلمات الجديدة فقط (منذ آخر تصدير)") }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(meta, { meta = it }); Text("تضمين البيانات الوصفية") }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(Exporter.Options(if (fmt == "ogg" && oggOk) "ogg" else "wav", newOnly, meta)) }) { Text("تصدير") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

// ----------------------------------------------------------------------------- تفاصيل مكتبة

enum class SortBy(val label: String) { ALPHA("أبجديًا"), NEWEST("الأحدث"), QUALITY("الجودة"), DURATION("المدة") }
enum class FilterBy(val label: String) { ALL("الكل"), NEEDS("تحتاج مراجعة"), LOW("جودة منخفضة") }

class LibraryDetailVm(private val id: String) : BaseVm() {
    private val db = ServiceLocator.db
    private val library = ServiceLocator.library
    val lib = db.libraries().observe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val query = MutableStateFlow("")
    val sort = MutableStateFlow(SortBy.ALPHA)
    val filter = MutableStateFlow(FilterBy.ALL)
    val words = combine(library.wordsOf(id), query, sort, filter) { list, q, s, f ->
        var r = list
        if (q.isNotBlank()) r = r.filter { it.text.contains(q.trim(), true) || it.normalized.contains(q.trim(), true) }
        r = when (f) {
            FilterBy.ALL -> r
            FilterBy.NEEDS -> r.filter { it.reviewStatus != "APPROVED" }
            FilterBy.LOW -> r.filter { it.quality < 0.4 }
        }
        when (s) {
            SortBy.ALPHA -> r.sortedBy { it.normalized }
            SortBy.NEWEST -> r.sortedByDescending { it.extractedAt }
            SortBy.QUALITY -> r.sortedByDescending { it.quality }
            SortBy.DURATION -> r.sortedByDescending { it.durationMs }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val reviewCount = db.reviews().observeForLibrary(id).map { it.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val sources = MutableStateFlow<List<SourceEntity>>(emptyList())
    val editableSources = MutableStateFlow<List<SourceEntity>>(emptyList())
    val playingId = MutableStateFlow<String?>(null)
    val playingAll = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    private var playJob: Job? = null

    init { refreshSources() }

    fun refreshSources() = launchSafe {
        sources.value = db.sources().forLibrary(id)
        editableSources.value = db.sources().all().filter { s -> s.workPath?.let { File(it).exists() } == true }
    }

    fun togglePlay(w: WordEntity) {
        if (playingId.value == w.id) { stop(); return }
        stop()
        playJob = launchSafe {
            playingId.value = w.id
            try { RangePlayer.play(File(w.localPath)) } finally { if (playingId.value == w.id) playingId.value = null }
        }
    }

    /** تشغيل الكلمات واحدة تلو الأخرى للمراجعة فقط؛ لا يُنشأ أي ملف مدموج. */
    fun playAll() {
        stop()
        playJob = launchSafe {
            playingAll.value = true
            try {
                for (w in words.value) {
                    playingId.value = w.id
                    RangePlayer.play(File(w.localPath))
                    kotlinx.coroutines.delay(250)
                }
            } finally { playingAll.value = false; playingId.value = null }
        }
    }

    fun stop() { playJob?.cancel(); playingId.value = null; playingAll.value = false }

    fun renameLibrary(name: String) = launchSafe { library.renameLibrary(id, name) }

    fun renameWord(wordId: String, text: String) = launchSafe {
        when (val r = library.renameWord(wordId, text)) {
            RenameResult.Ok -> { ServiceLocator.jobs.enqueueSync(); say("تمت إعادة التسمية") }
            is RenameResult.Conflict -> say("توجد كلمة «${r.existing.text}» في هذه المكتبة مسبقًا؛ احذفها أو استبدلها أولًا")
            RenameResult.Invalid -> say("الاسم غير صالح")
        }
    }

    fun deleteWord(wordId: String) = launchSafe {
        ServiceLocator.sync.deleteWordRemote(wordId) // إن فشل الحذف السحابي لا يُحذف محليًا
        library.deleteWord(wordId)
        say("حُذفت الكلمة")
    }

    /** يتحقق من وجود ملف العمل قبل فتح محرر إعادة القص. */
    fun prepareRecut(w: WordEntity, go: (sourceId: String) -> Unit) = launchSafe {
        val s = w.sourceId?.let { db.sources().get(it) }
        if (s?.workPath?.let { File(it).exists() } == true) go(s.id)
        else say("ملف التسجيل المؤقت محذوف. استخدم «استبدال بمقطع من ملف آخر» لاختيار الملف الأصلي من جديد.")
    }

    /** يفك ترميز ملف يختاره المستخدم ليُقصّ منه يدويًا (استبدال أو إضافة). */
    fun importForEditing(uri: Uri, go: (sourceId: String) -> Unit) = launchSafe {
        busy.value = true
        try {
            val picked = ServiceLocator.jobs.describe(uri)
            ServiceLocator.jobs.persistRead(uri)
            val sid = UUID.randomUUID().toString()
            val out = File(ServiceLocator.app.filesDir, "work/$sid.wav").also { it.parentFile?.mkdirs() }
            val info = withContext(Dispatchers.IO) { AudioDecoder.decodeToWav(ServiceLocator.app, uri, out, { false }) { } }
            db.sources().upsert(SourceEntity(sid, picked.name, uri.toString(), out.absolutePath, null, info.durationMs, picked.sizeBytes, System.currentTimeMillis()))
            refreshSources()
            go(sid)
        } finally { busy.value = false }
    }

    fun export(uri: Uri, o: Exporter.Options) = launchSafe {
        val n = ServiceLocator.exporter.exportLibraries(listOf(id), uri, o)
        say(if (n == 0) "لا توجد كلمات للتصدير بهذه الخيارات" else "صُدِّرت $n كلمة")
    }

    fun exportWord(wordId: String, uri: Uri) = launchSafe {
        ServiceLocator.exporter.exportWord(wordId, uri, ServiceLocator.settings.current.exportFormat)
        say("صُدِّرت الكلمة")
    }
}

@Composable
fun LibraryDetailScreen(nav: NavController, id: String) {
    val vm = rememberVm(key = "lib-$id") { LibraryDetailVm(id) }
    val lib by vm.lib.collectAsState()
    val words by vm.words.collectAsState()
    val query by vm.query.collectAsState()
    val sort by vm.sort.collectAsState()
    val filter by vm.filter.collectAsState()
    val reviewCount by vm.reviewCount.collectAsState()
    val sources by vm.sources.collectAsState()
    val editable by vm.editableSources.collectAsState()
    val playingId by vm.playingId.collectAsState()
    val playingAll by vm.playingAll.collectAsState()
    val busy by vm.busy.collectAsState()

    var renamingLib by remember { mutableStateOf(false) }
    var renamingWord by remember { mutableStateOf<WordEntity?>(null) }
    var deletingWord by remember { mutableStateOf<WordEntity?>(null) }
    var exportDialog by remember { mutableStateOf(false) }
    var pendingOpts by remember { mutableStateOf<Exporter.Options?>(null) }
    var addDialog by remember { mutableStateOf(false) }
    var replaceTarget by remember { mutableStateOf<WordEntity?>(null) }
    var singleExport by remember { mutableStateOf<String?>(null) }

    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val o = pendingOpts
        if (uri != null && o != null) vm.export(uri, o)
        pendingOpts = null
    }
    val wavLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/*")) { uri ->
        val w = singleExport
        if (uri != null && w != null) vm.exportWord(w, uri)
        singleExport = null
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = replaceTarget
        if (uri != null) vm.importForEditing(uri) { sid ->
            if (target != null) nav.navigate(Routes.editor("RECUT", sid, 0, 3000, id, target.id, ""))
            else nav.navigate(Routes.editor("ADD", sid, 0, 2000, id, "", ""))
        }
        replaceTarget = null
    }

    ScreenScaffold(
        title = lib?.name ?: "المكتبة", onBack = { vm.stop(); nav.popBackStack() }, messages = vm.messages,
        actions = {
            IconButton(onClick = { renamingLib = true }) { Icon(Icons.Filled.Edit, "تعديل الاسم") }
            IconButton(onClick = { exportDialog = true }) { Icon(Icons.Filled.Archive, "تصدير") }
        },
        fab = { ExtendedFloatingActionButton(onClick = { addDialog = true }, icon = { Icon(Icons.Filled.ContentCut, null) }, text = { Text("إضافة كلمة يدويًا") }) },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${words.size} كلمة فريدة · ${formatBytes(words.sumOf { it.sizeBytes })}" + (lib?.let { " · أُنشئت ${formatDate(it.createdAt)}" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium)
                if (sources.isNotEmpty()) Text("التسجيلات المصدرية: " + sources.joinToString("، ") { it.name }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (reviewCount > 0) FilledTonalButton(onClick = { nav.navigate("reviews?libraryId=$id") }) { Text("مقاطع بانتظار المراجعة ($reviewCount)") }
                OutlinedTextField(value = query, onValueChange = { vm.query.value = it }, singleLine = true, label = { Text("البحث عن كلمة") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SortBy.entries.forEach { s -> FilterChip(selected = sort == s, onClick = { vm.sort.value = s }, label = { Text(s.label) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterBy.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { vm.filter.value = f }, label = { Text(f.label) }) }
                    Spacer(Modifier.weight(1f))
                    if (playingAll) FilledTonalButton(onClick = { vm.stop() }) { Icon(Icons.Filled.Stop, null); Text("إيقاف") }
                    else OutlinedButton(onClick = { vm.playAll() }, enabled = words.isNotEmpty()) { Icon(Icons.Filled.PlaylistPlay, null); Text("تشغيل الكل") }
                }
            }
            if (words.isEmpty()) EmptyState("لا توجد كلمات.")
            else LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                items(words, key = { it.id }) { w ->
                    var menu by remember { mutableStateOf(false) }
                    ListItem(
                        leadingContent = {
                            FilledTonalIconButton(onClick = { vm.togglePlay(w) }) {
                                Icon(if (playingId == w.id) Icons.Filled.Stop else Icons.Filled.PlayArrow, "تشغيل")
                            }
                        },
                        headlineContent = { Text(w.text, style = MaterialTheme.typography.titleMedium) },
                        supportingContent = {
                            Text("%.2f ث · جودة %s · %s".format(w.durationMs / 1000.0, percent(w.quality), if (w.reviewStatus == "APPROVED") "معتمدة" else "تحتاج مراجعة"))
                        },
                        trailingContent = {
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "المزيد") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("إعادة القص") }, onClick = {
                                        menu = false
                                        vm.prepareRecut(w) { sid -> nav.navigate(Routes.editor("RECUT", sid, w.startMs, w.endMs, id, w.id, "")) }
                                    })
                                    DropdownMenuItem(text = { Text("استبدال بمقطع من ملف آخر") }, onClick = {
                                        menu = false; replaceTarget = w; pickFile.launch(arrayOf("audio/*", "video/*"))
                                    })
                                    DropdownMenuItem(text = { Text("إعادة تسمية") }, onClick = { menu = false; renamingWord = w })
                                    DropdownMenuItem(text = { Text("تصدير هذه الكلمة") }, onClick = {
                                        menu = false; singleExport = w.id
                                        wavLauncher.launch(w.fileName.removeSuffix(".wav") + if (ServiceLocator.settings.current.exportFormat == "ogg") ".ogg" else ".wav")
                                    })
                                    DropdownMenuItem(text = { Text("حذف") }, onClick = { menu = false; deletingWord = w })
                                }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (renamingLib) TextInputDialog("تعديل اسم المكتبة", lib?.name.orEmpty(), "الاسم", { vm.renameLibrary(it) }, { renamingLib = false })
    renamingWord?.let { w -> TextInputDialog("إعادة تسمية الكلمة", w.text, "الكلمة", { vm.renameWord(w.id, it) }, { renamingWord = null }) }
    deletingWord?.let { w -> ConfirmDialog("حذف الكلمة", "ستُحذف «${w.text}» وملفها الصوتي.", "حذف", { vm.deleteWord(w.id) }, { deletingWord = null }) }
    if (exportDialog) ExportDialog(
        onConfirm = { o -> pendingOpts = o; exportDialog = false; zipLauncher.launch("مكتبتي_الصوتية.zip") },
        onDismiss = { exportDialog = false },
    )
    if (addDialog) {
        AlertDialog(
            onDismissRequest = { addDialog = false }, title = { Text("إضافة كلمة يدويًا") },
            text = {
                Column {
                    Text("اختر تسجيلًا مجهّزًا لقصّ الكلمة منه، أو افتح ملفًا جديدًا.", style = MaterialTheme.typography.bodySmall)
                    editable.forEach { s ->
                        TextButton(onClick = { addDialog = false; nav.navigate(Routes.editor("ADD", s.id, 0, 2000, id, "", "")) }) { Text(s.name) }
                    }
                    TextButton(onClick = { addDialog = false; replaceTarget = null; pickFile.launch(arrayOf("audio/*", "video/*")) }) { Text("فتح ملف جديد…") }
                }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { addDialog = false }) { Text("إغلاق") } },
        )
    }
}
