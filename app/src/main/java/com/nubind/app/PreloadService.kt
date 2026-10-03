package com.nubind.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nubind.app.root.PreloadStatus
import com.nubind.app.root.PreloadStatusParser
import com.nubind.app.root.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Notificación persistente con el progreso de la precarga.
 *
 * preload.sh corre como proceso root independiente de la app; este servicio en
 * primer plano solo lo observa (lee preload_status.json) y refleja el avance en
 * la barra de notificaciones, así se sigue viendo con la app cerrada. Se detiene
 * solo cuando la precarga termina, se corta o nunca llega a arrancar.
 * Es idempotente: llamar a [start] con el servicio ya activo no hace nada.
 */
class PreloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        // Hay que llamar a startForeground enseguida (límite de 5 s tras startForegroundService).
        ServiceCompat.startForeground(
            this, NOTIF_ID, progressNotification(this, null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        if (job?.isActive != true) job = scope.launch { track() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun CoroutineScope.track() {
        val nm = getSystemService(NotificationManager::class.java)
        val startDeadline = SystemClock.elapsedRealtime() + START_GRACE_MS
        val hardDeadline = SystemClock.elapsedRealtime() + HARD_TIMEOUT_MS
        var everRunning = false
        var lastKey: String? = null
        while (isActive) {
            val status = PreloadStatusParser.parse(RootShell.preloadStatus())
            val now = SystemClock.elapsedRealtime()
            when {
                status?.running == true -> {
                    everRunning = true
                    val key = "${status.doneMb}/${status.selectedMb}/${status.doneFiles}/${status.selectedFiles}"
                    if (key != lastKey) {
                        lastKey = key
                        nm.notify(NOTIF_ID, progressNotification(this@PreloadService, status))
                    }
                }
                everRunning -> {
                    finish(nm, status)
                    return
                }
                now > startDeadline -> {
                    // Nunca arrancó (perfil que no precarga, o el script falló antes de escribir nada).
                    finish(nm, null)
                    return
                }
            }
            if (now > hardDeadline) {
                finish(nm, null)
                return
            }
            delay(POLL_MS)
        }
    }

    /** Quita la notificación en curso y, si hay un resultado que mostrar, deja un aviso descartable. */
    private fun finish(nm: NotificationManager, status: PreloadStatus?) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (status != null && status.selectedFiles > 0) nm.notify(DONE_ID, doneNotification(this, status))
        stopSelf()
    }

    companion object {
        private const val CHANNEL_ID = "preload"
        private const val NOTIF_ID = 7101
        private const val DONE_ID = 7102
        private const val POLL_MS = 2000L
        // Igual que BindViewModel: recorrer un remoto grande antes de la primera escritura puede tardar.
        private const val START_GRACE_MS = 60_000L
        private const val HARD_TIMEOUT_MS = 12 * 60 * 60_000L

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, PreloadService::class.java))
            } catch (_: Exception) {
                // Android 12+ puede negar arrancar un servicio en primer plano desde segundo plano.
            }
        }

        private fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.precarga_de_archivos),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }

        private fun openAppIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        private fun detail(ctx: Context, s: PreloadStatus) =
            ctx.getString(R.string.mb_archivos, s.doneMb, s.selectedMb, s.doneFiles, s.selectedFiles)

        private fun progressNotification(ctx: Context, s: PreloadStatus?): Notification {
            val pct = s?.let { (it.fraction * 100).toInt() }
            val b = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif_preload)
                .setContentTitle(
                    if (pct != null) "${ctx.getString(R.string.precargando)} $pct%"
                    else ctx.getString(R.string.precargando)
                )
                .setContentText(if (s != null) detail(ctx, s) else ctx.getString(R.string.preload_scanning))
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(openAppIntent(ctx))
            if (s != null && s.remote.isNotBlank()) b.setSubText(s.remote)
            if (pct != null && s != null && s.selectedMb > 0) b.setProgress(100, pct, false)
            else b.setProgress(0, 0, true)
            return b.build()
        }

        private fun doneNotification(ctx: Context, s: PreloadStatus): Notification {
            val state = ctx.getString(if (s.finished) R.string.listo else R.string.incompleta)
            val b = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif_preload)
                .setContentTitle(ctx.getString(R.string.precarga_de_archivos))
                .setContentText("$state · ${detail(ctx, s)}")
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setSilent(true)
                .setAutoCancel(true)
                .setTimeoutAfter(60_000L)
                .setContentIntent(openAppIntent(ctx))
            if (s.remote.isNotBlank()) b.setSubText(s.remote)
            return b.build()
        }
    }
}
