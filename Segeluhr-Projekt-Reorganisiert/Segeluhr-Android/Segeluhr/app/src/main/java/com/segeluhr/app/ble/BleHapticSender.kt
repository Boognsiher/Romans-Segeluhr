package com.segeluhr.app.ble

import com.segeluhr.app.core.HapticFeedback

/**
 * Wird im Modus "Mit Uhr" verwendet: statt am Handy zu vibrieren, wird der
 * Muster-Code per BLE-Notify an die Uhr geschickt (Characteristic
 * CHAR_HAPTIC_UUID). Die Uhr-Firmware triggert dann ihren eigenen
 * Vibrationsmotor entsprechend Abschnitt 7 der Spezifikation.
 */
class BleHapticSender(private val bleManager: BleGattServerManager) : HapticFeedback {
    override fun step1() = bleManager.sendHapticCommand(BleProtocol.HAPTIC_STEP1)
    override fun done2() = bleManager.sendHapticCommand(BleProtocol.HAPTIC_DONE2)
    override fun header3() = bleManager.sendHapticCommand(BleProtocol.HAPTIC_HEADER3)
    override fun error4() = bleManager.sendHapticCommand(BleProtocol.HAPTIC_ERROR4)
    override fun lakeWarn5() = bleManager.sendHapticCommand(BleProtocol.HAPTIC_LAKE_WARN5)
    override fun rounding6() = bleManager.sendHapticCommand(BleProtocol.HAPTIC_ROUNDING6)
    override fun maneuverCmd() = bleManager.sendHapticCommand(BleProtocol.HAPTIC_MANEUVER_CMD)
    override fun startSignal() = bleManager.sendHapticCommand(BleProtocol.HAPTIC_START_SIGNAL)
    override fun roundingConfirmNeeded() = bleManager.sendHapticCommand(BleProtocol.HAPTIC_ROUNDING_CONFIRM_NEEDED)
}
