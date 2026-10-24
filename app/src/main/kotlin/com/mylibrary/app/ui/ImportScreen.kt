package com.mylibrary.app.ui

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mylibrary.app.di.ServiceLocator
import com.mylibrary.app.work.ImportOptions
import com.mylibrary.app.work.PickedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

data class PickedInfo(val file: PickedFile, val durationMs: Long)

class ImportVm : BaseVm() {
    private val app get() = ServiceLocator.app
    private val jobs get() = ServiceLocator.jobs
    val picked = MutableStateFlow<List<PickedInfo>>(emptyList())
    val keepOriginal = MutableStateFlow(ServiceLocator.settings.current.keepOriginals)
    val separate = MutableStateFlow(false)
    val diarize = MutableStateFlow(true)
    val cloud = MutableStateFlow(false)
    val starting = MutableStateFlow(false)

    val cloudAvailable: Boolean
        get() = ServiceLocator.settings.current.allowCloudProcessing && ServiceLocator.backend.isConfigured &&
            ServiceLocator.auth.session.value != null
    val localModelInstalled: Boolean get() = ServiceLocator.localSpeechModel != null
    val localDiarizerInstalled: Boolean get() = ServiceLocator.localDiarizer != null

    fun add(uris: List<Uri>) {
        launchSafe {
            val infos = withContext(Dispatchers.IO) {
                uris.map { u ->
                    jobs.persistRead(u)
                    PickedInfo(jobs.describe(u), duration(u))
                }
            }
            picked.value = (picked.value + infos).distinctBy { it.file.uri }
        }
    }

    fun remove(info: PickedInfo) { picked.value = picked.value - info }

    fun setCloud(on: Boolean) {
        cloud.value = on
        if (!on) separate.value = false
    }

    fun start(onDone: () -> Unit) {
        launchSafe {
            starting.value = true
            try {
                jobs.start(
                    picked.value.map { it.file },
                    ImportOptions(
                        cloud = cloud.value, separate = separate.value,
                        diarize = diarize.value && (cloud.value || localDiarizerInstalled),
                        keepOriginal = keepOriginal.value,
                    ),
                )
                onDone()
            } finally {
                starting.value = false
            }
        }
    }

    private fun duration(u: Uri): Long = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(app, u)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        } finally {
            r.release()
        }
    }.getOrDefault(0L)
}

@Composable
fun ImportScreen(nav: NavController, video: Boolean) {
    val vm = rememberVm { ImportVm() }
    val picked by vm.picked.collectAsState()
    val keep by vm.keepOriginal.collectAsState()
    val separate by vm.separate.collectAsState()
    val diarize by vm.diarize.collectAsState()
    val cloud by vm.cloud.collectAsState()
    val starting by vm.starting.collectAsState()
    var askConsent by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.add(it) }
    val mimes = if (video) arrayOf("video/*") else arrayOf("audio/*", "video/mp4")

    ScreenScaffold(title = if (video) "استيراد فيديو" else "استيراد تسجيل", onBack = { nav.popBackStack() }, messages = vm.messages) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { picker.launch(mimes) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.AttachFile, null); Spacer(Modifier.width(8.dp)); Text("اختيار ملف أو عدة ملفات")
            }
            Text("الصيغ المدعومة: MP3 · WAV · M4A · AAC · FLAC · MP4 · MOV (إن أمكن استخراج المسار الصوتي على جهازك).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            picked.forEach { p ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        PreviewButton(p.file.uri)
                        Column(Modifier.weight(1f)) {
                            Text(p.file.name, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            Text(
                                "المدة: ${if (p.durationMs > 0) formatDuration(p.durationMs) else "غير معروفة"} · الحجم: ${formatBytes(p.file.sizeBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(onClick = { vm.remove(p) }) { Icon(Icons.Filled.Close, "إزالة") }
                    }
                }
            }

            HorizontalDivider()
            OptionRow("الاحتفاظ بنسخة من المصدر الأصلي", "تُحفظ داخل التطبيق ويمكن حذفها لاحقًا", keep) { vm.keepOriginal.value = it }
            OptionRow(
                "اكتشاف المتحدثين",
                if (cloud || vm.localDiarizerInstalled) "تُنشأ مكتبة مستقلة لكل صوت"
                else "غير متاح محليًا بدون نموذج؛ سيُعامل التسجيل كمتحدث واحد",
                diarize && (cloud || vm.localDiarizerInstalled), enabled = cloud || vm.localDiarizerInstalled,
            ) { vm.diarize.value = it }
            OptionRow(
                "محاولة عزل الكلام عن الموسيقى",
                if (cloud) "تتم على الخادم؛ قد تبقى آثار من الموسيقى ويمكنك المقارنة قبل الاعتماد"
                else "متاح عند تفعيل المعالجة السحابية فقط",
                separate, enabled = cloud,
            ) { vm.separate.value = it }
            OptionRow(
                "المعالجة السحابية (خادم المعالجة)",
                if (vm.cloudAvailable) "يُرفع التسجيل إلى خادمك بعد موافقتك الآن. قد تترتب تكلفة على موارد الخادم."
                else "غير مفعّلة: فعّلها من الإعدادات، واضبط عنوان الخادم وسجّل الدخول",
                cloud, enabled = vm.cloudAvailable,
            ) { if (it) askConsent = true else vm.setCloud(false) }

            if (!cloud && !vm.localModelInstalled) {
                Text(
                    "المعالجة المحلية: لا يوجد نموذج تعرّف على الكلام مثبّت، فسيُجهَّز الصوت فقط للقص اليدوي من شاشة مراجعة المقاطع. " +
                        "للاستخراج التلقائي فعّل المعالجة السحابية أو ثبّت نموذجًا محليًا (انظر docs/local-models.md).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = { vm.start { nav.navigate(Routes.JOBS) { popUpTo(Routes.HOME) } } },
                enabled = picked.isNotEmpty() && !starting, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text(if (starting) "جارٍ التجهيز…" else "بدء التحليل") }
        }
    }

    if (askConsent) {
        ConfirmDialog(
            title = "رفع التسجيل إلى الخادم؟",
            text = "سيُرفع الصوت الذي اخترته إلى خادم المعالجة الذي أعددتَه (عبر HTTPS) ليُحلَّل ثم تُحذف الملفات المؤقتة وفق سياسة الخادم. " +
                "لن يُرفع أي شيء دون موافقتك. هل توافق على هذه الدفعة؟",
            confirm = "أوافق", onConfirm = { vm.setCloud(true) }, onDismiss = { askConsent = false },
        )
    }
}

@Composable
private fun OptionRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
