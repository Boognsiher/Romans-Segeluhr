package com.segeluhr.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.segeluhr.app.logic.StatusLevel
import com.segeluhr.app.ui.theme.*

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(PanelDark, RoundedCornerShape(14.dp))
            .border(1.dp, LineDark, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Text(
            title.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextDim,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        content()
    }
}

@Composable
fun StatRow(label: String, value: String, valueColor: Color = TextLight, big: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(label, fontSize = 13.sp, color = TextDim)
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            fontSize = if (big) 26.sp else 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
        )
    }
}

@Composable
fun StatTile(label: String, value: String, unit: String? = null, modifier: Modifier = Modifier, valueColor: Color = TextLight) {
    Column(
        modifier
            .background(Panel2Dark, RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label.uppercase(), fontSize = 10.sp, color = TextDim, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontFamily = FontFamily.Monospace, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = valueColor)
            if (unit != null) {
                Spacer(Modifier.width(2.dp))
                Text(unit, fontSize = 11.sp, color = TextDim)
            }
        }
    }
}

@Composable
fun StatusBanner(text: String, level: StatusLevel) {
    val borderColor = when (level) {
        StatusLevel.AMBER -> Amber
        StatusLevel.RED -> Red
        StatusLevel.GREEN -> Green
        StatusLevel.NORMAL -> Teal
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(Panel2Dark, RoundedCornerShape(12.dp))
    ) {
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(borderColor)
        )
        Text(
            text,
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 14.dp),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextLight,
        )
    }
}

/**
 * Vereinheitlichte Bojen-Rundungserkennung (Erweiterung, siehe
 * docs/Erweiterung_Vereinheitlichte_Bojenerkennung.md): Rückfrage-Banner
 * für "Kurswechsel Amwind/Vorwind in der Nähe einer gesetzten Boje, aber
 * nicht direkt an ihrer Position erkannt". Bewusst als eigenständiger,
 * auffälligerer Banner statt einer Variante von [StatusBanner] — zwei
 * Buttons statt reinem Text, und die Zeitangabe macht den (auf der Uhr
 * per Geste identisch funktionierenden) Auto-Bestätigen-Timeout auch am
 * Handy sichtbar.
 */
@Composable
fun BuoyRoundingConfirmBanner(onConfirm: () -> Unit, onReject: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Panel2Dark, RoundedCornerShape(12.dp))
            .border(1.dp, Amber, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Text(
            "Boje noch nicht erreicht — trotzdem als gerundet werten?",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextLight,
        )
        Text(
            "Ohne Antwort gilt automatisch \"Ja\" (auch per Geste auf der Uhr möglich).",
            fontSize = 11.sp,
            color = TextDim,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onConfirm, modifier = Modifier.weight(1f)) {
                Text("Ja, Boje ist hier", fontSize = 13.sp)
            }
            OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) {
                Text("Nein, anderer Grund", fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun WaypointRow(
    label: String,
    coordText: String,
    onSet: () -> Unit,
    onClear: () -> Unit,
    // NEU (12.08.2026, siehe docs/Erweiterung_Boje_Kartenauswahl.md):
    // optionale Alternative zu onSet (immer aktuelle GPS-Position) — zeigt
    // bei Nicht-null einen zusätzlichen "Karte"-Button, der eine
    // Kartenauswahl (WaypointMapPickScreen) öffnet. Default null lässt alle
    // bestehenden Aufrufstellen (See-Kreise, Rand erfassen) unverändert.
    onSetFromMap: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(label, fontSize = 13.sp, color = TextLight)
            Text(coordText, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TextDim)
        }
        Row {
            OutlinedButton(onClick = onSet) { Text("Setzen", fontSize = 12.sp) }
            if (onSetFromMap != null) {
                Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = onSetFromMap) { Text("Karte", fontSize = 12.sp) }
            }
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = onClear) { Text("×", color = TextDim, fontSize = 18.sp) }
        }
    }
}

