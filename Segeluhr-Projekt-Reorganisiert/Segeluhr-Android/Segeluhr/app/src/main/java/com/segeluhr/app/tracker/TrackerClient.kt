package com.segeluhr.app.tracker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Zugang zum Roemis-Regatta-Tracker-Server (siehe docs/Erweiterung_Regatta_Tracker.md).
 * [serverUrl] ohne abschliessenden Slash, z.B. "https://tracker.example.com".
 */
data class TrackerSettings(
    val serverUrl: String = "",
    val trackerId: String = "",
    val token: String = "",
    val enabled: Boolean = false,
) {
    val configured: Boolean get() = serverUrl.isNotBlank() && trackerId.isNotBlank() && token.isNotBlank()
}

/** Eine Position für POST /api/trackers/:id/position (Einheiten wie die Server-API: m/s, Grad, 0..1). */
data class TrackerPosition(
    val lat: Double,
    val lon: Double,
    val accuracyM: Double?,
    val speedMs: Double?,
    val headingDeg: Double?,
    val battery: Double?,
    val timestampMs: Long,
)

data class TrackerResult(val ok: Boolean, val message: String, val body: JSONObject? = null)

object TrackerLink {
    /**
     * Zerlegt den Link aus dem Admin-Bereich des Trackers
     * (`https://host/t/<id>?token=<token>`) in Server/ID/Token. null = nicht erkennbar.
     */
    fun parse(link: String): TrackerSettings? {
        return try {
            val uri = URI(link.trim())
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.authority ?: return null
            if (scheme != "https" && scheme != "http") return null
            val segments = (uri.path ?: "").split('/').filter { it.isNotEmpty() }
            val tIdx = segments.indexOf("t")
            val id = segments.getOrNull(tIdx + 1)?.takeIf { tIdx >= 0 } ?: return null
            val token = (uri.rawQuery ?: "").split('&')
                .map { it.split('=', limit = 2) }
                .firstOrNull { it[0] == "token" && it.size == 2 }?.get(1) ?: return null
            if (token.isBlank()) return null
            TrackerSettings(serverUrl = "$scheme://$host", trackerId = id, token = token)
        } catch (e: Exception) {
            null
        }
    }
}

class TrackerClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    private fun url(s: TrackerSettings, suffix: String) = "${s.serverUrl}/api/trackers/${s.trackerId}/$suffix"

    suspend fun postPosition(s: TrackerSettings, p: TrackerPosition): TrackerResult {
        val body = JSONObject().apply {
            put("lat", p.lat); put("lon", p.lon); put("ts", p.timestampMs)
            p.accuracyM?.let { put("accuracy", it) }
            p.speedMs?.let { put("speed", it) }
            p.headingDeg?.let { put("heading", it) }
            p.battery?.let { put("battery", it) }
        }
        return call(Request.Builder().url(url(s, "position")).header("Authorization", "Bearer ${s.token}")
            .post(body.toString().toRequestBody(json)).build())
    }

    suspend fun getConfig(s: TrackerSettings): TrackerResult =
        call(Request.Builder().url(url(s, "config")).header("Authorization", "Bearer ${s.token}").get().build())

    suspend fun putConfig(s: TrackerSettings, config: JSONObject): TrackerResult =
        call(Request.Builder().url(url(s, "config")).header("Authorization", "Bearer ${s.token}")
            .put(config.toString().toRequestBody(json)).build())

    private suspend fun call(request: Request): TrackerResult = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val parsed = try { JSONObject(text) } catch (e: Exception) { null }
                if (resp.isSuccessful) {
                    TrackerResult(true, "OK", parsed)
                } else {
                    val err = parsed?.optString("error").orEmpty().ifBlank { "HTTP ${resp.code}" }
                    TrackerResult(false, err)
                }
            }
        } catch (e: Exception) {
            TrackerResult(false, "Keine Verbindung (${e.javaClass.simpleName})")
        }
    }
}
