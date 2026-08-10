package com.segeluhr.app.geo

import com.segeluhr.app.core.GeoPoint
import com.segeluhr.app.core.LakeCircle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Automatische See-Erkennung, siehe docs/Erweiterung_Automatische_See_Erkennung.md.
 *
 * ABWEICHUNG von der ursprünglichen Doku (Roman-Entscheidung 06.08.2026):
 * `LakeGeofenceEngine` kannte ursprünglich nur einen einzelnen Kreis
 * (Mittelpunkt + Radius), keine echte Polygon-Geofence — die volle
 * Doku-Vision (OSM-Uferlinie, JTS-Pufferung, Douglas-Peucker, Multipolygone)
 * würde einen kompletten Umbau der Engine brauchen. Stattdessen: Uferlinie
 * per Overpass-API laden, daraus eine KETTE von Kreisen entlang der
 * Mittellinie packen (siehe [CirclePacking.packChain]) — deckt auch lange/
 * unregelmässige Seen ab, ohne die volle Polygon-Geofence bauen zu müssen
 * (zweite Roman-Entscheidung 06.08.2026, nachdem ein einzelner Kreis bei
 * länglichen Seen an den Enden ständig falsch gewarnt hätte).
 * `LakeGeofenceEngine` wurde dafür auf `List<LakeCircle>` umgestellt (statt
 * Polygon-Distanzberechnung) — ein deutlich kleinerer Umbau als volles JTS.
 *
 * Jeder einzelne Kreis-Radius entspricht demselben Prinzip wie beim
 * manuellen "See-Rand erfassen" (siehe SettingsRepository: kleinster
 * gemessener Abstand zum Ufer wird als Radius übernommen) — hier eben der
 * exakt kleinste (= grösstmögliche sichere) Abstand von einem optimal
 * gewählten Mittelpunkt zur nächsten Uferlinie, statt weniger Handmessungen.
 * Die eigentliche Kreis-Packung sitzt seit 10.08.2026 in [CirclePacking]
 * (quellen-unabhängig, siehe dortige Klassendoku) — diese Klasse liefert
 * nur noch die OSM-spezifische Beschaffung/Auswahl des Polygons.
 *
 * BEKANNTE EINSCHRÄNKUNG: nur einfache OSM-"way"-Geometrien (natural=water
 * auf einem einzelnen geschlossenen Weg) werden ausgewertet. Grössere/
 * komplexere Seen sind in OSM oft als "relation" (Multipolygon, z.B. mit
 * Inseln oder aus mehreren Uferabschnitten zusammengesetzt) erfasst — das
 * Zusammensetzen solcher Relationen ist laut Doku "nicht trivial" und war
 * genau der Teil, der mit der einfacheren Kreis-Lösung vermieden werden
 * sollte. Falls der Zielsee als Relation vorliegt, schlägt die Erkennung
 * fehl (klare Fehlermeldung) — für genau diesen Fall gibt's seit 10.08.2026
 * die manuelle Karten-Zeichnen-Funktion (siehe
 * docs/Erweiterung_Seegrenze_Zeichnen.md), zusätzlich zum bisherigen
 * GPS-Rand-Abfahren.
 */
object LakeAutoDetector {

    private val client = OkHttpClient()
    private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
    private const val SEARCH_RADIUS_M = 5000
    // Wie nah der aktuelle GPS-Punkt an einer Uferlinie liegen darf, um sie
    // noch als "das ist der gemeinte See" zu akzeptieren, falls er nicht
    // exakt IM Polygon liegt (z.B. Boot/Steg direkt am Ufer beim Einrichten,
    // siehe Doku Abschnitt 5, offener Punkt).
    private const val EDGE_TOLERANCE_M = 50.0

    data class DetectedLake(val circles: List<LakeCircle>, val name: String?)

    sealed class Result {
        data class Success(val lake: DetectedLake) : Result()
        data class Failure(val message: String) : Result()
    }

