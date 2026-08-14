package com.segeluhr.watch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Text
import com.segeluhr.watch.ui.theme.Amber
import com.segeluhr.watch.ui.theme.BgDark
import com.segeluhr.watch.ui.theme.Green
import com.segeluhr.watch.ui.theme.Red
import com.segeluhr.watch.ui.theme.TextLight

/**
 * Rückfrage "Boje hier?" bei vereinheitlichter Bojen-Rundungserkennung
 * (siehe docs/Erweiterung_Vereinheitlichte_Bojenerkennung.md) — auf der
 * Ultra per Geste (Tilt=Ja/Shake=Nein) oder Taster-Fallback beantwortet;
 * hier bewusst NUR per Touch-Buttons (kein Klio-Sensor/Gestenerkennung auf
 * der Galaxy Watch, siehe docs/Erweiterung_GalaxyWatch_App.md — die
 * Gestenerkennung war auf der Ultra selbst nie zuverlässig zum Laufen
 * gebracht, siehe PROJEKT_STATUS.md 13.08.2026).
 */
@Composable
fun RoundingConfirmBanner(onConfirm: () -> Unit, onReject: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(BgDark.copy(alpha = 0.95f)).padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Boje hier?", color = Amber, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            "Noch nicht erreicht — trotzdem als gerundet werten?",
            color = TextLight, fontSize = 10.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onReject, colors = ButtonDefaults.secondaryButtonColors()) {
                Text("Nein", color = Red, fontSize = 12.sp)
            }
            Button(onClick = onConfirm, colors = ButtonDefaults.primaryButtonColors()) {
                Text("Ja", color = Green, fontSize = 12.sp)
            }
        }
    }
}
