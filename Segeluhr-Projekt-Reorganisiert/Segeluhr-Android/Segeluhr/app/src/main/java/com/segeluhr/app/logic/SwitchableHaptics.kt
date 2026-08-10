package com.segeluhr.app.logic

import com.segeluhr.app.core.HapticFeedback

/**
 * Wird EINMAL erzeugt und an alle Engines (WindEngine, TrainingEngine, ...)
 * weitergegeben. [preferWatch] wird beim Umschalten zwischen "Ohne Uhr" und
 * "Mit Uhr" im Setup-Tab gesetzt — die Engines selbst müssen davon nichts
 * wissen und behalten ihren internen Zustand (Kalibrierfortschritt,
 * Ringpuffer etc.) beim Umschalten bei.
 *
 * WICHTIG: Auch wenn [preferWatch] true ist ("Mit Uhr" gewählt), wird bei
 * JEDEM Vibrationsereignis per [isWatchConnected] neu geprüft, ob gerade
 * tatsächlich eine Uhr per BLE verbunden ist. Ist das nicht der Fall (Uhr
 * ausser Reichweite, ausgeschaltet, noch nicht gekoppelt), vibriert
 * automatisch das Handy — es soll nie ein Signal "verpuffen", nur weil die
 * Uhr gerade nicht erreichbar ist.
 */
class SwitchableHaptics(
    private val phone: HapticFeedback,
    private val watch: HapticFeedback,
    private val isWatchConnected: () -> Boolean,
) : HapticFeedback {

    @Volatile
    var preferWatch: Boolean = false

    private fun target(): HapticFeedback =
        if (preferWatch && isWatchConnected()) watch else phone

    override fun step1() = target().step1()
    override fun done2() = target().done2()
    override fun header3() = target().header3()
    override fun error4() = target().error4()
    override fun lakeWarn5() = target().lakeWarn5()
    override fun rounding6() = target().rounding6()
    override fun maneuverCmd() = target().maneuverCmd()
    override fun startSignal() = target().startSignal()
    override fun roundingConfirmNeeded() = target().roundingConfirmNeeded()
}
