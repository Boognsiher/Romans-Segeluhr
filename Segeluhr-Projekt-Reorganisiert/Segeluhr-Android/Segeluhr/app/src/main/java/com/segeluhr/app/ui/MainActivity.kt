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
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import com.segeluhr.app.data.model.AppRole
import com.segeluhr.app.ui.screens.*
import com.segeluhr.app.ui.theme.SegeluhrTheme
import com.segeluhr.app.viewmodel.LandUhrViewModel
import com.segeluhr.app.viewmodel.SegeluhrViewModel
import kotlinx.coroutines.launch

private enum class Tab(val label: String) {
    NORMAL("Normal"), START("Start"), WIND("Wind"), TRAINING("Training"), LOG("Log"), SESSIONS("Verlauf"), SETUP("Setup"),
}

/**
 * Anzeigetext für WaypointMapPickScreen (siehe
 * docs/Erweiterung_Boje_Kartenauswahl.md) — dieselben Labels wie in den
 * jeweiligen WaypointRow-Aufrufen (TrainingScreen.kt/SetupScreen.kt).
 */
/**
 * "Als PDF teilen" (SessionReportDialog + SessionHistoryScreen, siehe
 * docs/Erweiterung_Tages_Auswertung.md) — baut das PDF asynchron
 * (SessionPdfExporter.export ist suspend, rendert dafür kurz eine
 * Karten-Bitmap) und öffnet danach den System-Share-Dialog. Toast bei
 * Fehlern (z.B. kein Internet für die Kartenkacheln) statt stillem
 * Verschlucken.
 */
/**
 * [onExportingChange] treibt den blockierenden Ladedialog in [SegeluhrApp]
 * (Roman-Feedback 17.08.2026: Export dauert wegen der Kartenkacheln-
 * Wartezeit mehrere Sekunden, ohne Rückmeldung wirkt der Button-Druck wie
 * "tut nichts") — true beim Start, false in jedem Fall (Erfolg wie Fehler)
 * am Ende.
 */
private fun sharePdf(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    report: com.segeluhr.app.data.model.SessionReport,
    onExportingChange: (Boolean) -> Unit,
) {
    scope.launch {
        onExportingChange(true)
        try {
            val uri = com.segeluhr.app.data.pdf.SessionPdfExporter.export(context, report)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(intent, "Auswertung als PDF teilen"))
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "PDF-Export fehlgeschlagen: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        } finally {
            onExportingChange(false)
        }
    }
}

private fun waypointLabelFor(key: String): String = when (key) {
    "buoy1" -> "Boje 1"
    "buoy2" -> "Boje 2"
    "competitionMark1" -> "Luvboje"
    // "competitionMark2" 02.09.2026 aus dem Setup-Tab entfernt (siehe
    // docs/Erweiterung_Competition_Kursmodell.md), Label-Fall bewusst
    // stehen gelassen — der Wegpunkt/Key existiert intern weiter.
    "competitionMark2" -> "Entlastungsboje (Halbwind)"
    // NEU (02.09.2026, siehe docs/Erweiterung_Competition_Kursmodell.md)
    "leeBuoy" -> "Lee-Boje"
    "gateA" -> "Gate A"
    "gateB" -> "Gate B"
    "finishBuoy" -> "Zielboje (Lee, hinter Boot)"
    else -> "Wegpunkt"
}

class MainActivity : ComponentActivity() {

