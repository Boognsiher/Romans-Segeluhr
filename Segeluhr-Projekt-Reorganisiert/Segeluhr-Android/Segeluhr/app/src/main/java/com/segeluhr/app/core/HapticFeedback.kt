package com.segeluhr.app.core

/**
 * Abstraktion über die Vibrationsmuster aus Abschnitt 7 der Spezifikation.
 * Zwei Implementierungen:
 *  - [VibrationPatterns]: vibriert direkt am Handy (Standalone-Modus)
 *  - com.segeluhr.app.ble.BleHapticSender: sendet den Befehl per BLE an die
 *    Uhr, die dort selbst vibriert (Modus "Mit Uhr")
 *
 * So bleibt die restliche Logik (WindEngine, TrainingEngine, ...) komplett
 * unabhängig davon, WO am Ende tatsächlich vibriert wird.
 */
interface HapticFeedback {
    /** 1x kurz — Zwischenschritt (z.B. Bug-Referenz erfasst) */
    fun step1()

    /** 2x kurz — Vorgang erfolgreich abgeschlossen */
    fun done2()

    /** 3x kurz — ungünstiger Wind-Shift (Header zum Ziel) */
    fun header3()

    /** 4x kurz — Fehler / Timeout / Plausibilitätsproblem */
    fun error4()

    /** 5x kurz — See-Rand-Warnung */
    fun lakeWarn5()

    /** 6x kurz — Boje gerundet */
    fun rounding6()

    /** Manöver-Kommando ("jetzt wenden/halsen") — kräftig/lang, eigener Effekt */
    fun maneuverCmd()

    /** Start-Signal bei 0:00 — kräftig */
    fun startSignal()

    /**
     * Bojen-Rundungs-Rückfrage steht an ("Boje noch nicht erreicht —
     * trotzdem als gerundet werten?", siehe
     * docs/Erweiterung_Vereinheitlichte_Bojenerkennung.md und
     * MarkRoundingDetector.Result.NeedsConfirmation). Reine
     * Aufmerksamkeits-Benachrichtigung — die eigentliche Antwort kommt per
     * Geste (Uhr) oder Banner-Button (Handy), nicht per Haptik.
     */
    fun roundingConfirmNeeded()
}
