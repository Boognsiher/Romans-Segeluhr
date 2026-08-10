package com.segeluhr.app.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.segeluhr.app.ble.LandConnectionState
import com.segeluhr.app.ble.LandPositionState
import com.segeluhr.app.ui.theme.*
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker

// 90s, wie LORA_SIGNAL_LOST_THRESHOLD_MS in shared/LoRaPacket.h (3x
// LORA_SEND_INTERVAL_MS) - ab hier gilt die S3 selbst schon als "Signal
// verloren", also uebernehmen wir denselben Schwellenwert fuer die Warnfarbe.
private const val STALE_THRESHOLD_MS = 90_000L

/**
 * Karten-Screen für den Land-Modus (AppRole.SHORE), siehe
 * docs/Erweiterung_Landuhr_Kartenansicht.md. Verbindet sich per BLE mit der
 * Land-Uhr (T-Watch S3) und zeigt die zuletzt von dort per LoRa empfangene
 * Boot-Position auf einer OSM-Karte (osmdroid).
 */
@Composable
fun LandUhrScreen(
    state: LandPositionState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSwitchToSailorMode: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Scan/Verbindung nur laufen lassen, waehrend dieser Screen auch
    // tatsaechlich sichtbar ist (siehe LandUhrViewModel-Kommentar) - reagiert
    // zusaetzlich auf ON_STOP/ON_START, falls die App in den Hintergrund geht.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> onConnect()
                Lifecycle.Event.ON_STOP -> onDisconnect()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onConnect()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            onDisconnect()
        }
    }

    // osmdroid-Setup, einmalig: User-Agent (sonst blockt der Tile-Server
    // irgendwann Anfragen) + Cache-Pfad explizit in den App-eigenen
    // Cache-Ordner gelegt, damit KEINE WRITE_EXTERNAL_STORAGE-Permission
    // nötig ist (osmdroid würde sonst versuchen, auf den externen Speicher
    // auszuweichen).
    LaunchedEffect(Unit) {
        val osmdroidDir = context.cacheDir.resolve("osmdroid")
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = osmdroidDir
            osmdroidTileCache = osmdroidDir.resolve("tiles")
        }
    }

    var secondsAgo by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.lastUpdateElapsedRealtimeMs) {
        while (true) {
            secondsAgo = state.lastUpdateElapsedRealtimeMs?.let {
                (SystemClock.elapsedRealtime() - it) / 1000
            }
            delay(1000)
        }
    }

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var markerRef by remember { mutableStateOf<Marker?>(null) }
    var hasCenteredOnce by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(13.0)
                    controller.setCenter(OsmGeoPoint(47.0, 8.0)) // grobe CH-Mitte, bis der erste Fix da ist
                    // ODbL-Pflicht-Attribution für OSM-Kartendaten — osmdroid fügt sie NICHT
                    // automatisch hinzu, muss explizit rein (siehe
                    // docs/Erweiterung_Landuhr_Kartenansicht.md, Lizenz-Abschnitt).
                    overlays.add(CopyrightOverlay(ctx))
                    mapViewRef = this
                }
            },
        )

        // Marker/Kamera bei jeder neuen Position aktualisieren - eigener Effekt
        // statt in factory{}, da AndroidView.factory nur beim ersten Bauen läuft.
        LaunchedEffect(state.position, state.gpsValidFix) {
            val mapView = mapViewRef ?: return@LaunchedEffect
            val pos = state.position
            if (pos == null || !state.gpsValidFix) {
                markerRef?.let { mapView.overlays.remove(it) }
                markerRef = null
                mapView.invalidate()
                return@LaunchedEffect
            }
            val osmPos = OsmGeoPoint(pos.lat, pos.lon)
            val marker = markerRef ?: Marker(mapView).also {
                it.title = "Boot"
                mapView.overlays.add(it)
                markerRef = it
            }
            marker.position = osmPos
            if (!hasCenteredOnce) {
                mapView.controller.setCenter(osmPos)
                mapView.controller.setZoom(15.0)
                hasCenteredOnce = true
            }
            mapView.invalidate()
        }

        // Oben: Verbindungsstatus + Zurück-Button, halbtransparent über der Karte.
        Column(
            Modifier
                .fillMaxWidth()
                .background(BgDark.copy(alpha = 0.85f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onSwitchToSailorMode) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Zurück zu Boot-Modus", tint = TextLight)
                }
                Spacer(Modifier.width(4.dp))
                Text("Landuhr — Boot-Position", color = TextLight, fontSize = 16.sp)
            }
            Text(
                statusText(state, secondsAgo),
                color = statusColor(state, secondsAgo),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
            )
        }
    }
}

private fun statusText(state: LandPositionState, secondsAgo: Long?): String = when (state.connectionState) {
    LandConnectionState.DISCONNECTED -> "Keine Verbindung zur Land-Uhr — versuche erneut..."
    LandConnectionState.SCANNING -> "Suche Land-Uhr (Bluetooth)..."
    LandConnectionState.CONNECTING -> "Verbinde mit Land-Uhr..."
    LandConnectionState.CONNECTED -> when {
        state.position == null -> "Verbunden — warte auf erstes Signal vom Boot..."
        !state.gpsValidFix -> "Verbunden — Boot hat aktuell keinen GPS-Fix"
        else -> "Verbunden — Position zuletzt vor ${secondsAgo ?: 0}s aktualisiert"
    }
}

private fun statusColor(state: LandPositionState, secondsAgo: Long?) = when {
    state.connectionState != LandConnectionState.CONNECTED -> TextDim
    state.position == null || !state.gpsValidFix -> Amber
    (secondsAgo ?: 0) * 1000 > STALE_THRESHOLD_MS -> Amber
    else -> Green
}
