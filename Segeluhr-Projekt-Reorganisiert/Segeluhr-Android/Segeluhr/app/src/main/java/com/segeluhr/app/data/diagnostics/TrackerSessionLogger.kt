package com.segeluhr.app.data.diagnostics

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.segeluhr.app.ble.GatewayPacket
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Zeichnet eine Tracker-Session als CSV auf (Erweiterung 12.11.2026, siehe
 * docs/Erweiterung_Geraete_Logik.md "Tracker-Session aufzeichnen"), analog zum
 * [DiagnosticsLogger]: eigene Positionen UND alle über das Relais
 * (RegattaMaster) empfangenen Positionen fremder LoRa-Tracker, in einer Datei,
 * unterscheidbar über die Spalte `source`.
 *
 * Unterschiede zum [DiagnosticsLogger]: Der Mitschnitt wird bewusst **explizit**
 * gestartet und beendet (Standard AUS, enthält Positionen anderer Boote) und
 * legt pro Aufnahme eine neue Datei an. Eine Zeile pro Position statt pro
 * 1-Hz-Tick. Ablage in `filesDir/trackerlog/`, Teilen über den FileProvider
 * (`res/xml/file_paths.xml`, Eintrag "trackerlog").
 *
 * Es wird **alles** protokolliert, was ankommt, auch Pakete ohne GPS-Fix
 * (Spalte `gps_fix` = 0, Position dann typischerweise 0,0): fürs Auswerten ist
 * "Tracker lebt, aber ohne Fix" eine wichtige Information. Nach jeder Zeile
 * `flush()`, damit ein App-Absturz keine Aufnahme kostet.
 *
 * Neue Spalten IMMER ans Ende anhängen (gleiche Regel wie beim Diagnose-Log).
 */
class TrackerSessionLogger(private val context: Context) {

    private val dir: File by lazy { File(context.filesDir, "trackerlog").apply { mkdirs() } }

    private var writer: FileWriter? = null
    private var file: File? = null

    var rowCount: Int = 0
        private set
    val currentFileName: String? get() = file?.name
    val isRecording: Boolean get() = writer != null

    private val isoTime = SimpleDateFormat("HH:mm:ss", Locale.GERMANY)
    private val fileTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMANY)

    private val header = listOf(
        "ts_iso", "ts_epoch_ms", "source", "tracker_id", "pkt_ts_ms",
        "lat", "lon", "speed_ms", "heading_deg", "battery_pct",
        "hdop", "rssi_dbm", "snr_db", "gps_fix", "accuracy_m", "event",
    ).joinToString(",")

    /** Startet eine neue Aufnahme (neue Datei). Läuft schon eine, passiert nichts. */
    @Synchronized
    fun start() {
        if (writer != null) return
        val f = File(dir, "trackerlog_${fileTimestamp.format(Date())}.csv")
        file = f
        writer = FileWriter(f, true).apply { write(header + "\n"); flush() }
        rowCount = 0
    }

    /** Beendet die Aufnahme; Datei und Zeilenzähler bleiben für "Teilen" erhalten. */
    @Synchronized
    fun stop() {
        writer?.flush()
        writer?.close()
        writer = null
    }

    /** Position eines fremden LoRa-Trackers (aus dem Relais). */
    @Synchronized
    fun logTracker(p: GatewayPacket) = writeRow(
        source = "TRACKER", id = p.loraId.toString(), pktTs = p.timestampMs,
        lat = p.lat, lon = p.lon, speedMs = p.speedMs, heading = p.headingDeg,
        batteryPct = p.batteryPct, hdop = p.hdop, rssi = p.rssi, snr = p.snrDb,
        gpsFix = p.hasGpsFix, accuracy = null, event = "",
    )

    /** Eigene Position (Handy-GPS). */
    @Synchronized
    fun logOwn(
        timestampMs: Long, lat: Double?, lon: Double?, speedMs: Double?, heading: Double?,
        batteryPct: Int?, gpsFix: Boolean, accuracyM: Double?,
    ) = writeRow(
        source = "EIGEN", id = "own", pktTs = timestampMs,
        lat = lat, lon = lon, speedMs = speedMs, heading = heading,
        batteryPct = batteryPct, hdop = null, rssi = null, snr = null,
        gpsFix = gpsFix, accuracy = accuracyM, event = "",
    )

    /** Manueller Marker (Setup-Tab "Ereignis markieren"), wie beim Diagnose-Log. */
    @Synchronized
    fun markEvent(note: String) = writeRow(
        source = "EVENT", id = "", pktTs = null, lat = null, lon = null, speedMs = null, heading = null,
        batteryPct = null, hdop = null, rssi = null, snr = null, gpsFix = null, accuracy = null,
        event = note.ifBlank { "MARKER" },
    )

    private fun writeRow(
        source: String, id: String, pktTs: Long?, lat: Double?, lon: Double?, speedMs: Double?,
        heading: Double?, batteryPct: Int?, hdop: Double?, rssi: Int?, snr: Double?,
        gpsFix: Boolean?, accuracy: Double?, event: String,
    ) {
        val w = writer ?: return // nicht aufnehmend: still ignorieren
        val now = System.currentTimeMillis()
        val cells = listOf(
            isoTime.format(Date(now)), now.toString(), source, id, pktTs?.toString() ?: "",
            lat?.toString() ?: "", lon?.toString() ?: "", speedMs?.toString() ?: "",
            heading?.toString() ?: "", batteryPct?.toString() ?: "",
            hdop?.toString() ?: "", rssi?.toString() ?: "", snr?.toString() ?: "",
            gpsFix?.let { if (it) "1" else "0" } ?: "", accuracy?.toString() ?: "", csvEscape(event),
        )
        w.write(cells.joinToString(",") + "\n")
        w.flush()
        rowCount++
    }

    private fun csvEscape(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    /** Content-URI der aktuellen/letzten Aufnahme fürs Teilen — null, falls noch nie aufgenommen wurde. */
    @Synchronized
    fun shareUri(): Uri? {
        val f = file ?: return null
        writer?.flush()
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
    }

    fun close() = stop()
}
