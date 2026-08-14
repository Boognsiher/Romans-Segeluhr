package com.segeluhr.watch.ble

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.segeluhr.watch.MainActivity

/**
 * Hält die BLE-Verbindung zum Handy (und damit Haptik-Alarme) am Laufen,
 * auch wenn das Display der Uhr aus ist — analog zu
 * SegeluhrForegroundService.kt am Handy, hier aber nur für die Central-
 * Verbindung (kein eigener GATT-Server, keine GPS-Weiterleitung nötig).
 */
class WatchBleForegroundService : Service() {

    private lateinit var bleClient: WatchBleClient

    companion object {
        private const val CHANNEL_ID = "segeluhr_watch_ble"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, WatchBleForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchBleForegroundService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        bleClient = WatchBleBridge.getInstance(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        bleClient.start()
        return START_STICKY
    }

    override fun onDestroy() {
        bleClient.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Segeluhr")
            .setContentText("Verbunden mit dem Handy")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Segeluhr BLE", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
