package com.mylibrary.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mylibrary.core.JobStage
import java.text.DecimalFormat

fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> DecimalFormat("0.0").format(b / 1024.0) + " KB"
    b < 1024L * 1024 * 1024 -> DecimalFormat("0.0").format(b / 1048576.0) + " MB"
    else -> DecimalFormat("0.00").format(b / 1073741824.0) + " GB"
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun stageLabel(stage: String): String = when (stage) {
    JobStage.QUEUED.name -> "في انتظار المعالجة"
    JobStage.EXTRACTING_AUDIO.name -> "جارٍ استخراج الصوت"
    JobStage.SEPARATING.name -> "جارٍ فصل الموسيقى"
    JobStage.DIARIZING.name -> "جارٍ تحديد المتحدثين"
    JobStage.TRANSCRIBING.name -> "جارٍ التعرف على الكلام"
    JobStage.AWAITING_SPEAKER_REVIEW.name -> "بانتظار مراجعة المتحدثين"
    JobStage.EXTRACTING_WORDS.name -> "جارٍ استخراج الكلمات"
    JobStage.UPLOADING.name -> "جارٍ رفع الملفات"
    JobStage.COMPLETED.name -> "اكتملت المعالجة"
    JobStage.COMPLETED_WITH_WARNINGS.name -> "اكتملت المعالجة مع تحذيرات"
    JobStage.FAILED.name -> "فشلت المعالجة"
    JobStage.CANCELED.name -> "أُلغيت المهمة"
    else -> stage
}

fun reasonLabel(reason: String): String = reason.split(",").joinToString("، ") {
    when (it) {
        "NO_TEXT" -> "نص غير صالح"
        "NO_CONFIDENCE" -> "لا توجد ثقة من النموذج"
        "LOW_CONFIDENCE" -> "ثقة التعرف منخفضة"
        "LOW_QUALITY" -> "جودة الصوت منخفضة"
        "OVERLAP" -> "تداخل مع متحدث آخر"
        "TOO_SHORT" -> "مقطع قصير جدًا"
        "TOO_LONG" -> "مقطع طويل جدًا"
        "NO_SPEAKER" -> "لم يُحدَّد المتحدث"
        "SUGGEST_REPLACE" -> "مقطع أفضل متاح — اقتراح استبدال"
        else -> it
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") } },
        actions = actions,
    )
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String = "تأكيد", onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

@Composable
fun TextInputDialog(title: String, initial: String = "", label: String, onOk: (String) -> Unit, onDismiss: () -> Unit) {
    var v = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(value = v.value, onValueChange = { v.value = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onOk(v.value); onDismiss() }) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}
