package com.segeluhr.app.core

/**
 * Misst die tatsächliche Annäherung an ein Ziel über ein gleitendes
 * Zeitfenster, statt die "Velocity Made Good" (VMC) aus dem Momentan-Kurs
 * zu berechnen (Erweiterung für [logic.HomeEngine], siehe
 * docs/Erweiterung_Heimweg.md, offener Punkt vom 10.08.2026: die bisherige
 * Berechnung `sogKn * cos(COG − Peilung)` reagierte zu direkt auf
 * Kurs-Zacken während einer Wende — VMC brach dabei kurzzeitig bis auf 0
 * ein, obwohl die reale Annäherung über die ganze Kreuz-Etappe hinweg
 * stetig weiterlief).
 *
 * Rechnet stattdessen schlicht: "wie viele Meter bin ich in den letzten
 * [windowMs] wirklich näher gekommen?" — unempfindlich gegen kurzfristige
 * Kursänderungen, weil nur die reale Distanzänderung zählt, nicht der
 * Momentan-Kurs. Wie [CourseTracker]: jede Nutzung braucht eine EIGENE,
 * unabhängige Instanz.
 */
class HomeProgressTracker(
    private val windowMs: Long = 60_000L,
    private val minWindowMs: Long = 20_000L,
) {
    private data class Sample(val timestampMs: Long, val distanceM: Double)

    private val buffer = ArrayDeque<Sample>()

    fun reset() {
        buffer.clear()
    }

    /**
     * Bei jedem HomeEngine.tick() mit dem aktuellen Fix-Zeitstempel + Distanz
     * zum Ziel aufrufen. HomeEngine.tick() läuft auch bei eingefrorenem
     * (veraltetem) Fix weiter, wenn das GPS-Signal ausfällt — ohne diese
     * Prüfung würde der Puffer sich sonst mit identischen Zeitstempeln
     * unbegrenzt füllen (kein neuerer Fix -> keine Eviction greift mehr).
     */
    fun sample(timestampMs: Long, distanceM: Double) {
        if (buffer.isNotEmpty() && timestampMs <= buffer.last().timestampMs) return
        buffer.addLast(Sample(timestampMs, distanceM))
        while (buffer.isNotEmpty() && timestampMs - buffer.first().timestampMs > windowMs) {
            buffer.removeFirst()
        }
    }

    /**
     * Durchschnittliche Annäherungsgeschwindigkeit in Knoten über das
     * aktuelle Fenster, oder null, wenn noch nicht genug Historie vorhanden
     * ist (Zeitspanne im Puffer < [minWindowMs]) — dann zeigt die App
     * bewusst "ETA: unbekannt" statt einen verfrühten, noch stark
     * verrauschten Wert (z.B. direkt nach dem Aktivieren des Heimweg-Modus).
     */
    fun averageVmcKn(): Double? {
        val oldest = buffer.firstOrNull() ?: return null
        val newest = buffer.lastOrNull() ?: return null
        val spanMs = newest.timestampMs - oldest.timestampMs
        if (spanMs < minWindowMs) return null
        val deltaDistanceM = oldest.distanceM - newest.distanceM // positiv = näher gekommen
        val deltaSeconds = spanMs / 1000.0
        val metersPerSecond = deltaDistanceM / deltaSeconds
        return metersPerSecond * 3600.0 / 1852.0
    }
}
