package com.segeluhr.app.ble

import android.content.Context

/**
 * Sowohl der SegeluhrForegroundService (startet/stoppt den GATT-Server,
 * leitet GPS-Fixe weiter) als auch das ViewModel (schickt Haptik-Kommandos)
 * brauchen Zugriff auf DENSELBEN [BleGattServerManager] — sonst würden
 * Haptik-Notifies über eine andere Server-Instanz laufen als die, mit der
 * die Uhr tatsächlich verbunden ist.
 */
object BleBridge {
    @Volatile private var instance: BleGattServerManager? = null

    fun getInstance(context: Context): BleGattServerManager =
        instance ?: synchronized(this) {
            instance ?: BleGattServerManager(context.applicationContext).also { instance = it }
        }
}
