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
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mapevents.MapEventsOverlay
import org.osmdroid.views.overlay.mapevents.MapEventsReceiver

private const val MIN_POINTS = 3

/**
 * Erweiterung "See-Grenze auf Karte einzeichnen" (10.08.2026, siehe
 * docs/Erweiterung_Seegrenze_Zeichnen.md) — Alternative zur automatischen
 * OSM-Erkennung (`LakeAutoDetector`, scheitert bei Seen, die als
 * OSM-Relation statt einfachem "way" erfasst sind, z.B. Zürichsee) UND zum
 * bisherigen GPS-Rand-Abfahren ("Rand erfassen", braucht physische Präsenz
 * am See). Hier: echte osmdroid-Karte (dieselbe Komponente wie
 * `LandUhrScreen`, dort schon mit echten Kacheln verifiziert), Nutzer tippt
 * Uferpunkte nacheinander an, "Fertig" übergibt das Polygon an
 * `CirclePacking.packChain()` — dieselbe Kreis-Packungs-Logik wie bei der
 * automatischen Erkennung, nur mit selbst gewählten statt OSM-Punkten.
 *
 * Bewusst Tippen statt Freihand-Zeichnen (Roman-Entscheidung 10.08.2026) —
 * präziser am Touchscreen, kollidiert nicht mit der Karten-Wisch-Geste.
 */
@Composable
fun LakeDrawScreen(
    startCenter: GeoPoint?,
    onFinish: (List<GeoPoint>) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current

    // osmdroid-Setup, einmalig - identisch zu LandUhrScreen.kt (User-Agent +
    // App-eigener Cache-Ordner, keine WRITE_EXTERNAL_STORAGE-Permission nötig).
    LaunchedEffect(Unit) {
        val osmdroidDir = context.cacheDir.resolve("osmdroid")
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = osmdroidDir
            osmdroidTileCache = osmdroidDir.resolve("tiles")
        }
    }

    var points by remember { mutableStateOf(listOf<OsmGeoPoint>()) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    val markerRefs = remember { mutableStateListOf<Marker>() }
    var polygonOverlay by remember { mutableStateOf<Polygon?>(null) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    val center = startCenter?.let { OsmGeoPoint(it.lat, it.lon) } ?: OsmGeoPoint(47.0, 8.0)
                    controller.setZoom(if (startCenter != null) 14.0 else 8.0) // ohne GPS-Fix: weiter rausgezoomt, Nutzer muss selbst zum See navigieren
                    controller.setCenter(center)
                    overlays.add(CopyrightOverlay(ctx)) // Pflicht-Attribution für OSM-Kartendaten (ODbL), siehe docs/Erweiterung_Landuhr_Kartenansicht.md

                    // Tippen auf die Karte = neuer Uferpunkt. Als ERSTER Overlay
                    // hinzugefügt (Index 0), damit spätere Marker-Overlays (unten,
                    // mit onMarkerClickListener { false }) Taps nicht abfangen -
                    // Standard-osmdroid-Empfehlung für MapEventsOverlay.
                    overlays.add(
                        0,
                        MapEventsOverlay(object : MapEventsReceiver {
                            override fun singleTapConfirmedHelper(p: OsmGeoPoint): Boolean {
                                points = points + p
                                return true
                            }
                            override fun longPressHelper(p: OsmGeoPoint): Boolean = false
                        }),
                    )
                    mapViewRef = this
                }
            },
        )

        // Marker + Polygon-Vorschau bei jeder Punkt-Änderung neu aufbauen -
        // eigener Effekt statt in factory{}, da factory nur beim ersten Bauen läuft.
        LaunchedEffect(points) {
            val mapView = mapViewRef ?: return@LaunchedEffect

            markerRefs.forEach { mapView.overlays.remove(it) }
            markerRefs.clear()
            polygonOverlay?.let { mapView.overlays.remove(it) }
            polygonOverlay = null

            points.forEachIndexed { i, p ->
                val marker = Marker(mapView).apply {
                    position = p
                    title = "${i + 1}"
                    // WICHTIG: false zurückgeben, sonst "verschluckt" der Marker
                    // selbst den Tap (Info-Fenster) statt ihn für den nächsten
                    // Punkt an die MapEventsOverlay durchzulassen.
                    setOnMarkerClickListener { _, _ -> false }
                }
                mapView.overlays.add(marker)
                markerRefs.add(marker)
            }
            if (points.size >= 2) {
                val poly = Polygon(mapView).apply {
                    this.points = points
                    // Halbtransparente Vorschau in der App-Akzentfarbe (Teal),
                    // damit sofort ersichtlich ist, was als Geofence-Fläche
                    // interpretiert würde, bevor "Fertig" gedrückt wird.
                    fillPaint.color = android.graphics.Color.argb(60, 45, 212, 191)
                    outlinePaint.color = android.graphics.Color.rgb(45, 212, 191)
                    outlinePaint.strokeWidth = 4f
                }
                mapView.overlays.add(poly)
                polygonOverlay = poly
            }
            mapView.invalidate()
        }

        // Oben: Anleitung + Punktzähler, halbtransparent über der Karte.
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(BgDark.copy(alpha = 0.85f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text("See-Grenze einzeichnen", color = TextLight, fontSize = 16.sp)
            Text(
                if (points.size < MIN_POINTS) {
                    "Uferpunkte nacheinander antippen — noch mindestens ${MIN_POINTS - points.size} nötig."
                } else {
                    "${points.size} Punkte gesetzt — weiter antippen oder \"Fertig\"."
                },
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
            OutlinedButton(
                onClick = { points = points.dropLast(1) },
                enabled = points.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text("Letzten Punkt entfernen") }
            Button(
                onClick = { onFinish(points.map { GeoPoint(it.latitude, it.longitude) }) },
                enabled = points.size >= MIN_POINTS,
                colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = androidx.compose.ui.graphics.Color(0xFF04201C)),
                modifier = Modifier.weight(1f),
            ) { Text("Fertig") }
        }
    }
}
