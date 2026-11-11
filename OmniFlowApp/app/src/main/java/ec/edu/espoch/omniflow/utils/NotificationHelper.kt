package ec.edu.espoch.omniflow.utils

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ec.edu.espoch.omniflow.MainActivity
import ec.edu.espoch.omniflow.R

object NotificationHelper {
    /** Canal con el sonido propio de OmniFlow (los canales no pueden cambiar de sonido una vez creados). */
    const val CHANNEL_ALERTS = "omniflow_alertas_sonido"
    /** Canal sin sonido, para quien desactive el sonido de alertas en Ajustes. */
    const val CHANNEL_SILENT = "omniflow_alertas_silencio"
    private const val OLD_CHANNEL = "omniflow_alerts"
    private val VIBRATION = longArrayOf(0, 250, 120, 250)

    private fun soundUri(context: Context): Uri =
        Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/${R.raw.alerta}")

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(OLD_CHANNEL)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.notif_channel_alerts), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.notif_channel_alerts_desc)
                setSound(soundUri(context), attrs)
                enableVibration(true)
                vibrationPattern = VIBRATION
                enableLights(true)
                lightColor = 0xFFC8102E.toInt()
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SILENT, "Alertas (sin sonido)", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.notif_channel_alerts_desc)
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    fun canNotify(context: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Muestra la alerta. Si no hay permiso de notificaciones, igual suena dentro de la app. */
    fun show(context: Context, id: Int, title: String, body: String, sound: Boolean) {
        if (!canNotify(context)) {
            if (sound) playAlertSound(context)
            return
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_ALERTS, true)
        }
        val pending = PendingIntent.getActivity(
            context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, if (sound) CHANNEL_ALERTS else CHANNEL_SILENT)
            .setSmallIcon(R.drawable.ic_stat_omniflow)
            .setColor(0xFFC8102E.toInt())
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (sound) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Permiso revocado en tiempo de ejecucion: la alerta igual queda registrada en la app.
            if (sound) playAlertSound(context)
        }
    }

    /** Reproduce el tono de alerta de OmniFlow (tambien lo usa el boton "Probar sonido" de Ajustes). */
    fun playAlertSound(context: Context) {
        runCatching {
            MediaPlayer.create(context.applicationContext, R.raw.alerta)?.apply {
                setOnCompletionListener { it.release() }
                start()
            }
        }
    }
}
