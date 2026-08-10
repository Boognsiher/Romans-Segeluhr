package com.segeluhr.app.core

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Vibrationsmuster, Abschnitt 7 der Spezifikation. Anzahl kurzer Pulse statt
 * Text, damit Aktionen ohne Blick aufs Display erkennbar sind.
 */
class VibrationPatterns(context: Context) : HapticFeedback {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private fun pulses(n: Int, dur: Long = 80L, gap: Long = 80L): LongArray {
        val arr = mutableListOf<Long>()
        arr.add(0L) // Android-Pattern beginnt mit "warte 0ms", dann an/aus
        for (i in 0 until n) {
            if (i > 0) arr.add(gap)
            arr.add(dur)
        }
        return arr.toLongArray()
    }

    private fun fire(pattern: LongArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    /** 1x kurz — Zwischenschritt (z.B. Bug-Referenz erfasst) */
    override fun step1() = fire(pulses(1))

    /** 2x kurz — Vorgang erfolgreich abgeschlossen */
    override fun done2() = fire(pulses(2))

    /** 3x kurz — ungünstiger Wind-Shift (Header zum Ziel) */
    override fun header3() = fire(pulses(3))

    /** 4x kurz — Fehler / Timeout / Plausibilitätsproblem */
    override fun error4() = fire(pulses(4))

    /** 5x kurz — See-Rand-Warnung */
    override fun lakeWarn5() = fire(pulses(5))

    /** 6x kurz — Boje gerundet */
    override fun rounding6() = fire(pulses(6))

    /** Manöver-Kommando ("jetzt wenden/halsen") — 1x kräftig/lang, eigener Effekt */
    override fun maneuverCmd() = fire(longArrayOf(0L, 700L))

    /** Start-Signal bei 0:00 — kräftig */
    override fun startSignal() = fire(longArrayOf(0L, 900L))

    /** Bojen-Rundungs-Rückfrage — 3x lang/deutlich, unterscheidbar von rounding6()'s kurzen Pulsen */
    override fun roundingConfirmNeeded() = fire(pulses(3, dur = 250L, gap = 150L))
}
