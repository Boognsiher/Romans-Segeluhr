package com.segeluhr.watch.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.segeluhr.watch.ui.theme.BgDark
import com.segeluhr.watch.ui.theme.TextLight

/**
 * Kurze Bestätigung nach einem Button-Tap ("Wegpunkt gesendet" etc.) —
 * analog zu showCommandOverlay()/commandOverlayTick() auf der Ultra
 * (dort eingeführt, weil ein stiller BLE-Fehlschlag identisch aussah wie
 * "Button tut nichts", siehe Kommentar bei sendControlCommand() dort).
 */
@Composable
fun CommandOverlay(text: String?) {
    AnimatedVisibility(visible = text != null, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxWidth().padding(top = 44.dp), contentAlignment = Alignment.TopCenter) {
            Box(
                Modifier
                    .background(BgDark.copy(alpha = 0.92f), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(text ?: "", color = TextLight, fontSize = 11.sp)
            }
        }
    }
}
