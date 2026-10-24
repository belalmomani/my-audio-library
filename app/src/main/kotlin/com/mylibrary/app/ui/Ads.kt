package com.mylibrary.app.ui

import android.app.Application
import android.content.Context
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.mylibrary.app.BuildConfig
import com.mylibrary.app.di.ServiceLocator
import com.startapp.sdk.adsbase.StartAppSDK
import com.startapp.sdk.ads.banner.Banner

/**
 * إعلانات Start.io. لا يعتمد التطبيق عليها: بلا معرّف حقيقي أو بتعطيلها من الإعدادات لا يُحمَّل شيء.
 * الإعلان يوضع في مساحة ثابتة أسفل الشاشة (خارج محتوى التطبيق وأزرار التشغيل/الحفظ/التصدير).
 */
object Ads {
    @Volatile private var initialized = false

    val isConfigured: Boolean
        get() = BuildConfig.STARTIO_APP_ID.isNotBlank() && !BuildConfig.STARTIO_APP_ID.startsWith("REPLACE")

    fun init(app: Application) {
        if (!isConfigured || initialized) return
        runCatching {
            StartAppSDK.init(app, BuildConfig.STARTIO_APP_ID, false) // false: بلا إعلان الغلاف التلقائي
            StartAppSDK.setTestAdsEnabled(BuildConfig.DEBUG)
            applyConsent(app)
            initialized = true
        }
    }

    /** الإعلانات المخصّصة تتطلب موافقة صريحة؛ الافتراضي «لا». */
    fun applyConsent(ctx: Context) {
        runCatching { StartAppSDK.setUserConsent(ctx, "pas", System.currentTimeMillis(), ServiceLocator.settings.current.personalizedAds) }
    }
}

@Composable
fun AdBanner(processing: Boolean, modifier: Modifier = Modifier) {
    val s by ServiceLocator.settings.flow.collectAsState()
    if (!Ads.isConfigured || !s.adsEnabled || (processing && !s.adsDuringProcessing)) return
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            Banner(ctx).apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        },
    )
}
