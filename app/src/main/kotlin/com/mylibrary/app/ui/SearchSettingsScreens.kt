package com.mylibrary.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.mylibrary.app.BuildConfig
import com.mylibrary.app.audio.OggOpusEncoder
import com.mylibrary.app.audio.RangePlayer
import com.mylibrary.app.cloud.AuthResult
import com.mylibrary.app.data.AppSettings
import com.mylibrary.app.data.WordEntity
import com.mylibrary.app.di.ServiceLocator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import java.io.File

// ----------------------------------------------------------------------------- البحث

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchVm : BaseVm() {
    private val library = ServiceLocator.library
    val query = MutableStateFlow("")
    val results = query.debounce(250).mapLatest { library.search(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<WordEntity>())
    val libraryNames = library.summaries().map { l -> l.associate { it.id to it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    val playingId = MutableStateFlow<String?>(null)
    private var job: Job? = null

    fun toggle(w: WordEntity) {
        if (playingId.value == w.id) { job?.cancel(); playingId.value = null; return }
        job?.cancel()
        job = launchSafe {
            playingId.value = w.id
            try { RangePlayer.play(File(w.localPath)) } finally { if (playingId.value == w.id) playingId.value = null }
        }
    }
}

@Composable
fun SearchScreen(nav: NavController) {
    val vm = rememberVm { SearchVm() }
    val q by vm.query.collectAsState()
    val res by vm.results.collectAsState()
    val names by vm.libraryNames.collectAsState()
    val playing by vm.playingId.collectAsState()
    ScreenScaffold(title = "البحث", onBack = { nav.popBackStack() }, messages = vm.messages) { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(
                value = q, onValueChange = { vm.query.value = it }, singleLine = true, label = { Text("ابحث عن كلمة في كل المكتبات") },
                leadingIcon = { Icon(Icons.Filled.Search, null) }, modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            if (q.isNotBlank() && res.isEmpty()) EmptyState("لا نتائج.")
            LazyColumn {
                items(res, key = { it.id }) { w ->
                    ListItem(
                        leadingContent = { FilledTonalIconButton(onClick = { vm.toggle(w) }) { Icon(if (playing == w.id) Icons.Filled.Stop else Icons.Filled.PlayArrow, "تشغيل") } },
                        headlineContent = { Text(w.text) },
                        supportingContent = { Text("مكتبة: ${names[w.libraryId] ?: "—"} · %.2f ث".format(w.durationMs / 1000.0)) },
                        modifier = Modifier.clickable { nav.navigate("library/${w.libraryId}") },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------- الإعدادات

class SettingsVm : BaseVm() {
    private val settings = ServiceLocator.settings
    val state = settings.flow
    val session = ServiceLocator.auth.session
    val unsynced = ServiceLocator.db.words().observeUnsynced().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val busy = MutableStateFlow(false)

    fun update(f: (AppSettings) -> AppSettings) {
        settings.update(f)
        com.mylibrary.app.ui.Ads.applyConsent(ServiceLocator.app)
        if (settings.current.syncToCloud) ServiceLocator.jobs.enqueueSync()
    }

    fun saveBackend(url: String): Boolean {
        val u = url.trim().trimEnd('/')
        if (u.isNotEmpty() && !u.startsWith("https://")) { say("العنوان يجب أن يبدأ بـ https://"); return false }
        update { it.copy(backendUrl = u) }
        say("تم حفظ عنوان الخادم")
        return true
    }

    fun cleanTemp() = launchSafe {
        val r = ServiceLocator.library.cleanTemporaryFiles()
        say("حُرّرت ${formatBytes(r.freedBytes)}" + if (r.keptForReview > 0) " — أُبقي ${r.keptForReview} ملف لأن مقاطعه ما زالت بانتظار المراجعة" else "")
    }

    fun retryFailedUploads() = launchSafe {
        ServiceLocator.db.words().resetFailed()
        ServiceLocator.jobs.enqueueSync()
        say("أُعيدت جدولة الرفع")
    }

    fun restore() = launchSafe {
        busy.value = true
        try {
            val n = ServiceLocator.sync.restore()
            say(if (n == 0) "لا توجد كلمات جديدة للاستعادة" else "استُعيدت $n كلمة")
        } finally { busy.value = false }
    }

    fun signOut() = launchSafe { ServiceLocator.auth.signOut(); say("تم تسجيل الخروج") }

    fun deleteAccount() = launchSafe {
        busy.value = true
        try {
            ServiceLocator.sync.deleteAccountRemote()
            update { it.copy(syncToCloud = false, allowCloudProcessing = false) }
            say("حُذف الحساب وكل بياناته السحابية. بياناتك المحلية باقية على هذا الجهاز.")
        } finally { busy.value = false }
    }
}

@Composable
fun SettingsScreen(nav: NavController) {
    val vm = rememberVm { SettingsVm() }
    val s by vm.state.collectAsState()
    val session by vm.session.collectAsState()
    val unsynced by vm.unsynced.collectAsState()
    val busy by vm.busy.collectAsState()
    val ctx = LocalContext.current
    var backend by remember(s.backendUrl) { mutableStateOf(s.backendUrl) }
    var consentCloud by remember { mutableStateOf(false) }
    var deleteAcct by remember { mutableStateOf(false) }
    var privacy by remember { mutableStateOf(false) }

    ScreenScaffold(title = "الإعدادات", onBack = { nav.popBackStack() }, messages = vm.messages) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            SectionTitle("اللغة")
            Text("العربية (الافتراضية). بنية النصوص جاهزة لإضافة لغات أخرى لاحقًا.", style = MaterialTheme.typography.bodySmall)

            SectionTitle("الحساب والتخزين السحابي")
            if (session == null) {
                Text("غير مسجّل الدخول. التطبيق يعمل محليًا بالكامل بدون حساب.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { nav.navigate(Routes.AUTH) }) { Text("تسجيل الدخول / إنشاء حساب") }
            } else {
                Text(session!!.email, style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.signOut() }) { Text("تسجيل الخروج") }
                    OutlinedButton(onClick = { deleteAcct = true }) { Text("حذف الحساب وبياناته") }
                }
            }
            SwitchRow("مزامنة الكلمات إلى السحابة (Supabase + R2)", "لا يُرفع شيء قبل تفعيل هذا الخيار وتسجيل الدخول. الملفات الخاصة بروابط موقّعة مؤقتة.",
                s.syncToCloud, enabled = session != null) { vm.update { c -> c.copy(syncToCloud = it) } }
            if (s.syncToCloud) Text("بانتظار الرفع/غير متزامنة: $unsynced كلمة", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.retryFailedUploads() }, enabled = session != null && s.syncToCloud) { Text("إعادة محاولة الرفع") }
                OutlinedButton(onClick = { vm.restore() }, enabled = session != null && !busy) { Text("استعادة من السحابة") }
            }

            SectionTitle("إعدادات المعالجة")
            SwitchRow("السماح بالمعالجة السحابية", "إن عُطّل فكل المعالجة محلية فقط ولا يُرسل أي تسجيل. حتى عند تفعيله ستُسأل قبل كل رفع.",
                s.allowCloudProcessing) { if (it) consentCloud = true else vm.update { c -> c.copy(allowCloudProcessing = false) } }
            OutlinedTextField(
                value = backend, onValueChange = { backend = it }, singleLine = true, label = { Text("عنوان خادم المعالجة (https)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { vm.saveBackend(backend) }) { Text("حفظ العنوان") }
            Text("تكلفة المعالجة السحابية تتوقف على الخادم الذي تشغّله (انظر docs/server.md).", style = MaterialTheme.typography.bodySmall)

            SectionTitle("سياسة الاحتفاظ بالتسجيلات الأصلية")
            SwitchRow("الاحتفاظ بنسخة من المصدر عند الاستيراد", "افتراضي لخيار شاشة الاستيراد؛ النسخ تشغل مساحة.", s.keepOriginals) { vm.update { c -> c.copy(keepOriginals = it) } }

            SectionTitle("إدارة التنزيلات والملفات المؤقتة")
            Text("يحذف ملفات الصوت المفكوكة والنسخ المعالجة فقط؛ لا يمس الكلمات المعتمدة في المكتبات.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { vm.cleanTemp() }) { Text("حذف الملفات المؤقتة") }

            SectionTitle("التصدير")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(s.exportFormat == "wav", { vm.update { c -> c.copy(exportFormat = "wav") } }); Text("WAV")
                Spacer(Modifier.width(16.dp))
                val ogg = remember { OggOpusEncoder.isSupported() }
                RadioButton(s.exportFormat == "ogg", { vm.update { c -> c.copy(exportFormat = "ogg") } }, enabled = ogg); Text(if (ogg) "OGG" else "OGG (غير متاح)")
            }

            SectionTitle("الإعلانات (Start.io)")
            if (!Ads.isConfigured) Text("لم يُضبط معرّف التطبيق في هذا الإصدار، فلا تظهر إعلانات. انظر docs/startio.md.", style = MaterialTheme.typography.bodySmall)
            SwitchRow("إظهار الإعلانات", "شريط ثابت أسفل الشاشة لا يغطي أزرار التشغيل والحفظ والتصدير.", s.adsEnabled) { vm.update { c -> c.copy(adsEnabled = it) } }
            SwitchRow("إظهار الإعلانات أثناء المعالجة", "مُعطّل افتراضيًا.", s.adsDuringProcessing) { vm.update { c -> c.copy(adsDuringProcessing = it) } }
            SwitchRow("إعلانات مخصّصة", "موافقة صريحة؛ الافتراضي «لا».", s.personalizedAds) { vm.update { c -> c.copy(personalizedAds = it) } }

            SectionTitle("معلومات التطبيق")
            Text("مكتبتي الصوتية — الإصدار 1.0.0", style = MaterialTheme.typography.bodySmall)
            Text("قاعدة التطابق: v1 (إزالة علامات الترقيم والتشكيل فقط)", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                if (BuildConfig.PRIVACY_POLICY_URL.isNotBlank()) ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_POLICY_URL)))
                else privacy = true
            }) { Text("سياسة الخصوصية") }
        }
    }

    if (consentCloud) ConfirmDialog(
        "السماح بالمعالجة السحابية؟",
        "عند الاستيراد ستختار المعالجة السحابية بنفسك لكل دفعة، وعندها يُرفع التسجيل إلى خادم المعالجة الذي أعددتَه عبر HTTPS. لا يُرفع شيء تلقائيًا.",
        "السماح", { vm.update { c -> c.copy(allowCloudProcessing = true) } }, { consentCloud = false },
    )
    if (deleteAcct) ConfirmDialog(
        "حذف الحساب؟", "سيُحذف حسابك وكل مكتباتك وملفاتك من السحابة نهائيًا. نسختك المحلية على الجهاز تبقى. لا يمكن التراجع.",
        "حذف نهائيًا", { vm.deleteAccount() }, { deleteAcct = false },
    )
    if (privacy) AlertDialog(
        onDismissRequest = { privacy = false }, title = { Text("سياسة الخصوصية") },
        text = { Text("لم يُضبط رابط سياسة الخصوصية بعد. ضع الرابط في PRIVACY_POLICY_URL قبل النشر (انظر docs/privacy-policy.template.md).") },
        confirmButton = { TextButton(onClick = { privacy = false }) { Text("حسنًا") } },
    )
}

@Composable
private fun SectionTitle(t: String) {
    Spacer(Modifier.height(8.dp))
    Text(t, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    HorizontalDivider()
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, enabled = enabled)
    }
}

// ----------------------------------------------------------------------------- تسجيل الدخول

class AuthVm : BaseVm() {
    val busy = MutableStateFlow(false)
    val done = MutableStateFlow(false)

    fun submit(email: String, password: String, signUp: Boolean) = launchSafe {
        if (email.isBlank() || password.length < 8) { say("أدخل بريدًا صحيحًا وكلمة مرور من 8 أحرف على الأقل"); return@launchSafe }
        busy.value = true
        try {
            val auth = ServiceLocator.auth
            when (val r = if (signUp) auth.signUp(email.trim(), password) else auth.signIn(email.trim(), password)) {
                is AuthResult.Ok -> done.value = true
                AuthResult.ConfirmEmail -> say("أُرسل رابط التأكيد إلى بريدك. أكّده ثم سجّل الدخول.")
                is AuthResult.Error -> say(r.message)
            }
        } finally { busy.value = false }
    }
}

@Composable
fun AuthScreen(nav: NavController) {
    val vm = rememberVm { AuthVm() }
    val busy by vm.busy.collectAsState()
    val done by vm.done.collectAsState()
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var signUp by remember { mutableStateOf(false) }
    LaunchedEffect(done) { if (done) nav.popBackStack() }
    ScreenScaffold(title = if (signUp) "إنشاء حساب" else "تسجيل الدخول", onBack = { nav.popBackStack() }, messages = vm.messages) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(email, { email = it }, label = { Text("البريد الإلكتروني") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, { pass = it }, label = { Text("كلمة المرور (8 أحرف على الأقل)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.submit(email, pass, signUp) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(if (busy) "جارٍ…" else if (signUp) "إنشاء الحساب" else "دخول")
            }
            TextButton(onClick = { signUp = !signUp }) { Text(if (signUp) "لدي حساب — تسجيل الدخول" else "ليس لدي حساب — إنشاء حساب") }
            Text("الحساب اختياري ولازم فقط للمزامنة السحابية والمعالجة على الخادم. ستُحفظ كلمة المرور لدى Supabase ولا تُسجَّل في التطبيق.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