    private val viewModel: SegeluhrViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val locationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        viewModel.onLocationPermissionResult(locationGranted)
        // Nur relevant ab API 31 (siehe hasBluetoothScanPermission()) - auf
        // aelteren Versionen kommt BLUETOOTH_SCAN gar nicht in "results" vor,
        // dann bleibt hasBluetoothScanPermission() (true) massgeblich.
        if (results.containsKey(Manifest.permission.BLUETOOTH_SCAN)) {
            viewModel.onBluetoothScanPermissionResult(results[Manifest.permission.BLUETOOTH_SCAN] == true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkLocationPermission()
        viewModel.onBluetoothScanPermissionResult(hasBluetoothScanPermission())

        setContent {
            SegeluhrTheme {
                SegeluhrApp(viewModel, onRequestPermissions = ::requestAllPermissions)
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // Bugfix 09.08.2026: BLUETOOTH_SCAN (API 31+, noetig fuer den Land-Modus,
    // siehe docs/Erweiterung_Landuhr_Kartenansicht.md) wurde bei Installationen,
    // die den Standortzugriff schon vorher erteilt hatten, NIE angefragt - der
    // Button dafuer im Setup-Tab war an locationPermissionGranted gekoppelt,
    // das bei solchen Installs schon true war. App fand die Land-Uhr nie,
    // SecurityException im Logcat bestaetigt. Deshalb jetzt als eigener Check.
    private fun hasBluetoothScanPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED

    private fun checkLocationPermission() {
        viewModel.onLocationPermissionResult(hasLocationPermission())
    }

    private fun requestAllPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            // Fürs Scannen als BLE-Central im Land-Modus, siehe
            // docs/Erweiterung_Landuhr_Kartenansicht.md - hier zusammen mit den
            // anderen BLE-Permissions angefragt, unabhängig von der aktuellen
            // Rolle (einmalig beim ersten Start, nicht bei jedem Rollenwechsel).
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
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

    // Start-Rollenwahl (siehe docs/Erweiterung_App_Stopp_Rollenwahl.md):
    // JEDER App-Start zeigt erst diesen Wahlbildschirm, bevor irgendein
    // anderer Screen aufgebaut wird - roleConfirmedThisSession ist bewusst
    // nicht persistiert (siehe SegeluhrUiState-Kommentar), erscheint also
    // wirklich bei jedem Neustart erneut.
    if (!state.roleConfirmedThisSession) {
        RolePickerScreen(lastRole = state.appRole, onPick = viewModel::confirmRole)
        return
    }

    // Rollen-Umschalter (siehe docs/Erweiterung_Landuhr_Kartenansicht.md):
    // im Land-Modus komplett eigener Screen-Baum statt der Segler-Tabs
    // darunter - beide Rollen haben inhaltlich nichts miteinander zu tun.
    // LandUhrViewModel bewusst erst hier (per Compose-viewModel()) angelegt,
    // nicht eager in der Activity wie SegeluhrViewModel - soll nur laufen/
    // scannen, wenn diese Rolle auch aktiv gewählt ist.
    if (state.appRole == AppRole.SHORE) {
        val landViewModel: LandUhrViewModel = composeViewModel()
        val landState by landViewModel.state.collectAsState()
        LandUhrScreen(
            state = landState,
            onConnect = landViewModel::connect,
            onDisconnect = landViewModel::disconnect,
            onSwitchToSailorMode = { viewModel.setAppRole(AppRole.SAILOR) },
        )
        return
    }

    // See-Grenze-Zeichnen-Modus (siehe docs/Erweiterung_Seegrenze_Zeichnen.md)
    // — ersetzt wie die Land-Rolle oben komplett den Tab-Baum, solange aktiv.
    if (state.lakeDrawModeActive) {
        val fix = state.gpsFix
        LakeDrawScreen(
            startCenter = if (fix.lat != null && fix.lon != null) {
                com.segeluhr.app.core.GeoPoint(fix.lat, fix.lon)
            } else null,
            onFinish = viewModel::finishLakeDrawing,
            onCancel = viewModel::cancelLakeDrawing,
        )
        return
    }

    // Kartenauswahl für einen einzelnen Wegpunkt (siehe
    // docs/Erweiterung_Boje_Kartenauswahl.md) — gleiches Ersetzungsmuster
    // wie lakeDrawModeActive oben, nur pro Wegpunkt-Key.
    val waypointMapPickKey = state.waypointMapPickKey
    if (waypointMapPickKey != null) {
        val fix = state.gpsFix
        WaypointMapPickScreen(
            label = waypointLabelFor(waypointMapPickKey),
            startCenter = if (fix.lat != null && fix.lon != null) {
                com.segeluhr.app.core.GeoPoint(fix.lat, fix.lon)
            } else null,
            onFinish = viewModel::finishWaypointMapPick,
            onCancel = viewModel::cancelWaypointMapPick,
        )
        return
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // PDF-Export läuft (siehe docs/Erweiterung_Tages_Auswertung.md,
    // SessionPdfExporter) — braucht wegen der Kartenkacheln-Wartezeit
    // (TILE_LOAD_WAIT_MS) mehrere Sekunden. Roman-Feedback: ohne sichtbare
    // Rückmeldung wirkt der Button-Druck wie "tut nichts". Ladedialog blockt
    // bewusst (kein Zurück/Aussen-Tap) statt eines Spinners im Button, weil
    // "Als PDF teilen" an zwei Stellen (Dialog UND Verlauf-Detail) vorkommt
    // und ein zentraler Overlay beide gleich abdeckt.
    var pdfExportInProgress by remember { mutableStateOf(false) }
    if (pdfExportInProgress) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = {},
            properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        ) {
            Column(
                Modifier
                    .background(com.segeluhr.app.ui.theme.PanelDark, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .padding(28.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("PDF wird erstellt…", color = com.segeluhr.app.ui.theme.TextLight)
                Text("Lädt Kartenkacheln, kann einige Sekunden dauern.", fontSize = 12.sp, color = com.segeluhr.app.ui.theme.TextDim, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }

    // Tages-Auswertung (siehe docs/Erweiterung_Tages_Auswertung.md) —
    // erscheint automatisch bei App-Stopp, tab-unabhängig wie
    // BuoyRoundingConfirmBanner unten, aber als Dialog statt Banner (deutlich
    // mehr Inhalt, soll nicht mit einem Tab-Wechsel wieder verschwinden).
    val sessionReport = state.sessionReport
    if (sessionReport != null) {
        com.segeluhr.app.ui.components.SessionReportDialog(
            report = sessionReport,
            onDismiss = viewModel::dismissSessionReport,
            onShare = { sharePdf(context, coroutineScope, sessionReport) { inProgress -> pdfExportInProgress = inProgress } },
        )
    }

    var selectedTab by remember { mutableStateOf(Tab.NORMAL) }

    Scaffold(
        topBar = {
            TopBarWithGpsStatus(fresh = state.gpsFresh, moving = state.gpsMoving, stopped = state.appStopped)
        },
        bottomBar = {
            NavigationBar {
                val icons = mapOf(
                    Tab.NORMAL to Icons.Filled.Speed,
                    Tab.START to Icons.Filled.Timer,
                    Tab.WIND to Icons.Filled.Explore,
                    Tab.TRAINING to Icons.Filled.TrackChanges,
                    Tab.LOG to Icons.Filled.ShowChart,
                    Tab.SESSIONS to Icons.Filled.History,
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
            // Vereinheitlichte Bojen-Rundungserkennung (siehe
            // docs/Erweiterung_Vereinheitlichte_Bojenerkennung.md) — bewusst
            // TAB-UNABHÄNGIG hier eingeblendet, direkt unter dem Status-
            // Banner, damit die Rückfrage nicht verpasst wird, egal welcher
            // Tab gerade offen ist.
            if (state.pendingBuoyConfirmation != null) {
                Box(Modifier.padding(14.dp, 8.dp, 14.dp, 0.dp)) {
                    com.segeluhr.app.ui.components.BuoyRoundingConfirmBanner(
                        onConfirm = viewModel::confirmBuoyRounding,
                        onReject = viewModel::rejectBuoyRounding,
                    )
                }
            }
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
                Tab.WIND -> WindScreen(
                    state,
                    onStartCalib = viewModel::startAmwindCalibration,
                    onAbortCalib = viewModel::abortCalibration,
                    onCalibrationModeChanged = viewModel::setCalibrationModeEnabled,
                    onSmartModeChanged = viewModel::setSmartModeEnabled,
                    onResetBoatCalibration = viewModel::resetBoatCalibration,
                )
                Tab.TRAINING -> TrainingScreen(
                    state,
                    onSetMode = viewModel::setTrainMode,
                    onSetWaypoint = viewModel::captureWaypoint,
                    onClearWaypoint = viewModel::clearWaypoint,
                    onAutoDetectLake = viewModel::autoDetectLake,
                    onRemoveLakeCircle = viewModel::removeLakeCircle,
                    onStartLakeDrawing = viewModel::startLakeDrawing,
                    onSetWaypointFromMap = viewModel::startWaypointMapPick,
                )
                Tab.LOG -> LogScreen(state, onClearLog = viewModel::clearManeuverLog)
                Tab.SESSIONS -> {
                    val sessions by viewModel.sessionHistory().collectAsState(initial = emptyList())
                    com.segeluhr.app.ui.screens.SessionHistoryScreen(
                        sessions = sessions,
                        onDeleteSession = viewModel::deleteSession,
                        onExportPdf = { report -> sharePdf(context, coroutineScope, report) { inProgress -> pdfExportInProgress = inProgress } },
                        onImportLog = { uri ->
                            viewModel.importDiagnosticsLog(uri) { success ->
                                if (!success) {
                                    android.widget.Toast.makeText(context, "Import fehlgeschlagen — keine gültige Diagnose-Log-CSV.", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                    )
                }
                Tab.SETUP -> SetupScreen(
                    state,
                    onSetWaypoint = viewModel::captureWaypoint,
                    onClearWaypoint = viewModel::clearWaypoint,
                    onWakeLockChanged = viewModel::setWakeLockEnabled,
                    onOperationModeChanged = viewModel::setOperationMode,
                    onAppRoleChanged = viewModel::setAppRole,
                    onRequestLocationPermission = onRequestPermissions,
                    onResetAll = viewModel::resetAll,
                    onSetActiveBoatProfile = viewModel::setActiveBoatProfile,
                    onAddBoatProfile = viewModel::addBoatProfile,
                    onDeleteBoatProfile = viewModel::deleteBoatProfile,
                    onDiagnosticsEnabledChanged = viewModel::setDiagnosticsEnabled,
                    onMarkDiagnosticsEvent = viewModel::markDiagnosticsEvent,
                    onGetDiagnosticsShareUri = viewModel::shareDiagnosticsLogUri,
                    onStopApp = viewModel::stopApp,
                    onStartApp = viewModel::startApp,
                    onSetWaypointFromMap = viewModel::startWaypointMapPick,
                    onLeewardModeChanged = viewModel::setLeewardMode,
                )
            }
        }
    }
}

@Composable
private fun TopBarWithGpsStatus(fresh: Boolean, moving: Boolean, stopped: Boolean) {
    TopAppBar(
        title = { Text("⛵ Segeluhr") },
        actions = {
            // "Gestoppt" (siehe docs/Erweiterung_App_Stopp_Rollenwahl.md) hat
            // Vorrang vor dem normalen Fix-Status - sonst würde ein alter,
            // eingefrorener Fix-Zustand angezeigt, obwohl GPS gar nicht mehr läuft.
            val (dotColor, label) = when {
                stopped -> com.segeluhr.app.ui.theme.TextDim to "Gestoppt"
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
