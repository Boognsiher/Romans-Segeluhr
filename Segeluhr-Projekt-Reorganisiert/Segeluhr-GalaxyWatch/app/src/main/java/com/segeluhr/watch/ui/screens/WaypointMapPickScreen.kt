package com.segeluhr.watch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Text
import com.segeluhr.watch.core.GeoPoint
import com.segeluhr.watch.ui.theme.BgDark
import com.segeluhr.watch.ui.theme.OnAccent
import com.segeluhr.watch.ui.theme.TextDim
import com.segeluhr.watch.ui.theme.TextLight
import com.segeluhr.watch.ui.theme.Teal
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

/**
 * Wegpunkt per Kartentipp setzen — neu ggü. der Ultra (siehe
 * docs/Erweiterung_GalaxyWatch_App.md), abgeleitet vom selben osmdroid-
 * Muster wie WaypointMapPickScreen.kt am Handy: EIN Marker, Tippen
 * setzt/verschiebt ihn. Anders als am Handy schreibt [onFinish] NICHT
 * direkt in eine lokale Datenbank, sondern schickt die Koordinate per BLE
 * ans Handy (CMD_SET_WAYPOINT_AT_COORDS) — das Handy bleibt die einzige
 * Quelle der Wahrheit für Wegpunkte.
 */
@Composable
fun WaypointMapPickScreen(label: String, startCenter: GeoPoint?, onFinish: (GeoPoint) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        val osmdroidDir = context.cacheDir.resolve("osmdroid")
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = osmdroidDir
            osmdroidTileCache = osmdroidDir.resolve("tiles")
        }
    }

    var point by remember { mutableStateOf<OsmGeoPoint?>(null) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var markerRef by remember { mutableStateOf<Marker?>(null) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    val center = startCenter?.let { OsmGeoPoint(it.lat, it.lon) } ?: OsmGeoPoint(47.0, 8.0)
                    controller.setZoom(if (startCenter != null) 15.0 else 8.0)
                    controller.setCenter(center)
                    overlays.add(CopyrightOverlay(ctx))
                    overlays.add(
                        0,
                        MapEventsOverlay(object : MapEventsReceiver {
                            override fun singleTapConfirmedHelper(p: OsmGeoPoint): Boolean {
                                point = p
                                return true
                            }
                            override fun longPressHelper(p: OsmGeoPoint): Boolean = false
                        }),
                    )
                    mapViewRef = this
                }
            },
        )

        LaunchedEffect(point) {
            val mapView = mapViewRef ?: return@LaunchedEffect
            val p = point
            markerRef?.let { mapView.overlays.remove(it) }
            markerRef = null
            if (p != null) {
                val marker = Marker(mapView).apply {
                    position = p
                    title = label
                    setOnMarkerClickListener { _, _ -> false }
                }
                mapView.overlays.add(marker)
                markerRef = marker
            }
            mapView.invalidate()
        }

        Column(
            Modifier.fillMaxWidth().align(Alignment.TopCenter)
                .background(BgDark.copy(alpha = 0.88f)).padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(label, color = TextLight, fontSize = 12.sp)
            Text(
                if (point == null) "Antippen zum Setzen" else "Erneut antippen zum Verschieben",
                color = TextDim, fontSize = 9.sp,
            )
        }

        Row(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .background(BgDark.copy(alpha = 0.88f)).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(onClick = onCancel, colors = ButtonDefaults.secondaryButtonColors(), modifier = Modifier.weight(1f)) {
                Text("Abbr.", fontSize = 11.sp)
            }
            Button(
                onClick = { point?.let { onFinish(GeoPoint(it.latitude, it.longitude)) } },
                enabled = point != null,
                colors = ButtonDefaults.primaryButtonColors(backgroundColor = Teal, contentColor = OnAccent),
                modifier = Modifier.weight(1f),
            ) { Text("OK", fontSize = 11.sp) }
        }
    }
}
