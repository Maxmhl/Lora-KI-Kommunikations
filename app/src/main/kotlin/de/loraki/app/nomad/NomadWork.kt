package de.loraki.app.nomad

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.loraki.app.App
import de.loraki.app.data.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.TimeUnit

object NomadWork {
    const val DOWNLOAD = "nomad-download"
    const val SYNC = "nomad-sync"
    private const val CH = "nomad"

    fun ensureChannel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH, "Nomad-Downloads", NotificationManager.IMPORTANCE_LOW))
    }

    private fun constraints(s: Settings) = Constraints.Builder()
        .setRequiredNetworkType(if (s.nomadWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresStorageNotLow(true)
        .build()

    /** Startet den Download der gewählten Serien (Reihenfolge: Kartenstil zuerst). Läuft als Vordergrunddienst weiter. */
    fun download(ctx: Context, s: Settings, series: List<String>) {
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(Data.Builder().putStringArray("series", series.toTypedArray()).build())
            .setConstraints(constraints(s))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(DOWNLOAD, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    fun cancelDownloads(ctx: Context) { WorkManager.getInstance(ctx).cancelUniqueWork(DOWNLOAD) }

    /** Richtet die regelmäßige Update-Prüfung ein (oder entfernt sie bei „nie“). */
    fun scheduleSync(ctx: Context, s: Settings) {
        val wm = WorkManager.getInstance(ctx)
        val days = s.nomadIntervalDays
        if (days <= 0 || s.nomadUrl.isBlank()) { wm.cancelUniqueWork(SYNC); return }
        val req = PeriodicWorkRequestBuilder<SyncWorker>(days.toLong(), TimeUnit.DAYS, 6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (s.nomadWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(SYNC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** Einmalige Prüfung (z. B. wenn die letzte länger als das Intervall zurückliegt). */
    fun syncNow(ctx: Context, s: Settings) {
        WorkManager.getInstance(ctx).enqueueUniqueWork("nomad-sync-now", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints(s)).build())
    }

    @android.annotation.SuppressLint("MissingPermission") // POST_NOTIFICATIONS kann abgelehnt sein; notify() wirft dann nicht, die Meldung entfällt
    fun notify(ctx: Context, id: Int, title: String, text: String) {
        val n = NotificationCompat.Builder(ctx, CH).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title).setContentText(text).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }

    fun progressNotification(ctx: Context, text: String, done: Long, total: Long) =
        NotificationCompat.Builder(ctx, CH).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Nomad-Download").setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(1000, if (total > 0) (done * 1000 / total).toInt() else 0, total <= 0).build()
}

class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val repo get() = (applicationContext as App).graph.nomad

    override suspend fun getForegroundInfo() = foreground("Starte …", 0, 0)

    private fun foreground(text: String, done: Long, total: Long): ForegroundInfo {
        NomadWork.ensureChannel(applicationContext)
        val n = NomadWork.progressNotification(applicationContext, text, done, total)
        return if (android.os.Build.VERSION.SDK_INT >= 29) ForegroundInfo(3, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(3, n)
    }

    override suspend fun doWork(): Result {
        val wanted = inputData.getStringArray("series")?.toList() ?: return Result.failure()
        val manifest = repo.manifest.value ?: try { repo.refresh() } catch (e: IOException) { return Result.retry() }
        val items = wanted.mapNotNull { s -> manifest.items.firstOrNull { it.series == s } }
            .sortedBy { if (it.kind == "mapassets") 0 else 1 }
        runCatching { setForeground(foreground("Starte …", 0, 0)) }
        return coroutineScope {
            var current = ""
            val ticker: Job = launch {
                while (true) {
                    delay(1000)
                    val p = repo.progress.value.entries.firstOrNull() ?: continue
                    runCatching { setForeground(foreground(current, p.value.done, p.value.total)) }
                }
            }
            try {
                for (item in items) {
                    current = "${item.name} (${item.version})"
                    repo.download(item)
                }
                NomadWork.notify(applicationContext, 4, "Nomad", "${items.size} Download(s) abgeschlossen")
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                repo.message.value = e.message
                if (runAttemptCount < 6) Result.retry() else Result.failure()
            } finally { ticker.cancel() }
        }
    }
}

/** Periodische Prüfung: neue Versionen der gewählten Inhalte melden oder (optional) automatisch laden. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val g = (applicationContext as App).graph
        try { g.nomad.refresh() } catch (e: IOException) { return if (runAttemptCount < 3) Result.retry() else Result.success() }
        val plan = g.nomad.plan()
        if (plan.update.isEmpty()) return Result.success()
        if (g.settings.nomadAutoDownload) {
            NomadWork.download(applicationContext, g.settings, plan.update.map { it.series })
        } else {
            NomadWork.notify(applicationContext, 5, "Nomad: Updates verfügbar",
                plan.update.joinToString { "${it.name} ${it.version}" } + " – in der Bibliothek aktualisieren")
        }
        return Result.success()
    }
}
