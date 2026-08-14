package com.segeluhr.watch

import android.Manifest
import android.os.Build
import android.os.Bundle
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
