package com.example.soundtrigger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings

@Suppress("DEPRECATION")
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val alarmAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val channel = NotificationChannel(
                ALARM_CHANNEL,
                "Sound Trigger alarm",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Ten-minute Sound Trigger timer"
                setSound(Settings.System.DEFAULT_ALARM_ALERT_URI, alarmAttributes)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 900)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, ALARM_CHANNEL)
        } else {
            Notification.Builder(context).setSound(Settings.System.DEFAULT_ALARM_ALERT_URI)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Sound Trigger — 10 минут прошло")
            .setContentText("Таймер завершён")
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

        manager.notify(ALARM_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val ALARM_CHANNEL = "soundtrigger_alarm"
        private const val ALARM_NOTIFICATION_ID = 2002
    }
}
