package com.segeluhr.watch.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.segeluhr.watch.ble.ConnectionState

/** Gemeinsames Gerüst aller Segel-Tabs: Statuszeile oben, Inhalt zentriert, Command-Overlay oben drüber. */
@Composable
fun SailScreenScaffold(
    connectionState: ConnectionState,
    phoneBatteryPct: Int?,
    ownBatteryPct: Int?,
    commandOverlay: String?,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            StatusBar(connectionState, phoneBatteryPct, ownBatteryPct)
            Box(
                Modifier.fillMaxSize().padding(PaddingValues(horizontal = 22.dp, vertical = 4.dp)),
                contentAlignment = Alignment.Center,
            ) { content() }
        }
        CommandOverlay(commandOverlay)
    }
}
