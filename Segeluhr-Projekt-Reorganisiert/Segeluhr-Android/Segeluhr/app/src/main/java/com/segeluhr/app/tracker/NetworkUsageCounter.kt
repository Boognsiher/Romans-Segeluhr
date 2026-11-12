package com.segeluhr.app.tracker

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** Wofür eine HTTP-Anfrage der App gebraucht wurde (Aufschlüsselung im Setup-Tab). */
enum class UsageCategory(val label: String) {
    OWN_POSITION("Eigene Position"),
    GATEWAY("Relais (fremde Tracker)"),
    CONFIG("Konfiguration"),
}

/** Summen für eine [UsageCategory]. Bytes auf Anwendungsebene, siehe [NetworkUsageCounter]. */
data class UsageBucket(
    val sentBytes: Long = 0,
    val receivedBytes: Long = 0,
    val requests: Int = 0,
    val failedRequests: Int = 0,
)

/**
 * Stand des Datenvolumen-Zählers. [session] = seit App-Start (nur im Speicher),
 * [total] = seit dem letzten Zurücksetzen (überlebt App-Neustarts), [totalSinceMs]
 * = Zeitpunkt dieses Zurücksetzens (bzw. der ersten Messung).
 */
data class NetworkUsage(
    val session: Map<UsageCategory, UsageBucket> = emptyMap(),
    val total: Map<UsageCategory, UsageBucket> = emptyMap(),
    val totalSinceMs: Long = 0,
) {
    val sessionSent: Long get() = session.values.sumOf { it.sentBytes }
    val sessionReceived: Long get() = session.values.sumOf { it.receivedBytes }
    val totalSent: Long get() = total.values.sumOf { it.sentBytes }
    val totalReceived: Long get() = total.values.sumOf { it.receivedBytes }
}

/**
 * Misst das über die App ans Server-Netz gesendete/empfangene Datenvolumen
 * (Erweiterung 12.11.2026, siehe docs/Erweiterung_Geraete_Logik.md
 * "Datenvolumen"), per OkHttp-[Interceptor] an den beiden Clients
 * ([TrackerClient], [GatewayUploader]).
 *
 * **Was gezählt wird:** Request-Zeile + Header + Body sowie Response-Statuszeile
 * + Header + Body, also die HTTP-Nutzlast auf Anwendungsebene. Header, die
 * OkHttp erst weiter unten selbst ergänzt (Host, User-Agent, Content-Length,
 * Connection, Accept-Encoding), werden pauschal mit [EXTRA_REQUEST_HEADER_BYTES]
 * dazugerechnet.
 * **Was NICHT gezählt wird:** TLS-Handshake/-Records und TCP/IP-Header samt ACKs.
 * Der echte Verbrauch laut Mobilfunkanbieter liegt deshalb etwas höher, grob
 * [PROTOCOL_OVERHEAD_FACTOR] (Schätzwert, nicht gemessen). Fehlgeschlagene
 * Anfragen ohne Verbindung zählen nur mit der Request-Seite.
 *
 * Nicht erfasst ist der Datenverkehr der Zuschauer-WebView und der OSM-Karten
 * (osmdroid) — beides läuft nicht über diese Clients.
 */
class NetworkUsageCounter(context: Context) {

    companion object {
        /** Pauschal für von OkHttp später ergänzte Request-Header (Host, User-Agent, Content-Length, ...). */
        const val EXTRA_REQUEST_HEADER_BYTES = 120L
        /** Grober Aufschlag für TLS + TCP/IP über die gezählte Nutzlast (Schätzung, siehe Klassendoku). */
        const val PROTOCOL_OVERHEAD_FACTOR = 1.25
        private const val PEEK_LIMIT = 1_048_576L
        private const val PREFS = "network_usage"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(NetworkUsage(total = loadTotals(), totalSinceMs = loadSince()))
    val state: StateFlow<NetworkUsage> = _state.asStateFlow()

    /** Zählt jede Anfrage dieses Clients; die Kategorie kommt aus dem Request-Tag (Default: eigene Position). */
    val interceptor: Interceptor = Interceptor { chain ->
        val req = chain.request()
        val category = req.tag(UsageCategory::class.java) ?: UsageCategory.OWN_POSITION
        val sent = requestBytes(req)
        try {
            val resp = chain.proceed(req)
            record(category, sent, responseBytes(resp), failed = !resp.isSuccessful)
            resp
        } catch (e: IOException) {
            record(category, sent, 0, failed = true)
            throw e
        }
    }

    /** Setzt die dauerhafte Summe zurück (die Session-Summe seit App-Start bleibt bewusst unverändert). */
    @Synchronized
    fun resetTotal() {
        prefs.edit().clear().putLong("since", System.currentTimeMillis()).apply()
        _state.value = _state.value.copy(total = emptyMap(), totalSinceMs = System.currentTimeMillis())
    }

    @Synchronized
    private fun record(category: UsageCategory, sent: Long, received: Long, failed: Boolean) {
        fun UsageBucket?.add() = (this ?: UsageBucket()).let {
            UsageBucket(
                sentBytes = it.sentBytes + sent,
                receivedBytes = it.receivedBytes + received,
                requests = it.requests + 1,
                failedRequests = it.failedRequests + if (failed) 1 else 0,
            )
        }
        val cur = _state.value
        val newTotal = cur.total + (category to cur.total[category].add())
        val newSession = cur.session + (category to cur.session[category].add())
        prefs.edit().apply {
            val b = newTotal.getValue(category)
            putLong("${category.name}_sent", b.sentBytes)
            putLong("${category.name}_recv", b.receivedBytes)
            putInt("${category.name}_req", b.requests)
            putInt("${category.name}_fail", b.failedRequests)
            if (!prefs.contains("since")) putLong("since", cur.totalSinceMs.takeIf { it > 0 } ?: System.currentTimeMillis())
        }.apply()
        _state.value = cur.copy(
            session = newSession,
            total = newTotal,
            totalSinceMs = cur.totalSinceMs.takeIf { it > 0 } ?: System.currentTimeMillis(),
        )
    }

    private fun loadTotals(): Map<UsageCategory, UsageBucket> =
        UsageCategory.values().mapNotNull { c ->
            if (!prefs.contains("${c.name}_req")) null
            else c to UsageBucket(
                sentBytes = prefs.getLong("${c.name}_sent", 0),
                receivedBytes = prefs.getLong("${c.name}_recv", 0),
                requests = prefs.getInt("${c.name}_req", 0),
                failedRequests = prefs.getInt("${c.name}_fail", 0),
            )
        }.toMap()

    private fun loadSince(): Long = prefs.getLong("since", 0L)

    private fun requestBytes(req: Request): Long {
        val line = req.method.length + 1 + req.url.encodedPath.length +
            (req.url.encodedQuery?.let { it.length + 1 } ?: 0) + " HTTP/1.1\r\n".length
        val body = req.body?.contentLength()?.takeIf { it >= 0 } ?: 0L
        return line + req.headers.byteCount() + EXTRA_REQUEST_HEADER_BYTES + body
    }

    private fun responseBytes(resp: Response): Long {
        val statusLine = "HTTP/1.1 ${resp.code} ${resp.message}\r\n".length
        val body = resp.body?.let { b ->
            // contentLength() ist -1 bei chunked/transparent entpacktem gzip - dann den (ggf. entpackten)
            // Inhalt per peekBody() messen, ohne ihn für den Aufrufer zu verbrauchen.
            b.contentLength().takeIf { it >= 0 } ?: resp.peekBody(PEEK_LIMIT).contentLength()
        } ?: 0L
        return statusLine + resp.headers.byteCount() + body
    }
}
