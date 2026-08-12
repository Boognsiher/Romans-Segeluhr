package com.segeluhr.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.segeluhr.app.core.GeoPoint
import com.segeluhr.app.ui.theme.*
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

/**
 * Erweiterung "Bojen/Marken auf der Karte setzen" (12.08.2026, Roman-
 * Wunschliste "vor dem nächsten Test", siehe
 * docs/Erweiterung_Boje_Kartenauswahl.md) — Alternative zum bisherigen
 * "Setzen" (fixiert immer die aktuelle GPS-Position), abgeleitet vom
 * gleichen osmdroid-Muster wie [LakeDrawScreen] (MapView, MapEventsOverlay
 * für Taps, Pflicht-Attribution). Anders als dort aber EIN Punkt statt einer
 * Kette: Tippen setzt/verschiebt einfach immer den einen Marker, statt neue
 * Punkte hinzuzufügen — [LakeDrawScreen]s Ketten-/Polygon-Logik passt
 * konzeptionell nicht zu "ein Wegpunkt", deshalb eigener kleiner Screen statt
 * Wiederverwendung mit einem "maxPoints=1"-Sonderfall dort.
 */
@Composable
fun WaypointMapPickScreen(
    label: String,
    startCenter: GeoPoint?,
    onFinish: (GeoPoint) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current

    // osmdroid-Setup, identisch zu LakeDrawScreen.kt/LandUhrScreen.kt.
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
                    controller.setZoom(if (startCenter != null) 15.0 else 8.0) // ohne GPS-Fix: weiter rausgezoomt, Nutzer muss selbst zur Stelle navigieren
                    controller.setCenter(center)
                    overlays.add(CopyrightOverlay(ctx)) // Pflicht-Attribution für OSM-Kartendaten (ODbL)

                    // Tippen = Marker setzen/verschieben. Als ERSTER Overlay (Index 0),
                    // damit der Marker-Overlay (unten, onMarkerClickListener { false })
                    // Taps nicht abfängt - gleiche Reihenfolge wie in LakeDrawScreen.
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

        // Marker bei jeder Punkt-Änderung neu setzen (ersetzt den alten statt
        // einen weiteren hinzuzufügen - EIN Wegpunkt, keine Kette).
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

        // Oben: Anleitung, halbtransparent über der Karte.
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(BgDark.copy(alpha = 0.85f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text("$label auf Karte setzen", color = TextLight, fontSize = 16.sp)
            Text(
                if (point == null) "Antippen, um die Position zu setzen." else "Erneut antippen, um zu verschieben.",
                color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp),
            )
        }

        // Unten: Aktionen, halbtransparent über der Karte.
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(BgDark.copy(alpha = 0.85f))
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Text("Abbrechen")
            }
            Button(
                onClick = { point?.let { onFinish(GeoPoint(it.latitude, it.longitude)) } },
                enabled = point != null,
                colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = androidx.compose.ui.graphics.Color(0xFF04201C)),
                modifier = Modifier.weight(1f),
            ) { Text("Übernehmen") }
        }
    }
}
