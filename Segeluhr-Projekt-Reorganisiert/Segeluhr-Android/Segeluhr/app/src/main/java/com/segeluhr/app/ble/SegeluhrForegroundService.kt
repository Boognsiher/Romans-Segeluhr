package com.segeluhr.app.ble

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.segeluhr.app.data.model.OperationMode
import com.segeluhr.app.data.settings.SettingsRepository
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
 * Hält GPS am Laufen (FusedLocationProviderClient liefert sonst keine
 * verlässlichen Fixe mehr, sobald der Bildschirm aus ist bzw. die App in
 * den Hintergrund geht — Android drosselt/stoppt Standort-Updates ohne
 * aktiven Foreground-Service) — UND die BLE-GATT-Bridge (Handy -> T-Watch,
 * BLE_Protokoll.md), falls eine Uhr verbunden werden soll.
 *
 * Läuft bewusst in BEIDEN Betriebsmodi ("Ohne Uhr"/STANDALONE und
 * "Mit Uhr"/WITH_WATCH, siehe [OperationMode]) — vorher lief er NUR im
 * WITH_WATCH-Modus, wodurch das Log/Tracking bei reinem Handy-Betrieb ohne
 * Uhr abriss, sobald das Handy z.B. in der Tasche das Display abschaltete
 * (gemeldeter Bug: "trackt nicht mit, wenn ich nur mit dem Handy raus
 * gehe"). Das GATT-Server-Advertising selbst ist laut eigener Doku
 * "dauerhaft advertisen, geringer Verbrauch" (siehe BleGattServerManager)
 * und damit auch im Ohne-Uhr-Modus unbedenklich — falls später doch eine
 * Uhr dazukommt, kann sie sich verbinden, ohne dass der Modus vorher
 * umgeschaltet werden muss.
 */
class SegeluhrForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private lateinit var bleManager: BleGattServerManager
    private lateinit var locationProvider: LocationProvider
    private lateinit var settingsRepo: SettingsRepository
    // Setup-Tab-Schalter "Display wach halten" (bisher rein kosmetisch, ohne
    // Wirkung) — haelt hier tatsaechlich die CPU wach, damit der 1Hz-GPS-Tick
    // nicht durch Doze verzoegert/ausgesetzt wird, waehrend das Handy in der
    // Tasche steckt (Bildschirm bleibt dabei bewusst AUS, PARTIAL_WAKE_LOCK
    // betrifft nur die CPU, nicht das Display).
    private var wakeLock: PowerManager.WakeLock? = null

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
        settingsRepo = SettingsRepository(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(OperationMode.STANDALONE))
        bleManager.start()
        locationProvider.fixFlow()
            .onEach { fix -> bleManager.notifyGpsFix(fix) }
            .launchIn(scope)
        // Notification-Text an den tatsächlichen Modus anpassen, live bei
        // Umschalten im Setup-Tab (Service selbst läuft in beiden Modi
        // unverändert weiter, siehe Klassenkommentar).
        settingsRepo.operationModeFlow
            .onEach { mode ->
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, buildNotification(mode))
            }
            .launchIn(scope)
        settingsRepo.wakeLockEnabledFlow
            .onEach { enabled -> setWakeLockHeld(enabled) }
            .launchIn(scope)
        return START_STICKY
    }

    override fun onDestroy() {
        setWakeLockHeld(false)
        bleManager.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun setWakeLockHeld(held: Boolean) {
        if (held) {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Segeluhr:tracking")
                .apply { setReferenceCounted(false); acquire() }
        } else {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(mode: OperationMode): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (mode == OperationMode.WITH_WATCH) {
            "GPS wird an die T-Watch übertragen"
        } else {
            "GPS-Tracking läuft (ohne Uhr)"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Segeluhr — Tracking aktiv")
            .setContentText(text)
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
