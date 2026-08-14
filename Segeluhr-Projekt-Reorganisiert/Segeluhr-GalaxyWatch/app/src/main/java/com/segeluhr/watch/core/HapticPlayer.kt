package com.segeluhr.watch.core

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.segeluhr.watch.ble.BleProtocol

/**
 * Spielt die per CHAR_HAPTIC_UUID vom Handy empfangenen Muster-Codes ab —
 * 1:1 dieselben Muster wie VibrationPatterns.kt am Handy (Abschnitt 7 der
 * Spezifikation), nur als Empfänger statt als Erzeuger: das Handy vibriert
 * im Modus "Mit Uhr" selbst nicht mehr, siehe dortige Klassendoku.
 */
class HapticPlayer(context: Context) {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private fun pulses(n: Int, dur: Long = 80L, gap: Long = 80L): LongArray {
        val arr = mutableListOf<Long>()
        arr.add(0L)
        for (i in 0 until n) {
            if (i > 0) arr.add(gap)
            arr.add(dur)
        }
        return arr.toLongArray()
    }

    private fun fire(pattern: LongArray) {
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    fun play(code: Int) {
        when (code) {
            BleProtocol.HAPTIC_STEP1 -> fire(pulses(1))
            BleProtocol.HAPTIC_DONE2 -> fire(pulses(2))
            BleProtocol.HAPTIC_HEADER3 -> fire(pulses(3))
            BleProtocol.HAPTIC_ERROR4 -> fire(pulses(4))
            BleProtocol.HAPTIC_LAKE_WARN5 -> fire(pulses(5))
            BleProtocol.HAPTIC_ROUNDING6 -> fire(pulses(6))
            BleProtocol.HAPTIC_MANEUVER_CMD -> fire(longArrayOf(0L, 700L))
            BleProtocol.HAPTIC_START_SIGNAL -> fire(longArrayOf(0L, 900L))
            BleProtocol.HAPTIC_ROUNDING_CONFIRM_NEEDED -> fire(pulses(3, dur = 250L, gap = 150L))
        }
    }
}
