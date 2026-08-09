package com.segeluhr.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.segeluhr.app.ble.LandUhrClient
import com.segeluhr.app.ble.LandPositionState
import kotlinx.coroutines.flow.StateFlow

/**
 * Dünner Wrapper um [LandUhrClient] für Compose - siehe
 * docs/Erweiterung_Landuhr_Kartenansicht.md. Bewusst ein eigenes ViewModel
 * statt Teil von [SegeluhrViewModel]: komplett andere Rolle (BLE-Client
 * statt -Server), soll nicht mitlaufen/Akku kosten, wenn man gar nicht im
 * Land-Modus ist. [connect]/[disconnect] werden explizit von
 * [com.segeluhr.app.ui.screens.LandUhrScreen] aus dessen Lifecycle heraus
 * aufgerufen (nicht schon in init{}) - der Scan soll nur laufen, während
 * der Karten-Screen auch tatsächlich sichtbar ist.
 */
class LandUhrViewModel(application: Application) : AndroidViewModel(application) {

    private val client = LandUhrClient(application)

    val state: StateFlow<LandPositionState> = client.state

    fun connect() = client.start()

    fun disconnect() = client.stop()

    override fun onCleared() {
        client.stop()
        super.onCleared()
    }
}
