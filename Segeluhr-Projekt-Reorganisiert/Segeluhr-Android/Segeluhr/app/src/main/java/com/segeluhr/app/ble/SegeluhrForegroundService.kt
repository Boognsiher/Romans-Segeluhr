package com.segeluhr.app.ble

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.segeluhr.app.location.LocationProvider
import com.segeluhr.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Hält die BLE-GATT-Bridge (Handy -> T-Watch, BLE_Protokoll.md) und die
 * GPS-Weiterleitung am Laufen, auch wenn die App im Hintergrund ist.
 * Wird über den "BLE-Bridge"-Schalter im Setup-Tab gestartet/gestoppt.
 */
class SegeluhrForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private lateinit var bleManager: BleGattServerManager
    private lateinit var locationProvider: LocationProvider

    companion object {
        private const val CHANNEL_ID = "segeluhr_ble_bridge"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            val intent = Intent(context, SegeluhrForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SegeluhrForegroundService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        bleManager = BleBridge.getInstance(applicationContext)
        locationProvider = LocationProvider(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        bleManager.start()
        locationProvider.fixFlow()
            .onEach { fix -> bleManager.notifyGpsFix(fix) }
            .launchIn(scope)
        return START_STICKY
    }

    override fun onDestroy() {
        bleManager.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Segeluhr — BLE-Bridge aktiv")
            .setContentText("GPS wird an die T-Watch übertragen")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "BLE-GPS-Bridge", NotificationManager.IMPORTANCE_LOW,
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
