package com.brayan.videosplitter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.transformer.ExportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** État partagé entre le service (qui travaille) et l'écran (qui affiche). */
data class SplitUi(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val pct: Int = 0,
    val error: String? = null,
    val results: List<Uri> = emptyList(),
    val finished: Boolean = false
)

object SplitState {
    val flow = MutableStateFlow(SplitUi())
}

class SplitService : Service() {

    companion object {
        const val ACTION_CANCEL = "com.brayan.videosplitter.CANCEL"
        const val EXTRA_URI = "uri"
        const val EXTRA_SEG_MS = "segMs"
        const val EXTRA_PARTS = "parts"
        const val EXTRA_DURATION_MS = "durationMs"
        const val EXTRA_NAME = "name"
        private const val CH_PROGRESS = "split_progress"
        private const val CH_DONE = "split_done"
        private const val NOTIF_PROGRESS = 1
        private const val NOTIF_DONE = 2
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            val j = job
            if (j != null) j.cancel() else stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY

        val uriStr = intent?.getStringExtra(EXTRA_URI)
        if (uriStr == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val uri = Uri.parse(uriStr)
        val segMs = intent.getLongExtra(EXTRA_SEG_MS, 0L)
        val parts = intent.getIntExtra(EXTRA_PARTS, 0)
        val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
        val name = intent.getStringExtra(EXTRA_NAME) ?: "video"

        createChannels()
        ServiceCompat.startForeground(
            this, NOTIF_PROGRESS,
            buildProgress("Préparation…", 0, true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "decoupeur:split").apply {
            acquire(6 * 60 * 60 * 1000L)
        }

        job = scope.launch {
            val results = ArrayList<Uri>()
            SplitState.flow.value = SplitUi(running = true, total = parts)
            try {
                VideoSplitter.split(
                    this@SplitService, uri, Plan(segMs, parts), durationMs, name, results
                ) { d, t, p ->
                    SplitState.flow.value = SplitUi(running = true, done = d, total = t, pct = p)
                    val overall = ((d * 100 + p) / t).coerceIn(0, 100)
                    val shown = minOf(d + 1, t)
                    notifyProgress("Partie $shown / $t · $p %", overall)
                }
                SplitState.flow.value =
                    SplitUi(done = parts, total = parts, results = results, finished = true)
                notifyDone(results.size)
            } catch (e: CancellationException) {
                SplitState.flow.value = SplitUi(
                    error = "Découpe annulée. ${results.size} partie(s) déjà enregistrée(s).",
                    results = results
                )
            } catch (e: Exception) {
                val msg = (e as? ExportException)?.let { "${it.errorCodeName}" } ?: e.message
                SplitState.flow.value = SplitUi(
                    error = "Erreur : $msg (${results.size} partie(s) enregistrée(s))",
                    results = results
                )
            } finally {
                try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Découpe en cours", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Découpe terminée", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        this, 1,
        Intent(this, SplitService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildProgress(text: String, pct: Int, indeterminate: Boolean) =
        NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Découpe en cours")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, pct, indeterminate)
            .setContentIntent(openAppIntent())
            .addAction(0, "Annuler", cancelIntent())
            .build()

    private fun notifyProgress(text: String, pct: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_PROGRESS, buildProgress(text, pct, false))
    }

    private fun notifyDone(count: Int) {
        val n = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Découpe terminée")
            .setContentText("$count partie(s) dans Films/DecoupeurVideo. Touche pour partager.")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_DONE, n)
    }
}
