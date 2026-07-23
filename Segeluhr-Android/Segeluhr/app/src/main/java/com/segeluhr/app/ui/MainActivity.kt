@file:OptIn(ExperimentalMaterial3Api::class)

package com.segeluhr.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.segeluhr.app.ui.screens.*
import com.segeluhr.app.ui.theme.SegeluhrTheme
import com.segeluhr.app.viewmodel.SegeluhrViewModel

private enum class Tab(val label: String) {
    NORMAL("Normal"), START("Start"), WIND("Wind"), TRAINING("Training"), LOG("Log"), SETUP("Setup"),
}

class MainActivity : ComponentActivity() {

    private val viewModel: SegeluhrViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val locationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        viewModel.onLocationPermissionResult(locationGranted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkLocationPermission()

        setContent {
            SegeluhrTheme {
                SegeluhrApp(viewModel, onRequestPermissions = ::requestAllPermissions)
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun checkLocationPermission() {
        viewModel.onLocationPermissionResult(hasLocationPermission())
    }

    private fun requestAllPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }
}

@Composable
private fun SegeluhrApp(viewModel: SegeluhrViewModel, onRequestPermissions: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableStateOf(Tab.NORMAL) }

    Scaffold(
        topBar = {
            TopBarWithGpsStatus(fresh = state.gpsFresh, moving = state.gpsMoving)
        },
        bottomBar = {
            NavigationBar {
                val icons = mapOf(
                    Tab.NORMAL to Icons.Filled.Speed,
                    Tab.START to Icons.Filled.Timer,
                    Tab.WIND to Icons.Filled.Explore,
                    Tab.TRAINING to Icons.Filled.TrackChanges,
                    Tab.LOG to Icons.Filled.ShowChart,
                    Tab.SETUP to Icons.Filled.Settings,
                )
                Tab.values().forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(icons[tab]!!, contentDescription = tab.label) },
                        label = { Text(tab.label, fontSize = 9.sp) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            StatusBannerHost(state.statusText, state.statusLevel)
            when (selectedTab) {
                Tab.NORMAL -> NormalScreen(
                    state,
                    onToggleHomeMode = viewModel::setHomeModeActive,
                    onStopCompetition = viewModel::stopCompetition,
                )
                Tab.START -> StartScreen(
                    state,
                    onStart = viewModel::startCountdown,
                    onReset = viewModel::resetCountdown,
                    onSyncToNextMinute = viewModel::syncCountdownToNextMinute,
                )
                Tab.WIND -> WindScreen(state, onStartCalib = viewModel::startAmwindCalibration, onAbortCalib = viewModel::abortCalibration)
                Tab.TRAINING -> TrainingScreen(
                    state,
                    onSetMode = viewModel::setTrainMode,
                    onSetWaypoint = viewModel::captureWaypoint,
                    onClearWaypoint = viewModel::clearWaypoint,
                )
                Tab.LOG -> LogScreen(state, onClearLog = viewModel::clearManeuverLog)
                Tab.SETUP -> SetupScreen(
                    state,
                    onSetWaypoint = viewModel::captureWaypoint,
                    onClearWaypoint = viewModel::clearWaypoint,
                    onWakeLockChanged = viewModel::setWakeLockEnabled,
                    onOperationModeChanged = viewModel::setOperationMode,
                    onRequestLocationPermission = onRequestPermissions,
                    onResetAll = viewModel::resetAll,
                )
            }
        }
    }
}

@Composable
private fun TopBarWithGpsStatus(fresh: Boolean, moving: Boolean) {
    TopAppBar(
        title = { Text("⛵ Segeluhr") },
        actions = {
            val (dotColor, label) = when {
                !fresh -> com.segeluhr.app.ui.theme.Red to "Kein Fix"
                moving -> com.segeluhr.app.ui.theme.Green to "Fix · Fahrt"
                else -> com.segeluhr.app.ui.theme.Amber to "Fix · langsam"
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                Box(
                    Modifier
                        .size(9.dp)
                        .background(dotColor, androidx.compose.foundation.shape.CircleShape)
                )
                Spacer(Modifier.width(6.dp))
                Text(label, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        },
    )
}

@Composable
private fun StatusBannerHost(text: String, level: com.segeluhr.app.logic.StatusLevel) {
    Box(Modifier.padding(14.dp, 8.dp, 14.dp, 0.dp)) {
        com.segeluhr.app.ui.components.StatusBanner(text, level)
    }
}
