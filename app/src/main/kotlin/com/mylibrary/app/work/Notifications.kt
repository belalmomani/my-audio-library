package com.mylibrary.app.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mylibrary.app.MainActivity
import com.mylibrary.app.R

object Notifications {
    const val CHANNEL = "processing"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.notif_channel_processing), NotificationManager.IMPORTANCE_LOW))
        }
    }

    fun progress(ctx: Context, text: String): android.app.Notification {
        ensureChannel(ctx)
        return NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(ctx.getString(R.string.notif_processing_title))
            .setContentText(text).setOngoing(true).setOnlyAlertOnce(true).build()
    }

    fun reviewReady(ctx: Context, jobId: String) {
        ensureChannel(ctx)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).putExtra("jobId", jobId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("التحليل جاهز").setContentText("راجع المتحدثين ثم اعتمد استخراج الكلمات")
            .setContentIntent(pi).setAutoCancel(true).build()
        NotificationManagerCompat.from(ctx).notify(jobId.hashCode(), n)
    }
}
