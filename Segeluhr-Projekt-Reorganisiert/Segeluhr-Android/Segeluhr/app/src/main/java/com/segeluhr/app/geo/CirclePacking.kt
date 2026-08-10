package com.segeluhr.app.geo

import com.segeluhr.app.core.GeoPoint
import com.segeluhr.app.core.LakeCircle
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Greedy-Kreis-Ketten-Packung — 10.08.2026 aus `LakeAutoDetector` heraus in
 * ein eigenes, quellen-unabhängiges Modul extrahiert (siehe
 * docs/Erweiterung_Seegrenze_Zeichnen.md), damit sowohl die automatische
 * OSM-Erkennung als auch die neue manuelle Karten-Zeichnen-Funktion
 * dieselbe Logik nutzen, statt sie zu duplizieren. Nimmt ein beliebiges
 * geschlossenes Polygon (Lat/Lon) entgegen — ob die Punkte von Overpass
 * oder direkt vom Finger auf der Karte stammen, ist dieser Klasse egal.
 *
 * Algorithmus unverändert aus `LakeAutoDetector` übernommen (siehe dortige
 * ursprüngliche Klassendoku für die Begründung: kein echter Medial-Achsen-/
 * Skeleton-Algorithmus, sondern eine Gitter-Suche nach dem jeweils grössten
 * noch unabgedeckten einbeschriebenen Kreis — für eine Sicherheits-Geofence
 * ausreichend, ohne externe Geometrie-Bibliothek).
 */
object CirclePacking {
    const val MAX_CIRCLES = 8
    const val MIN_CIRCLE_RADIUS_M = 15.0

    data class Vec2(val x: Double, val y: Double)

    /** Projiziert lat/lon auf lokale Meter-Koordinaten um einen Ursprungspunkt - für Distanzen bis zu wenigen km ausreichend genau, keine UTM-Zone nötig. */
    class LocalProjection(private val origin: GeoPoint) {
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

    fun pointInPolygon(p: Vec2, poly: List<Vec2>): Boolean {
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

    private fun distToSegment(p: Vec2, a: Vec2, b: Vec2): Double {
        val abx = b.x - a.x; val aby = b.y - a.y
        val len2 = abx * abx + aby * aby
        if (len2 == 0.0) return hypot(p.x - a.x, p.y - a.y)
        var t = ((p.x - a.x) * abx + (p.y - a.y) * aby) / len2
        t = t.coerceIn(0.0, 1.0)
        val projX = a.x + t * abx; val projY = a.y + t * aby
        return hypot(p.x - projX, p.y - projY)
    }

    fun minDistToBoundary(p: Vec2, poly: List<Vec2>): Double {
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
     * Verfeinerung. [excludeCovered] schliesst Kandidaten aus, die schon von
     * einem bereits gepackten Kreis abgedeckt sind.
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
            val halfW = (maxX - minX) / 4
            val halfH = (maxY - minY) / 4
            minX = bestPoint.x - halfW; maxX = bestPoint.x + halfW
            minY = bestPoint.y - halfH; maxY = bestPoint.y + halfH
        }
        return bestPoint to bestDist
    }

    /**
     * Packt eine Kette von Sicherheitskreisen in [polygon] (Lat/Lon, wird
     * intern wie ein geschlossener Ring behandelt — letzter Punkt muss NICHT
     * gleich dem ersten sein). Projiziert lokal um den Schwerpunkt des
     * Polygons (unabhängig davon, woher die Punkte stammen — OSM-Way oder
     * per Finger auf der Karte gezeichnet). Leere Liste, falls das Polygon
     * zu klein für einen einzigen Kreis über [MIN_CIRCLE_RADIUS_M] ist, oder
     * weniger als 3 Punkte übergeben wurden.
     */
    fun packChain(polygon: List<GeoPoint>): List<LakeCircle> {
        if (polygon.size < 3) return emptyList()
        val originLat = polygon.sumOf { it.lat } / polygon.size
        val originLon = polygon.sumOf { it.lon } / polygon.size
        val proj = LocalProjection(GeoPoint(originLat, originLon))
        val local = proj.toLocal(polygon)

        val circles = mutableListOf<Pair<Vec2, Double>>()
        for (i in 0 until MAX_CIRCLES) {
            val (center, radius) = largestInscribedCircle(local, circles)
            if (radius < MIN_CIRCLE_RADIUS_M) break
            circles.add(center to radius)
        }
        return circles.map { (center, radius) -> LakeCircle(proj.toGeo(center), radius) }
    }
}
