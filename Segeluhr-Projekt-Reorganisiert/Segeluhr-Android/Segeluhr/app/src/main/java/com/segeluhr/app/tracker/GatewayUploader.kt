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

class GatewayUploader {

    private val http = OkHttpClient.Builder()
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
                .post(body.toString().toRequestBody(json))
                .build()
            try {
                http.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    val parsed = try { JSONObject(text) } catch (e: Exception) { null }
                    if (resp.isSuccessful) {
                        GatewayUploadResult(true, "OK", parsed?.optInt("accepted", positions.size) ?: positions.size)
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
