package com.segeluhr.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Sailing
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.segeluhr.app.data.model.AppRole
import com.segeluhr.app.ui.theme.*

/**
 * Erweiterung "Start-Rollenwahl" (10.08.2026, siehe
 * docs/Erweiterung_App_Stopp_Rollenwahl.md) — Vollbild-Wahlbildschirm bei
 * JEDEM App-Start (Roman-Entscheidung: nicht nur einmalig), bevor irgendein
 * anderer Screen aufgebaut wird. Ersetzt das bisherige stille Standard-
 * Verhalten (immer erst Boots-Rolle, Umschalten nur versteckt im Setup-Tab)
 * durch eine explizite, unübersehbare Entscheidung — sinnvoll, wenn
 * dasselbe Handy mal auf dem Boot, mal an Land in der Hand ist.
 *
 * [lastRole] zeigt nur zur Orientierung "zuletzt genutzt" an ("die App
 * merkt sich trotzdem" - persistiert bleibt die Rolle weiterhin über
 * SettingsRepository, nur der Wahlbildschirm selbst wird nicht übersprungen).
 */
@Composable
fun RolePickerScreen(
    lastRole: AppRole,
    onPick: (AppRole) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("⛵ Segeluhr", color = TextLight, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Wie wird die App gerade genutzt?",
            color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 28.dp),
        )

        RoleCard(
            icon = Icons.Filled.Sailing,
            title = "Auf dem Boot",
            description = "GPS, Windkalibrierung, Training/Wettfahrt-Navigation",
            highlighted = lastRole == AppRole.SAILOR,
            onClick = { onPick(AppRole.SAILOR) },
        )
        Spacer(Modifier.height(16.dp))
        RoleCard(
            icon = Icons.Filled.Home,
            title = "An Land",
            description = "Nur Boot-Position auf der Karte verfolgen (Land-Uhr per Bluetooth)",
            highlighted = lastRole == AppRole.SHORE,
            onClick = { onPick(AppRole.SHORE) },
        )

        Text(
            "Zuletzt genutzt: ${if (lastRole == AppRole.SAILOR) "Auf dem Boot" else "An Land"} — " +
                "später jederzeit im Setup-Tab umschaltbar.",
            color = TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 24.dp),
        )
    }
}

@Composable
private fun RoleCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .background(if (highlighted) Panel2Dark else PanelDark)
            .clickable(onClick = onClick),
    ) {
        // Kleines Hintergrund-Icon (Roman-Wunsch), rechtsbündig, dezent -
        // reine Wiedererkennung/Auflockerung, kein dominantes Hero-Element.
        Icon(
            icon,
            contentDescription = null,
            tint = Teal.copy(alpha = 0.14f),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
                .size(84.dp),
        )
        Column(
            Modifier.align(Alignment.CenterStart).padding(start = 18.dp, end = 90.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = Teal, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, color = TextLight, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                description, color = TextDim, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (highlighted) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .background(TealDim)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text("zuletzt", color = Teal, fontSize = 10.sp)
            }
        }
    }
}
