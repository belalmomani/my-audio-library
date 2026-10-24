package com.mylibrary.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.mylibrary.app.data.JobEntity
import com.mylibrary.app.data.SourceEntity
import com.mylibrary.app.di.ServiceLocator
import com.mylibrary.core.JobStage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class JobRow(val job: JobEntity, val source: SourceEntity?)

class JobsVm : BaseVm() {
    private val db = ServiceLocator.db
    private val jobs = ServiceLocator.jobs

    val rows = db.jobs().observeAll().map { list ->
        val sources = db.sources().all().associateBy { it.id }
        list.map { JobRow(it, sources[it.sourceId]) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun pause(id: String) = launchSafe { jobs.pause(id) }
    fun resume(id: String) = launchSafe { jobs.resume(id) }
    fun cancel(id: String) = launchSafe { jobs.cancel(id) }
    fun retry(id: String) = launchSafe { jobs.retry(id) }
    fun delete(id: String) = launchSafe { jobs.deleteFinished(id) }
}

@Composable
fun JobsScreen(nav: NavController) {
    val vm = rememberVm { JobsVm() }
    val rows by vm.rows.collectAsState()
    val running = rows.any { !JobStage.valueOf(it.job.stage).isTerminal && !it.job.paused }
    ScreenScaffold(title = "المهام", onBack = { nav.popBackStack() }, messages = vm.messages, processing = running) { pad ->
        if (rows.isEmpty()) {
            EmptyState("لا توجد مهام بعد. استورد تسجيلًا لتبدأ.", Modifier.padding(pad))
        } else {
            LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(rows, key = { it.job.id }) { r ->
                    JobCard(
                        r, vm,
                        onSpeakers = { nav.navigate("job/${r.job.id}/speakers") },
                        onReviews = { nav.navigate("reviews") },
                    )
                }
            }
        }
    }
}

@Composable
private fun JobCard(r: JobRow, vm: JobsVm, onSpeakers: () -> Unit, onReviews: () -> Unit) {
    val j = r.job
    val stage = JobStage.valueOf(j.stage)
    var expanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(r.source?.name ?: "تسجيل", style = MaterialTheme.typography.titleMedium, maxLines = 2)
            Text(
                "${formatBytes(r.source?.sizeBytes ?: 0)} · ${if ((r.source?.durationMs ?: 0) > 0) formatDuration(r.source!!.durationMs) else "—"} · " +
                    if (j.cloud) "معالجة سحابية" else "معالجة محلية",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val label = (if (j.paused && !stage.isTerminal) "متوقفة مؤقتًا — " else "") + stageLabel(j.stage)
            Text(label, style = MaterialTheme.typography.bodyLarge,
                color = if (stage == JobStage.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            if (!stage.isTerminal) LinearProgressIndicator(progress = { j.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text("${(j.progress * 100).toInt()}% · متحدثون: ${j.speakersFound} · كلمات مكتشفة: ${j.wordsFound} · جديدة: ${j.newWords} · مكررة مُتجاوَزة: ${j.duplicatesSkipped}",
                style = MaterialTheme.typography.bodySmall)
            if (j.lastError != null) Text(j.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    stage == JobStage.AWAITING_SPEAKER_REVIEW -> Button(onClick = onSpeakers) { Text("مراجعة المتحدثين") }
                    stage == JobStage.FAILED -> {
                        Button(onClick = { vm.retry(j.id) }) { Text("إعادة المحاولة") }
                        TextButton(onClick = { confirmDelete = true }) { Text("حذف") }
                    }
                    stage.isTerminal -> {
                        if (j.needsReview > 0) FilledTonalButton(onClick = onReviews) { Text("مراجعة (${j.needsReview})") }
                        TextButton(onClick = { confirmDelete = true }) { Text("إزالة من القائمة") }
                    }
                    j.paused -> {
                        Button(onClick = { vm.resume(j.id) }) { Text("استئناف") }
                        TextButton(onClick = { confirmCancel = true }) { Text("إلغاء") }
                    }
                    else -> {
                        FilledTonalButton(onClick = { vm.pause(j.id) }) { Text("إيقاف مؤقت") }
                        TextButton(onClick = { confirmCancel = true }) { Text("إلغاء") }
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, "التفاصيل")
                }
            }

            if (expanded) {
                HorizontalDivider()
                Detail("المرحلة الحالية", stageLabel(j.stage))
                Detail("عدد المتحدثين المكتشفين", j.speakersFound.toString())
                Detail("عدد الكلمات المكتشفة", j.wordsFound.toString())
                Detail("عدد الكلمات الجديدة", j.newWords.toString())
                Detail("الكلمات المكررة التي تم تجاوزها", j.duplicatesSkipped.toString())
                Detail("المقاطع التي تحتاج إلى مراجعة", j.needsReview.toString())
                Detail("الملفات المحفوظة بنجاح", j.filesSaved.toString())
                Detail("عدد الأخطاء", j.errors.toString())
                Detail("وقت البدء", formatDate(j.startedAt))
                j.finishedAt?.let { Detail("وقت الانتهاء", formatDate(it)) }
                if (j.warnings.isNotBlank()) {
                    Text("تحذيرات", style = MaterialTheme.typography.labelLarge)
                    Text(j.warnings, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (confirmDelete) ConfirmDialog("إزالة المهمة", "تُزال المهمة وبيانات تحليلها المؤقتة. لا تُحذف الكلمات المحفوظة في المكتبات.", "إزالة",
        { vm.delete(j.id) }, { confirmDelete = false })
    if (confirmCancel) ConfirmDialog("إلغاء المهمة", "ستتوقف المعالجة. تبقى الكلمات التي أُضيفت سابقًا.", "إلغاء المهمة",
        { vm.cancel(j.id) }, { confirmCancel = false })
}

@Composable
private fun Detail(k: String, v: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, style = MaterialTheme.typography.bodySmall)
    }
}
