package com.mylibrary.app.ui

import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** أساس كل ViewModel: رسائل للمستخدم + تشغيل آمن للمهام (أي استثناء يظهر رسالةً بدل إسقاط التطبيق). */
open class BaseVm : ViewModel() {
    private val _msg = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: Flow<String> get() = _msg

    fun say(s: String) { _msg.tryEmit(s) }

    protected fun launchSafe(block: suspend CoroutineScope.() -> Unit): Job = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            say(e.message ?: "حدث خطأ غير متوقع")
        }
    }
}

@Composable
inline fun <reified T : ViewModel> rememberVm(key: String? = null, crossinline make: () -> T): T =
    viewModel(key = key, factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <V : ViewModel> create(modelClass: Class<V>): V = make() as V
    })

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    messages: Flow<String>? = null,
    showAds: Boolean = true,
    processing: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    fab: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val snack = remember { SnackbarHostState() }
    if (messages != null) {
        LaunchedEffect(messages) { messages.collect { snack.showSnackbar(it) } }
    }
    Scaffold(
        topBar = { AppTopBar(title, onBack, actions) },
        snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = fab,
        // الإعلان في شريط سفلي ثابت خارج المحتوى فلا يغطي أزرار التشغيل أو الحفظ أو التصدير
        bottomBar = { if (showAds) AdBanner(processing) },
        content = content,
    )
}

private val ORDINALS = listOf("", "الأول", "الثاني", "الثالث", "الرابع", "الخامس", "السادس", "السابع", "الثامن", "التاسع", "العاشر")
fun ordinalAr(n: Int): String = ORDINALS.getOrNull(n)?.takeIf { it.isNotEmpty() } ?: n.toString()

fun formatDate(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

fun percent(v: Double): String = "${(v * 100).toInt().coerceIn(0, 100)}%"

/** معاينة صوتية للملف المختار قبل التحليل (مشغّل النظام؛ يعمل مع الفيديو أيضًا). */
@Composable
fun PreviewButton(uri: Uri) {
    val ctx = LocalContext.current
    var playing by remember(uri) { mutableStateOf(false) }
    var player by remember(uri) { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(uri) { onDispose { runCatching { player?.release() }; player = null } }
    IconButton(onClick = {
        runCatching {
            val p = player
            if (playing) {
                p?.pause(); playing = false
            } else if (p != null) {
                p.start(); playing = true
            } else {
                val n = MediaPlayer()
                n.setDataSource(ctx, uri)
                n.setOnCompletionListener { playing = false; it.seekTo(0) }
                n.setOnPreparedListener { it.start() }
                player = n
                n.prepareAsync()
                playing = true
            }
        }.onFailure { playing = false }
    }) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "إيقاف" else "تشغيل") }
}
