package com.mylibrary.app.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AppSettings(
    /** false = معالجة محلية فقط؛ true = يُسمح بخادم المعالجة (بعد موافقة صريحة). */
    val allowCloudProcessing: Boolean = false,
    val syncToCloud: Boolean = false,
    val keepOriginals: Boolean = false,
    val adsEnabled: Boolean = true,
    val adsDuringProcessing: Boolean = false,
    val personalizedAds: Boolean = false,
    val exportFormat: String = "wav",
    val backendUrl: String = "",
)

class SettingsStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(read())
    val flow: StateFlow<AppSettings> = _flow

    val current: AppSettings get() = _flow.value

    private fun read() = AppSettings(
        allowCloudProcessing = sp.getBoolean("cloud", false),
        syncToCloud = sp.getBoolean("sync", false),
        keepOriginals = sp.getBoolean("keepOrig", false),
        adsEnabled = sp.getBoolean("ads", true),
        adsDuringProcessing = sp.getBoolean("adsProc", false),
        personalizedAds = sp.getBoolean("adsPers", false),
        exportFormat = sp.getString("fmt", "wav") ?: "wav",
        backendUrl = sp.getString("backend", "") ?: "",
    )

    fun update(f: (AppSettings) -> AppSettings) {
        val n = f(_flow.value)
        sp.edit {
            putBoolean("cloud", n.allowCloudProcessing); putBoolean("sync", n.syncToCloud)
            putBoolean("keepOrig", n.keepOriginals); putBoolean("ads", n.adsEnabled)
            putBoolean("adsProc", n.adsDuringProcessing); putBoolean("adsPers", n.personalizedAds)
            putString("fmt", n.exportFormat); putString("backend", n.backendUrl)
        }
        _flow.value = n
    }
}