    suspend fun detect(fix: GeoPoint): Result = withContext(Dispatchers.IO) {
        val json = try {
            fetchOverpassJson(fix)
        } catch (e: Exception) {
            return@withContext Result.Failure("Kein Internetzugang oder Overpass-API nicht erreichbar: ${e.message}")
        }

        val candidates = try {
            parseWaterWays(json, fix)
        } catch (e: Exception) {
            return@withContext Result.Failure("Antwort der Overpass-API konnte nicht gelesen werden: ${e.message}")
        }

        if (candidates.isEmpty()) {
            return@withContext Result.Failure(
                "Kein See in der Nähe gefunden (oder nur als komplexe OSM-Relation " +
                    "erfasst, siehe bekannte Einschränkung) — bitte See-Mitte/-Rand manuell setzen."
            )
        }

        // Projektion in lokale Meter um den GPS-Fix als Ursprung (siehe
        // Klassenkommentar: einfache Äquirektangular-Projektion reicht für
        // einen einzelnen See, keine UTM-Zonen-Logik nötig). Nur für die
        // Auswahl DES richtigen Sees bei mehreren Treffern - die eigentliche
        // Kreis-Packung (weiter unten) projiziert unabhängig davon nochmal
        // selbst, siehe CirclePacking.packChain-Doku.
        val proj = CirclePacking.LocalProjection(fix)
        val polygons = candidates.map { it to proj.toLocal(it.points) }

        // Enthält der Fix-Punkt eines der Polygone? Bei mehreren Treffern
        // (z.B. kleiner Nebenteich) die grösste Fläche wählen (Doku Abschnitt 2).
        val origin = CirclePacking.Vec2(0.0, 0.0) // fix liegt per Definition der Projektion bei (0,0)
        val containing = polygons.filter { (_, localPts) -> CirclePacking.pointInPolygon(origin, localPts) }
        val chosen = if (containing.isNotEmpty()) {
            containing.maxByOrNull { (_, localPts) -> polygonArea(localPts) }
        } else {
            // Fix liegt in keinem Polygon (z.B. noch am Ufer stehend) - dem
            // nächstgelegenen innerhalb der Toleranz folgen.
            polygons.minByOrNull { (_, localPts) -> CirclePacking.minDistToBoundary(origin, localPts) }
                ?.takeIf { (_, localPts) -> CirclePacking.minDistToBoundary(origin, localPts) <= EDGE_TOLERANCE_M }
        }

        if (chosen == null) {
            return@withContext Result.Failure(
                "Nächster See ist mehr als ${EDGE_TOLERANCE_M.toInt()}m entfernt — " +
                    "bitte näher ans Wasser oder See-Mitte/-Rand manuell setzen."
            )
        }

        val (way, _) = chosen
        val circles = CirclePacking.packChain(way.points)
        if (circles.isEmpty()) {
            return@withContext Result.Failure(
                "See gefunden, aber zu klein/schmal für einen sinnvollen Sicherheits-" +
                    "kreis (< ${CirclePacking.MIN_CIRCLE_RADIUS_M.toInt()}m) — bitte See-Mitte/-Rand manuell setzen."
            )
        }

        Result.Success(DetectedLake(circles, way.name))
    }

    private fun fetchOverpassJson(fix: GeoPoint): JSONObject {
        val query = """
            [out:json][timeout:25];
            way["natural"="water"](around:$SEARCH_RADIUS_M,${fix.lat},${fix.lon});
            out geom;
        """.trimIndent()
        val body = "data=$query".toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val request = Request.Builder().url(OVERPASS_URL).post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val text = response.body?.string() ?: throw Exception("leere Antwort")
            return JSONObject(text)
        }
    }

    private data class WaterWay(val points: List<GeoPoint>, val name: String?)

    /** Extrahiert geschlossene "way"-Geometrien mit natural=water aus der Overpass-Antwort. */
    private fun parseWaterWays(json: JSONObject, fix: GeoPoint): List<WaterWay> {
        val elements = json.optJSONArray("elements") ?: return emptyList()
        val result = mutableListOf<WaterWay>()
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            if (el.optString("type") != "way") continue // Relationen (Multipolygone) bewusst nicht unterstützt, siehe Klassenkommentar
            val geom = el.optJSONArray("geometry") ?: continue
            if (geom.length() < 3) continue
            val points = (0 until geom.length()).map { j ->
                val pt = geom.getJSONObject(j)
                GeoPoint(pt.getDouble("lat"), pt.getDouble("lon"))
            }
            // Geschlossener Ring (OSM-Konvention für Flächen: erster == letzter Punkt)?
            val first = points.first(); val last = points.last()
            val isClosed = kotlin.math.abs(first.lat - last.lat) < 1e-7 && kotlin.math.abs(first.lon - last.lon) < 1e-7
            if (!isClosed) continue
            val name = el.optJSONObject("tags")?.optString("name")?.takeIf { it.isNotBlank() }
            result.add(WaterWay(points, name))
        }
        return result
    }

    // ---- Nur noch die für die Seeauswahl (bei mehreren Treffern) nötige
    // ---- Flächenberechnung — Punkt-in-Polygon/Abstand/Kreis-Packung sitzen
    // ---- jetzt in CirclePacking (quellen-unabhängig, siehe dortige Doku).

    /** Fläche via Gauß'scher Trapezformel (Shoelace) - für den Grössenvergleich bei mehreren Treffern. */
    private fun polygonArea(poly: List<CirclePacking.Vec2>): Double {
        var sum = 0.0
        var j = poly.size - 1
        for (i in poly.indices) {
            sum += (poly[j].x + poly[i].x) * (poly[j].y - poly[i].y)
            j = i
        }
        return kotlin.math.abs(sum / 2.0)
    }
}
