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
        // Botón Pausar / Reanudar de la notificación. El servicio ya está en primer
        // plano siguiendo la precarga: solo se cambia la pausa y se refresca el aviso.
        // No se vuelve a llamar a startForeground (mostraría un instante el aviso "vacío").
        val action = intent?.action
        if (action == ACTION_PAUSE || action == ACTION_RESUME) {
            scope.launch {
                if (action == ACTION_PAUSE) RootShell.preloadPause() else RootShell.preloadResume()
                val status = PreloadStatusParser.parse(RootShell.preloadStatus())
                if (job?.isActive == true && status?.running == true) {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIF_ID, progressNotification(this@PreloadService, status))
                } else if (job?.isActive != true) {
                    stopSelf() // Llegó la acción sin precarga que seguir: no dejar el servicio vivo.
                }
            }
            return START_NOT_STICKY
        }
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
        var hardDeadline = SystemClock.elapsedRealtime() + HARD_TIMEOUT_MS
        var everRunning = false
        var lastKey: String? = null
        while (isActive) {
            val status = PreloadStatusParser.parse(RootShell.preloadStatus())
            val now = SystemClock.elapsedRealtime()
            when {
                status?.running == true -> {
                    everRunning = true
                    // En pausa no corre el tope de seguridad (puede quedar pausada horas).
                    if (status.paused) hardDeadline = now + HARD_TIMEOUT_MS
                    val key = "${status.doneMb}/${status.selectedMb}/${status.doneFiles}/${status.selectedFiles}/${status.paused}"
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

    /**
     * Quita la notificación en curso. Solo si la precarga terminó completa deja un aviso
     * descartable; si quedó incompleta (se desmontó, se cortó, no entró todo) no se muestra nada.
     */
    private fun finish(nm: NotificationManager, status: PreloadStatus?) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (status != null && status.finished) nm.notify(DONE_ID, doneNotification(this, status))
        stopSelf()
    }

    companion object {
        private const val CHANNEL_ID = "preload"
        private const val NOTIF_ID = 7101
        private const val DONE_ID = 7102
        private const val ACTION_PAUSE = "com.nubind.app.action.PRELOAD_PAUSE"
        private const val ACTION_RESUME = "com.nubind.app.action.PRELOAD_RESUME"
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

        private fun actionIntent(ctx: Context, action: String): PendingIntent = PendingIntent.getService(
            ctx, if (action == ACTION_PAUSE) 1 else 2,
            Intent(ctx, PreloadService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        private fun detail(ctx: Context, s: PreloadStatus) =
            ctx.getString(R.string.mb_archivos, s.doneMb, s.selectedMb, s.doneFiles, s.selectedFiles)

        private fun progressNotification(ctx: Context, s: PreloadStatus?): Notification {
            // preload.sh publica "running" desde que recorre el remoto, antes de saber qué
            // va a descargar (selected_mb = 0). PreloadStatus.fraction da 1f en ese caso,
            // así que sin esta guarda se mostraba "100% · 0 / 0 MB". Mientras no haya un
            // total conocido se muestra "revisando" con barra indeterminada.
            val known = s != null && s.selectedMb > 0
            val pct = if (known) (s!!.fraction * 100).toInt() else null
            val paused = s?.paused == true
            val titleBase = ctx.getString(if (paused) R.string.preload_pausada else R.string.precargando)
            val b = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif_preload)
                .setContentTitle(if (pct != null) "$titleBase $pct%" else titleBase)
                .setContentText(if (known) detail(ctx, s!!) else ctx.getString(R.string.preload_scanning))
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(openAppIntent(ctx))
            if (s != null && s.remote.isNotBlank()) b.setSubText(s.remote)
            // Pausada: la barra se queda quieta (no indeterminada) aunque aún no haya total.
            if (pct != null) b.setProgress(100, pct, false) else b.setProgress(0, 0, !paused)
            // Pausar / Reanudar sin abrir la app.
            if (paused) {
                b.addAction(android.R.drawable.ic_media_play, ctx.getString(R.string.preload_reanudar), actionIntent(ctx, ACTION_RESUME))
            } else {
                b.addAction(android.R.drawable.ic_media_pause, ctx.getString(R.string.preload_pausar), actionIntent(ctx, ACTION_PAUSE))
            }
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
