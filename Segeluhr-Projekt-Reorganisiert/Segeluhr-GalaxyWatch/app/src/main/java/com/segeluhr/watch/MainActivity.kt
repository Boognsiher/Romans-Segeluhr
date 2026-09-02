package com.segeluhr.watch

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.segeluhr.watch.ui.SegelnApp
import com.segeluhr.watch.ui.theme.SegeluhrWatchTheme
import com.segeluhr.watch.viewmodel.SegeluhrWatchViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: SegeluhrWatchViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* Ergebnis wird nicht ausgewertet — der Foreground-Service versucht es bei fehlender Permission einfach erneut, siehe WatchBleClient. */ }

    // Physische-Tasten-Bedienung (02.09.2026, Roman-Wunsch): im
    // Wasserdicht-Modus ist der Touchscreen deaktiviert, einzige
    // Bedienung ist die untere ("Zurück"-)Taste der Watch 5 Pro
    // (KEYCODE_BACK). Die obere Taste ist auf Wear OS i.d.R.
    // system-reserviert (Power/Bixby/Home) und wird Vordergrund-Apps
    // normalerweise nicht zugestellt - KEYCODE_STEM_1 wird hier trotzdem
    // defensiv mitbehandelt, falls sie auf dieser Watch doch durchkommt;
    // unverifiziert bis zum nächsten Hardwaretest.
    //
    // dispatchKeyEvent() statt onKeyDown()/onKeyUp(), weil Compose sonst
    // KEYCODE_BACK selbst konsumieren könnte, bevor die Activity ihn sieht.
    // ACTION_DOWN merkt sich nur den Zeitpunkt (repeatCount==0, sonst
    // triggert Halten der Taste mehrfach); die eigentliche Aktion (kurz vs.
    // lang) wird erst bei ACTION_UP anhand der gemessenen Dauer ausgelöst,
    // mit dem System-Standard-Schwellwert für "lang".
    private var hardwareButtonDownAt: Long = 0L

    private fun isHardwareButtonKey(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_STEM_1

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isHardwareButtonKey(event.keyCode)) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) hardwareButtonDownAt = event.eventTime
                KeyEvent.ACTION_UP -> {
                    val heldMs = event.eventTime - hardwareButtonDownAt
                    if (heldMs >= ViewConfiguration.getLongPressTimeout()) {
                        viewModel.onHardwareButtonLongPress()
                    } else {
                        viewModel.onHardwareButtonShortPress()
                    }
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBlePermissionsIfNeeded()
        setContent {
            SegeluhrWatchTheme {
                SegelnApp(viewModel)
            }
        }
    }

    /**
     * BLUETOOTH_SCAN/CONNECT müssen ab Android 12 (API 31), POST_NOTIFICATIONS
     * (für den Foreground-Service) ab Android 13 (API 33) zur Laufzeit
     * angefragt werden.
     */
    private fun requestBlePermissionsIfNeeded() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        if (perms.isNotEmpty()) permissionLauncher.launch(perms.toTypedArray())
    }
}
