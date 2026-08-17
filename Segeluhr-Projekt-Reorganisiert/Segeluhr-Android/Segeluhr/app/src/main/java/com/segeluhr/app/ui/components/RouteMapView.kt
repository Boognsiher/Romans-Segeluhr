package com.segeluhr.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.segeluhr.app.core.GeoPoint
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * Erweiterung (17.08.2026, siehe docs/Erweiterung_Tages_Auswertung.md):
 * Live-Kartenansicht der gefahrenen Route im Verlauf-Detail-Screen —
 * gleiches osmdroid-Grundmuster wie [org.osmdroid.views.MapView]-Nutzung in
 * LakeDrawScreen.kt/WaypointMapPickScreen.kt, aber rein anzeigend (kein
 * Tap-Handling, keine Bearbeitung).
 */
@Composable
fun RouteMapView(route: List<GeoPoint>, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        val osmdroidDir = context.cacheDir.resolve("osmdroid")
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = osmdroidDir
            osmdroidTileCache = osmdroidDir.resolve("tiles")
        }
    }

    // Bugfix (17.08.2026, Roman-Feedback "Karte teils überschnitten der
    // Auswertung", auch nach dem weight(1f)-Layout-Fix noch reproduziert):
    // die native osmdroid-MapView clippt ihr eigenes Zeichnen nicht auf die
    // von Compose zugewiesene Box - ohne explizites clipToBounds() malt sie
    // über ihre 240dp-Höhe hinaus in den darunterliegenden Auswertungs-Text
    // hinein, obwohl die MESSUNG/Positionierung selbst korrekt ist. Box +
    // clipToBounds() erzwingt den Schnitt unabhängig vom genauen Compose-
    // AndroidView-Interop-Mechanismus dahinter.
    Box(modifier.clipToBounds()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    overlays.add(CopyrightOverlay(ctx)) // Pflicht-Attribution für OSM-Kartendaten (ODbL)
                    zoomToRoute(this, route, immediate = false)
                }
            },
            update = { mapView ->
                mapView.overlays.removeAll { it is Polyline || it is Marker }
                zoomToRoute(mapView, route, immediate = false)
            },
        )
    }
}

/**
 * Route-Overlay (Linie + Start-/Endmarker) einzeichnen und Kartenausschnitt
 * darauf zoomen — von [RouteMapView] (Compose, `immediate = false`, siehe
 * unten) und dem PDF-Kartenschnappschuss (siehe
 * data/pdf/SessionPdfExporter.kt, `immediate = true`) gemeinsam genutzt.
 *
 * [immediate]: `zoomToBoundingBox()` braucht eine schon gemessene
 * View-Grösse. Im Compose-`AndroidView` ist die View beim Erstellen noch
 * nicht layoutet - `post()` wartet auf den nächsten Layout-Pass (Standard-
 * Workaround). Der PDF-Exporter misst/layoutet die MapView dagegen VORHER
 * selbst synchron und braucht deshalb kein `post()` (das bei einer nie ans
 * Fenster angehängten View unter Umständen nie feuert).
 */
fun zoomToRoute(mapView: MapView, route: List<GeoPoint>, immediate: Boolean) {
    if (route.isEmpty()) {
        mapView.controller.setZoom(8.0)
        mapView.controller.setCenter(OsmGeoPoint(47.0, 8.0))
        return
    }
    val osmPoints = route.map { OsmGeoPoint(it.lat, it.lon) }
    val line = Polyline(mapView).apply {
        setPoints(osmPoints)
        outlinePaint.color = android.graphics.Color.parseColor("#00B8D4")
        outlinePaint.strokeWidth = 7f
    }
    mapView.overlays.add(line)
    mapView.overlays.add(Marker(mapView).apply { position = osmPoints.first(); title = "Start" })
    if (osmPoints.size > 1) {
        mapView.overlays.add(Marker(mapView).apply { position = osmPoints.last(); title = "Ende" })
    }
    val bbox = if (osmPoints.size == 1) {
        BoundingBox(osmPoints[0].latitude + 0.002, osmPoints[0].longitude + 0.002, osmPoints[0].latitude - 0.002, osmPoints[0].longitude - 0.002)
    } else {
        BoundingBox.fromGeoPointsSafe(osmPoints)
    }
    if (immediate) mapView.zoomToBoundingBox(bbox, false, 60) else mapView.post { mapView.zoomToBoundingBox(bbox, false, 60) }
}
