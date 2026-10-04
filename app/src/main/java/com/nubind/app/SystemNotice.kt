package com.nubind.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nubind.app.ui.components.NoticeKind

/**
 * Aviso fuera de la app (por ejemplo desde el tile de ajustes rápidos, donde no hay
 * pantalla de Compose donde dibujar el aviso expressive): una notificación emergente
 * breve, del color de su tipo, que se retira sola y al tocarla abre Nubind.
 *
 * Si las notificaciones están desactivadas, cae a un Toast para que el aviso no se pierda.
 */
object SystemNotice {
    private const val CHANNEL_ID = "notices"
    private const val NOTICE_ID = 7103

    fun show(ctx: Context, text: String, kind: NoticeKind) {
        val app = ctx.applicationContext
        val nm = NotificationManagerCompat.from(app)
        if (!nm.areNotificationsEnabled()) {
            fallbackToast(app, text)
            return
        }
        ensureChannel(app)

        val open = PendingIntent.getActivity(
            app, 0,
            Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = app.applicationInfo.loadLabel(app.packageManager)
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_notice)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(accent(kind))
            .setCategory(if (kind == NoticeKind.Error) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(kind.durationMs)
            .build()
        try {
            nm.notify(NOTICE_ID, notification)
        } catch (_: SecurityException) {
            fallbackToast(app, text)
        }
    }

    /** Mismos acentos que los avisos dentro de la app (verde, ámbar, rojo y azul petróleo). */
    private fun accent(kind: NoticeKind): Int = when (kind) {
        NoticeKind.Success -> 0xFF1B6D2F.toInt()
        NoticeKind.Warning -> 0xFF7A5900.toInt()
        NoticeKind.Error -> 0xFFBA1A1A.toInt()
        NoticeKind.Info -> 0xFF006688.toInt()
    }

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                ctx.getString(R.string.notice_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = ctx.getString(R.string.notice_channel_desc)
                setShowBadge(false)
            }
        )
    }

    private fun fallbackToast(ctx: Context, text: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, text, Toast.LENGTH_LONG).show() }
    }
}
