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
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Automatische See-Erkennung, siehe docs/Erweiterung_Automatische_See_Erkennung.md.
 *
 * ABWEICHUNG von der ursprünglichen Doku (Roman-Entscheidung 06.08.2026):
 * `LakeGeofenceEngine` kannte ursprünglich nur einen einzelnen Kreis
 * (Mittelpunkt + Radius), keine echte Polygon-Geofence — die volle
 * Doku-Vision (OSM-Uferlinie, JTS-Pufferung, Douglas-Peucker, Multipolygone)
 * würde einen kompletten Umbau der Engine brauchen. Stattdessen: Uferlinie
 * per Overpass-API laden, daraus eine KETTE von Kreisen entlang der
 * Mittellinie packen (siehe [packCircleChain]) — deckt auch lange/
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
 *
 * BEKANNTE EINSCHRÄNKUNG: nur einfache OSM-"way"-Geometrien (natural=water
 * auf einem einzelnen geschlossenen Weg) werden ausgewertet. Grössere/
 * komplexere Seen sind in OSM oft als "relation" (Multipolygon, z.B. mit
 * Inseln oder aus mehreren Uferabschnitten zusammengesetzt) erfasst — das
 * Zusammensetzen solcher Relationen ist laut Doku "nicht trivial" und war
 * genau der Teil, der mit der einfacheren Kreis-Lösung vermieden werden
 * sollte. Falls der Zielsee als Relation vorliegt, schlägt die Erkennung
 * fehl (klare Fehlermeldung) und die manuelle Eingabe (See-Mitte + See-Rand
 * erfassen) bleibt als Fallback bestehen.
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
    // Kette von Kreisen: maximale Anzahl und Mindestradius als Abbruch-
    // kriterium (kleinere Lücken lohnen keinen eigenen Kreis mehr).
    private const val MAX_CIRCLES = 8
    private const val MIN_CIRCLE_RADIUS_M = 15.0

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
        // einen einzelnen See, keine UTM-Zonen-Logik nötig).
        val proj = LocalProjection(fix)
        val polygons = candidates.map { it to proj.toLocal(it.points) }

        // Enthält der Fix-Punkt eines der Polygone? Bei mehreren Treffern
        // (z.B. kleiner Nebenteich) die grösste Fläche wählen (Doku Abschnitt 2).
        val origin = Vec2(0.0, 0.0) // fix liegt per Definition der Projektion bei (0,0)
        val containing = polygons.filter { (_, localPts) -> pointInPolygon(origin, localPts) }
        val chosen = if (containing.isNotEmpty()) {
            containing.maxByOrNull { (_, localPts) -> polygonArea(localPts) }
        } else {
            // Fix liegt in keinem Polygon (z.B. noch am Ufer stehend) - dem
            // nächstgelegenen innerhalb der Toleranz folgen.
            polygons.minByOrNull { (_, localPts) -> minDistToBoundary(origin, localPts) }
                ?.takeIf { (_, localPts) -> minDistToBoundary(origin, localPts) <= EDGE_TOLERANCE_M }
        }

        if (chosen == null) {
            return@withContext Result.Failure(
                "Nächster See ist mehr als ${EDGE_TOLERANCE_M.toInt()}m entfernt — " +
                    "bitte näher ans Wasser oder See-Mitte/-Rand manuell setzen."
            )
        }

        val (way, localPts) = chosen
        val localCircles = packCircleChain(localPts)
        if (localCircles.isEmpty()) {
            return@withContext Result.Failure(
                "See gefunden, aber zu klein/schmal für einen sinnvollen Sicherheits-" +
                    "kreis (< ${MIN_CIRCLE_RADIUS_M.toInt()}m) — bitte See-Mitte/-Rand manuell setzen."
            )
        }
        val circles = localCircles.map { (centerLocal, radiusM) -> LakeCircle(proj.toGeo(centerLocal), radiusM) }

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

    // ---- Lokale Geometrie (Äquirektangular-Projektion, Punkt-in-Polygon, ----
    // ---- Abstand zu Kanten, groesster einbeschriebener Kreis)            ----

    data class Vec2(val x: Double, val y: Double)

    /** Projiziert lat/lon auf lokale Meter-Koordinaten um einen Ursprungspunkt - für Distanzen bis zu wenigen km ausreichend genau, keine UTM-Zone nötig. */
    private class LocalProjection(private val origin: GeoPoint) {
        private val metersPerDegLat = 111_320.0
        private val metersPerDegLon = 111_320.0 * cos(Math.toRadians(origin.lat))

        fun toLocal(points: List<GeoPoint>): List<Vec2> = points.map {
            Vec2((it.lon - origin.lon) * metersPerDegLon, (it.lat - origin.lat) * metersPerDegLat)
        }

        fun toGeo(v: Vec2): GeoPoint = GeoPoint(
            origin.lat + v.y / metersPerDegLat,
            origin.lon + v.x / metersPerDegLon,
        )
    }

    private fun pointInPolygon(p: Vec2, poly: List<Vec2>): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val pi = poly[i]; val pj = poly[j]
            if ((pi.y > p.y) != (pj.y > p.y)) {
                val xIntersect = (pj.x - pi.x) * (p.y - pi.y) / (pj.y - pi.y) + pi.x
                if (p.x < xIntersect) inside = !inside
            }
            j = i
        }
        return inside
    }

    /** Fläche via Gauß'scher Trapezformel (Shoelace) - für den Grössenvergleich bei mehreren Treffern. */
    private fun polygonArea(poly: List<Vec2>): Double {
        var sum = 0.0
        var j = poly.size - 1
        for (i in poly.indices) {
            sum += (poly[j].x + poly[i].x) * (poly[j].y - poly[i].y)
            j = i
        }
        return kotlin.math.abs(sum / 2.0)
    }

    private fun distToSegment(p: Vec2, a: Vec2, b: Vec2): Double {
        val abx = b.x - a.x; val aby = b.y - a.y
        val len2 = abx * abx + aby * aby
        if (len2 == 0.0) return hypot(p.x - a.x, p.y - a.y)
        var t = ((p.x - a.x) * abx + (p.y - a.y) * aby) / len2
        t = t.coerceIn(0.0, 1.0)
        val projX = a.x + t * abx; val projY = a.y + t * aby
        return hypot(p.x - projX, p.y - projY)
    }

    private fun minDistToBoundary(p: Vec2, poly: List<Vec2>): Double {
        var minD = Double.MAX_VALUE
        var j = poly.size - 1
        for (i in poly.indices) {
            minD = minOf(minD, distToSegment(p, poly[j], poly[i]))
            j = i
        }
        return minD
    }

    private fun isCoveredByExisting(p: Vec2, existing: List<Pair<Vec2, Double>>): Boolean =
        existing.any { (center, radius) -> hypot(p.x - center.x, p.y - center.y) <= radius }

    /**
     * Grobe Annäherung an den Chebyshev-Mittelpunkt (grösster Kreis, der
     * komplett innerhalb des Polygons liegt) per Gitter-Suche mit iterativer
     * Verfeinerung - kein exakter Löser, aber für diesen Zweck (sinnvoller
     * Mittelpunkt+Radius statt exakter Optimalität) ausreichend und ohne
     * externe Geometrie-Bibliothek (JTS) umsetzbar, siehe Klassenkommentar.
     *
     * [excludeCovered] schliesst Kandidaten aus, die schon von einem
     * bereits gepackten Kreis abgedeckt sind - so findet [packCircleChain]
     * bei jedem Durchlauf eine noch UNabgedeckte Stelle statt immer
     * denselben (grössten) Kreis erneut.
     */
    private fun largestInscribedCircle(
        poly: List<Vec2>,
        excludeCovered: List<Pair<Vec2, Double>> = emptyList(),
    ): Pair<Vec2, Double> {
        var minX = poly.minOf { it.x }; var maxX = poly.maxOf { it.x }
        var minY = poly.minOf { it.y }; var maxY = poly.maxOf { it.y }
        var bestPoint = Vec2((minX + maxX) / 2, (minY + maxY) / 2)
        var bestDist = 0.0
        val gridSteps = 30

        repeat(5) {
            val stepX = (maxX - minX) / gridSteps
            val stepY = (maxY - minY) / gridSteps
            if (stepX <= 0.0 || stepY <= 0.0) return@repeat
            for (ix in 0..gridSteps) {
                for (iy in 0..gridSteps) {
                    val candidate = Vec2(minX + ix * stepX, minY + iy * stepY)
                    if (!pointInPolygon(candidate, poly)) continue
                    if (isCoveredByExisting(candidate, excludeCovered)) continue
                    val d = minDistToBoundary(candidate, poly)
                    if (d > bestDist) {
                        bestDist = d
                        bestPoint = candidate
                    }
                }
            }
            // Suchfenster ums bisher beste Ergebnis verkleinern (grob -> fein)
            val halfW = (maxX - minX) / 4
            val halfH = (maxY - minY) / 4
            minX = bestPoint.x - halfW; maxX = bestPoint.x + halfW
            minY = bestPoint.y - halfH; maxY = bestPoint.y + halfH
        }
        return bestPoint to bestDist
    }

    /**
     * Greedy-Kreispackung entlang der "Mittellinie" eines länglichen/
     * unregelmässigen Sees: wiederholt den grössten einbeschriebenen Kreis
     * in der noch UNabgedeckten Fläche suchen, bis entweder [MAX_CIRCLES]
     * erreicht ist oder der nächste Kreis kleiner als [MIN_CIRCLE_RADIUS_M]
     * würde (kein sinnvoller Platz mehr). Kein echter Medial-Achsen-/
     * Skeleton-Algorithmus, aber für die Zwecke einer Sicherheits-Geofence
     * (grobe, überlappende Abdeckung reicht) ausreichend und ohne externe
     * Geometrie-Bibliothek umsetzbar.
     */
    private fun packCircleChain(poly: List<Vec2>): List<Pair<Vec2, Double>> {
        val circles = mutableListOf<Pair<Vec2, Double>>()
        repeat(MAX_CIRCLES) {
            val (center, radius) = largestInscribedCircle(poly, circles)
            if (radius < MIN_CIRCLE_RADIUS_M) return circles
            circles.add(center to radius)
        }
        return circles
    }
}
