package com.segeluhr.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.segeluhr.wear.ble.WearBleClient
import com.segeluhr.wear.ui.ConnectionScreen
import com.segeluhr.wear.ui.theme.SegeluhrWearTheme

/**
 * Erster BLE-Test-Screen für die Galaxy-Watch-App (Wear OS), siehe
 * docs/Erweiterung_GalaxyWatch_WearOS.md. Prüft die zentrale offene Frage
 * vor dem vollen Screen-Umfang: kann die Uhr sich per eigener BLE-GATT-
 * Verbindung mit dem Handy-GATT-Server verbinden - PARALLEL zur normalen
 * Wear-OS-Companion-Kopplung (klassisches Bluetooth für Benachrichtigungen
 * etc.)? Zeigt nur Verbindungsstatus + GPS/Wind/Batterie-Rohwerte, noch
 * keine echten Segeluhr-Screens (Countdown, Manöver, Haptik-Trigger folgen
 * erst danach, siehe TODOs in WearBleClient).
 */
class MainActivity : ComponentActivity() {

    private lateinit var bleClient: WearBleClient

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            bleClient.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleClient = WearBleClient(applicationContext)

        setContent {
            SegeluhrWearTheme {
                val state by bleClient.connectionState.collectAsState()
                val gps by bleClient.gpsData.collectAsState()
                val battery by bleClient.batteryPercent.collectAsState()
                val wind by bleClient.windData.collectAsState()

                ConnectionScreen(
                    state = state,
                    gps = gps,
                    batteryPercent = battery,
                    wind = wind,
                    onRetry = { requestPermissionsAndStart() },
                )
            }
        }

        requestPermissionsAndStart()
    }

    override fun onDestroy() {
        super.onDestroy()
        bleClient.stop()
    }

    private fun requestPermissionsAndStart() {
        val needed = requiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            bleClient.start()
        } else {
            requestPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun requiredPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}
