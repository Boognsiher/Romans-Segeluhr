package com.segeluhr.app.ble

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
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
        private const val TAG = "SegeluhrFgService"

        /** Standort (fein oder grob) erteilt - Voraussetzung fuer den FGS-Typ "location" und fuer GPS. */
        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /**
         * BLE-Bridge erlaubt (API 31+: CONNECT fuer den GATT-Server, ADVERTISE fuers Advertising; darunter keine Laufzeit-Berechtigung).
         * Voraussetzung fuer den FGS-Typ "connectedDevice" und fuer bleManager.start().
         */
        fun hasBluetoothPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || (
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
                )

        /**
         * Bugfix 28.11.2026 (Absturz bei frischer Installation, PROJEKT_STATUS 24.11.): Ab Android 14 verlangt startForeground() fuer jeden
         * FGS-Typ die passende Laufzeit-Berechtigung, sonst SecurityException und Absturzschleife - noch bevor der Berechtigungsdialog
         * erscheinen kann. Deshalb hier nur starten, wenn mindestens Standort ODER Bluetooth erteilt ist. Die Pruefung muss VOR
         * startForegroundService() liegen: Nach diesem Aufruf muss startForeground() innerhalb weniger Sekunden folgen, sonst stuerzt die
         * App mit ForegroundServiceDidNotStartInTimeException ab. Nach erteilten Berechtigungen ruft MainActivity start() erneut auf.
         */
        fun start(context: Context) {
            if (!hasLocationPermission(context) && !hasBluetoothPermission(context)) {
                Log.i(TAG, "Dienst nicht gestartet: weder Standort- noch Bluetooth-Berechtigung erteilt")
                return
            }
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
        // (28.11.2026) Nur die FGS-Typen anfordern, deren Berechtigung erteilt ist: ohne Bluetooth laeuft GPS trotzdem im Hintergrund
        // ("Ohne Uhr"), ohne Standort trotzdem die BLE-Bridge. Ohne Typ-Angabe nimmt Android alle Typen aus dem Manifest
        // (location|connectedDevice) und wirft bei einer fehlenden Berechtigung eine SecurityException.
        val location = hasLocationPermission(this)
        val bluetooth = hasBluetoothPermission(this)
        var types = 0
        if (location) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (bluetooth) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (types == 0) {
            // Nur moeglich, wenn eine Berechtigung zwischen start() und hier entzogen wurde, oder beim Neustart durch das System (START_STICKY).
            Log.w(TAG, "Keine Berechtigung mehr fuer den Vordergrunddienst - beende ihn")
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(OperationMode.STANDALONE), types)
        // start() kann mehrfach kommen (App-Start, Rollenwechsel, nach erteilten Berechtigungen): Ablaeufe nur einmal starten,
        // sonst laufen GPS-Abfragen und Sammler doppelt.
        if (bluetooth) bleManager.start()            // hat selbst einen Schutz gegen doppeltes Starten
        if (location && locationJob == null) {
            locationJob = locationProvider.fixFlow()
                .onEach { fix -> bleManager.notifyGpsFix(fix) }
                .launchIn(scope)
        }
        if (!settingsCollectorsStarted) {
            settingsCollectorsStarted = true
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
        }
        return START_STICKY
    }

    private var locationJob: Job? = null
    private var settingsCollectorsStarted = false

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
