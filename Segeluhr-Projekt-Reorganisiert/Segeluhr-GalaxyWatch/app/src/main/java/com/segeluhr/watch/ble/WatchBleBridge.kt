package com.segeluhr.watch.ble

import android.content.Context

/**
 * Sowohl der [WatchBleForegroundService] als auch das ViewModel brauchen
 * Zugriff auf DENSELBEN [WatchBleClient] — analog zu BleBridge.kt am Handy.
 */
object WatchBleBridge {
    @Volatile private var instance: WatchBleClient? = null

    fun getInstance(context: Context): WatchBleClient =
        instance ?: synchronized(this) {
            instance ?: WatchBleClient(context.applicationContext).also { instance = it }
        }
}
