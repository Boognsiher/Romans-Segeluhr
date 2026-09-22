package com.segeluhr.watch

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBlePermissionsIfNeeded()
        setContent {
            SegeluhrWatchTheme {
                SegelnApp(viewModel)
            }
        }
    }

    // ---- Physischer Taster als Touch-Ersatz (22.09.2026, Roman-Wunsch: bei
    // Nässe ist der Touchscreen unzuverlässig, Countdown-Start und Tab-
    // Wechsel sollen über die Hardware-Taste laufen, siehe
    // docs/Erweiterung_GalaxyWatch_App.md "Physischer Taster"). Kurzer Druck
    // = Hauptaktion (öfter gebraucht, soll schnell gehen), langer Druck =
    // nächste Anzeige (siehe SegeluhrWatchViewModel.onPhysicalButton{Action,NextPage}).
    //
    // WELCHE Taste die Watch 5 Pro dafür tatsächlich sendet, ist ohne
    // Hardware-Test nicht sicher bekannt — der Home-Knopf ist auf Wear OS
    // grundsätzlich system-reserviert (geht immer zum Ziffernblatt/App-
    // Drawer, lässt sich nicht abfangen), deshalb bewusst auf die
    // Zurück-Taste UND alle drei Stem-Tastencodes gleichzeitig gehört -
    // welcher davon nie feuert, schadet nicht.
    private var keyDownAtMs: Long = 0L

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode in PHYSICAL_ACTION_KEYCODES) {
            if (event?.repeatCount == 0) keyDownAtMs = SystemClock.elapsedRealtime()
            return true // Event konsumieren - keine Standard-Aktion (z.B. Zurück-Navigation/App verlassen) auslösen
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode in PHYSICAL_ACTION_KEYCODES) {
            val heldMs = SystemClock.elapsedRealtime() - keyDownAtMs
            if (heldMs >= LONG_PRESS_THRESHOLD_MS) {
                viewModel.onPhysicalButtonNextPage()
            } else {
                viewModel.onPhysicalButtonAction()
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
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

    companion object {
        private const val LONG_PRESS_THRESHOLD_MS = 500L
        private val PHYSICAL_ACTION_KEYCODES = setOf(
            KeyEvent.KEYCODE_STEM_1, KeyEvent.KEYCODE_STEM_2, KeyEvent.KEYCODE_STEM_3,
            KeyEvent.KEYCODE_BACK,
        )
    }
}
