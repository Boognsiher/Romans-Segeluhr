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

@Composable
fun WaypointRow(
    label: String,
    coordText: String,
    onSet: () -> Unit,
    onClear: () -> Unit,
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
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = onClear) { Text("×", color = TextDim, fontSize = 18.sp) }
        }
    }
}

