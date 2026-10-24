package com.mylibrary.app.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.mylibrary.app.di.ServiceLocator
import com.mylibrary.core.NetworkUnavailableException
import kotlinx.coroutines.CancellationException

class ProcessingWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private fun info(): ForegroundInfo {
        val n = Notifications.progress(applicationContext, "قد تستغرق التسجيلات الطويلة عدة دقائق")
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(1001, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(1001, n)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = info()

    override suspend fun doWork(): Result {
        val jobId = inputData.getString("jobId") ?: return Result.failure()
        val phase = inputData.getString("phase") ?: "PROCESS"
        runCatching { setForeground(info()) } // قد يُرفض في الخلفية على بعض الإصدارات؛ العمل يستمر بدونه
        val runner = ServiceLocator.runner
        return try {
            if (phase == "EXTRACT") runner.extract(jobId) { isStopped } else runner.process(jobId) { isStopped }
            Result.success()
        } catch (e: StopException) {
            Result.success()
        } catch (e: NetworkUnavailableException) {
            // لا فشل: تبقى الحالة وتُستأنف تلقائيًا عند عودة الشبكة
            ServiceLocator.db.jobs().setLastError(jobId, "لا يوجد اتصال بالإنترنت — ستُستأنف المهمة تلقائيًا")
            Result.retry()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            runner.fail(jobId, e)
            Result.failure()
        }
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val sum = ServiceLocator.sync.syncPending { !isStopped }
        return if (sum.stoppedOffline) Result.retry() else Result.success()
    }
}
