package com.segeluhr.app.tracker

import com.segeluhr.app.data.db.GatewayPositionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Zugang zu POST /api/gateway/positions auf demselben Roemis-Regatta-Tracker-
 * Server wie [TrackerClient] - siehe docs/Erweiterung_Regatta_Tracker_Gateway.md.
 * Eigene, kleinere Klasse statt Erweiterung von [TrackerClient]: anderer
 * Auth-Header (x-gateway-token statt Bearer), anderer Body (Batch mehrerer
 * Fremdboote statt einer einzigen eigenen Position), eigenes Secret.
 */
data class GatewayUploadResult(val ok: Boolean, val message: String, val acceptedCount: Int = 0)

class GatewayUploader(usage: NetworkUsageCounter? = null) {

    private val http = OkHttpClient.Builder()
        .apply { usage?.let { addInterceptor(it.interceptor) } }
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    /** [serverUrl] ohne abschliessenden Slash (gleiches Feld wie [TrackerSettings.serverUrl]). */
    suspend fun postPositions(serverUrl: String, token: String, positions: List<GatewayPositionEntity>): GatewayUploadResult =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put(
                "positions",
                JSONArray().apply {
                    positions.forEach { p ->
                        put(
                            JSONObject().apply {
                                put("id", p.loraId)
                                if (p.heartbeat) {
                                    // Lebenszeichen ohne GPS-Fix (17.11.2026): der Server laesst die Position unveraendert und merkt sich nur
                                    // Status (Akku, Empfang, GPS-Zustand, zuletzt gehoert). Siehe docs/GATEWAY_PROTOCOL.md im Regatta-Repo.
                                    put("fix", false)
                                    put("seenTs", p.timestampMs)
                                    p.batteryFraction?.let { put("battery", it) }
                                    p.rssi?.let { put("rssi", it) }
                                    p.snrDb?.let { put("snr", it) }
                                    p.gpsState?.let { put("gpsState", it) }
                                    p.sats?.let { put("sats", it) }
                                    return@apply
                                }
                                put("lat", p.lat)
                                put("lon", p.lon)
                                put("ts", p.timestampMs)
                                p.headingDeg?.let { put("heading", it) }
                                p.batteryFraction?.let { put("battery", it) }
                                p.accuracyM?.let { put("accuracy", it) }
                            }
                        )
                    }
                }
            )
            val request = Request.Builder()
                .url("$serverUrl/api/gateway/positions")
                .header("x-gateway-token", token)
                .tag(UsageCategory::class.java, UsageCategory.GATEWAY)
                .post(body.toString().toRequestBody(json))
                .build()
            try {
                http.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    val parsed = try { JSONObject(text) } catch (e: Exception) { null }
                    if (resp.isSuccessful) {
                        GatewayUploadResult(
                            true, "OK",
                            // Antwort: "accepted" = Positionen, "heartbeats" = Lebenszeichen ohne Fix (17.11.2026)
                            parsed?.let { it.optInt("accepted", 0) + it.optInt("heartbeats", 0) } ?: positions.size,
                        )
                    } else {
                        val err = parsed?.optString("error").orEmpty().ifBlank { "HTTP ${resp.code}" }
                        GatewayUploadResult(false, err)
                    }
                }
            } catch (e: Exception) {
                GatewayUploadResult(false, "Keine Verbindung (${e.javaClass.simpleName})")
            }
        }
}
