package com.segeluhr.app.data.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Log
import android.view.View
import androidx.core.content.FileProvider
import com.segeluhr.app.core.GeoUtils
import com.segeluhr.app.data.model.SessionKind
import com.segeluhr.app.data.model.SessionReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Erweiterung (17.08.2026, Roman-Wunsch "PDF zum Teilen, so ähnlich wie bei
 * Strava, mit einer kleinen Karte", siehe docs/Erweiterung_Tages_Auswertung.md):
 * baut aus einem [SessionReport] ein Ein-Seiten-PDF (Statistik-Text + echter
 * OSM-Kartenausschnitt mit der gefahrenen Route) und liefert eine
 * teilbare `content://`-Uri übers bestehende FileProvider (wie
 * DiagnosticsLogger.shareUri()).
 *
 * **Kein Bulk-Vorladen möglich** (17.08.2026 versucht + verworfen: osmdroids
 * `CacheManager.downloadAreaAsync()` wäre der offizielle Weg fürs aktive
 * Vorladen eines Kartenausschnitts, wirft aber für die hier genutzte MAPNIK-
 * Quelle (freie OSM-Tile-Server) zur Laufzeit eine
 * `TileSourcePolicyException("doesn't support bulk download")` — osmdroids
 * eingebauter Schutz gegen automatisiertes Massen-Herunterladen von den
 * kostenlosen OSM-Servern. Die Exception fliegt dabei auf einem rohen
 * `AsyncTask`-Hintergrundthread, ausserhalb jeder Kotlin-Coroutine-
 * Fehlerbehandlung — führte beim ersten Versuch zu einem harten App-Absturz
 * statt einer sauberen Fehlermeldung). [renderMapBitmap] nutzt stattdessen
 * die NORMALE (Einzel-Kachel-)Ladelogik, die beim Zeichnen sowieso anläuft,
 * und wartet dafür fest [TILE_LOAD_WAIT_MS] ab, bevor sie die Bitmap zieht —
 * einfacher, aber ohne echtes "fertig geladen"-Signal. Reicht die Zeit bei
 * langsamem Netz nicht, zeigt die PDF-Karte im schlimmsten Fall Lücken im
 * Kachel-Raster (Route/Marker sind davon nicht betroffen). Der komplette
 * Kartenaufbau läuft ausserdem in einem eigenen try/catch — jeder Fehler
 * (Netzwerk, Rendering, ...) lässt das PDF ohne Karte statt gar nicht
 * entstehen; siehe [export].
 */
object SessionPdfExporter {

    private const val TAG = "SessionPdfExporter"
    private const val MAP_WIDTH_PX = 900
    private const val MAP_HEIGHT_PX = 560
    private const val TILE_LOAD_WAIT_MS = 8000L

    suspend fun export(context: Context, report: SessionReport): Uri = withContext(Dispatchers.Main) {
        val mapBitmap = try {
            renderMapBitmap(context, report)
        } catch (e: Exception) {
            Log.w(TAG, "Kartenausschnitt fürs PDF konnte nicht gerendert werden, PDF wird ohne Karte erzeugt.", e)
            null
        }
        val file = writePdf(context, report, mapBitmap)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private suspend fun renderMapBitmap(context: Context, report: SessionReport): Bitmap? {
        if (report.route.isEmpty()) return null
        val osmdroidDir = context.cacheDir.resolve("osmdroid")
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = osmdroidDir
            osmdroidTileCache = osmdroidDir.resolve("tiles")
            // Standard sind nur 2 parallele Downloads (DefaultConfigurationProvider) -
            // für den einmaligen Vorlade-Stoss hier bewusst höher, damit die
            // ~15-30 sichtbaren Kacheln schneller alle durch sind.
            tileDownloadThreads = 8
            tileDownloadMaxQueueSize = 40
        }
        val mapView = MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(false)
        }
        mapView.measure(
            View.MeasureSpec.makeMeasureSpec(MAP_WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(MAP_HEIGHT_PX, View.MeasureSpec.EXACTLY),
        )
        mapView.layout(0, 0, MAP_WIDTH_PX, MAP_HEIGHT_PX)
        // zoomToRoute(..., immediate = false) hängt das eigentliche Zoomen an
        // mapView.post() - die View ist nie an ein Fenster angehängt, dieser
        // Callback feuert also ggf. nie. Jetzt, wo measure()/layout() eine
        // feste Grösse gesetzt haben, direkt selbst zoomen (kein post() nötig).
        com.segeluhr.app.ui.components.zoomToRoute(mapView, report.route, immediate = true)

        val bitmap = Bitmap.createBitmap(MAP_WIDTH_PX, MAP_HEIGHT_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Bugfix (17.08.2026, Roman-Feedback "PDF-Karte ist immer noch nur
        // Kacheln [gemeint: nur das Platzhalter-Raster]" — der reine
        // delay()-Fix half NICHT): osmdroids TilesOverlay stösst das
        // asynchrone Nachladen fehlender Kacheln erst BEIM ZEICHNEN an
        // (draw() prüft den Cache, zeigt bei Fehlanzeigen sofort den
        // Platzhalter UND feuert im Hintergrund einen Ladeauftrag ab). Ohne
        // window-gebundene View gibt es aber kein automatisches Redraw nach
        // Abschluss - dieser Code rief bisher draw() nur EIN EINZIGES Mal
        // NACH der Wartezeit auf, wodurch der Ladeauftrag erst in genau
        // diesem Moment lostrat und die Wartezeit komplett wirkungslos
        // verpuffte. Fix: EINMAL VOR der Wartezeit zeichnen (stösst die
        // Downloads an), dann warten, dann ERNEUT zeichnen (liest jetzt die
        // inzwischen gefüllten Kachel-Caches).
        mapView.draw(canvas) // Ladeaufträge anstossen
        delay(TILE_LOAD_WAIT_MS) // siehe Klassendoku "Kein Bulk-Vorladen möglich"
        mapView.draw(canvas) // fertig geladene Kacheln einfangen
        mapView.onDetach() // osmdroid-Cleanup (stoppt Tile-Lade-Threads), analog zu Activity.onDestroy()
        return bitmap
    }

    private fun writePdf(context: Context, report: SessionReport, mapBitmap: Bitmap?): File {
        val pageWidth = 595 // A4-Breite bei 72dpi
        val pageHeight = 900
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create())
        val canvas = page.canvas

        val titlePaint = Paint().apply { color = Color.BLACK; textSize = 22f; isFakeBoldText = true }
        val subtitlePaint = Paint().apply { color = Color.DKGRAY; textSize = 12f }
        val sectionPaint = Paint().apply { color = Color.DKGRAY; textSize = 11f; isFakeBoldText = true }
        val labelPaint = Paint().apply { color = Color.DKGRAY; textSize = 13f }
        val valuePaint = Paint().apply { color = Color.BLACK; textSize = 13f; isFakeBoldText = true; textAlign = Paint.Align.RIGHT }

        var y = 50f
        val marginX = 40f
        val rightX = pageWidth - marginX

        canvas.drawText("⛵ Segeluhr — ${sessionTitle(report)}", marginX, y, titlePaint)
        y += 20f
        canvas.drawText(
            "Gestartet ${SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.GERMANY).format(Date(report.startedAtMs))}",
            marginX, y, subtitlePaint,
        )
        y += 26f

        fun row(label: String, value: String) {
            canvas.drawText(label, marginX, y, labelPaint)
            canvas.drawText(value, rightX, y, valuePaint)
            y += 20f
        }
        fun section(title: String) {
            y += 6f
            canvas.drawText(title.uppercase(), marginX, y, sectionPaint)
            y += 16f
        }

        row("Dauer", GeoUtils.fmtDuration(report.durationS.toDouble()))
        row("Distanz", GeoUtils.fmtDist(report.distanceM))
        row("Max. Speed", "%.1f kn".format(report.maxSpeedKn))
        row("Ø Speed", report.avgSpeedKn?.let { "%.1f kn".format(it) } ?: "--")

        section("Manöver")
        row("Wenden", report.tackCount.toString() + (report.avgTackAngleDeg?.let { " (Ø %.0f°)".format(it) } ?: ""))
        row("Halsen", report.gybeCount.toString() + (report.avgGybeAngleDeg?.let { " (Ø %.0f°)".format(it) } ?: ""))

        section("Wind")
        row("Shifts erkannt", "${report.windShiftCount} (${report.windShiftHeaderCount} Header / ${report.windShiftLiftCount} Lift)")
        row("Windkalibrierungen", report.windCalibrationCount.toString())
        row("Wendewinkel (gelernt)", "%.0f°".format(report.finalClosehauledAngleDeg))
        row("Vorwind-Winkel (gelernt)", "%.0f°".format(report.finalDownwindAngleDeg))

        if (report.watchConnectedPct != null) {
            section("Uhr")
            row("Verbindung", "%.0f%% der Zeit".format(report.watchConnectedPct))
        }

        y += 14f
        if (mapBitmap != null) {
            val destWidth = pageWidth - 2 * marginX
            val destHeight = destWidth * mapBitmap.height / mapBitmap.width
            val destRect = Rect(marginX.toInt(), y.toInt(), (marginX + destWidth).toInt(), (y + destHeight).toInt())
            canvas.drawBitmap(mapBitmap, null, destRect, null)
            y += destHeight + 16f
            canvas.drawText("© OpenStreetMap-Mitwirkende", marginX, y, subtitlePaint)
        } else {
            canvas.drawText("Keine Route aufgezeichnet.", marginX, y, subtitlePaint)
        }

        doc.finishPage(page)

        val dir = File(context.filesDir, "reports").apply { mkdirs() }
        val fileTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMANY).format(Date())
        val file = File(dir, "segeluhr_${report.kind.name.lowercase()}_$fileTimestamp.pdf")
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    private fun sessionTitle(report: SessionReport) = when (report.kind) {
        SessionKind.DAY -> "Tages-Auswertung"
        SessionKind.RACE -> "Wettfahrt-Auswertung"
    }
}
