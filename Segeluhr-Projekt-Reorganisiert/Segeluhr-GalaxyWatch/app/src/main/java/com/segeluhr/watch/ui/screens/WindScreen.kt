package com.segeluhr.watch.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.segeluhr.watch.data.WatchUiState
import com.segeluhr.watch.ui.theme.TextDim
import com.segeluhr.watch.ui.theme.TextLight

/** Wind-Tab, analog zu windScreenUpdate() auf der Ultra: Windrichtung + Trend. */
@Composable
fun WindScreen(state: WatchUiState) {
    val wind = state.wind
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (wind == null || !wind.calibrated) {
            Text("nicht\nkalibriert", color = TextDim, fontSize = 16.sp, textAlign = TextAlign.Center)
        } else {
            Text("%.0f°".format(wind.dirDeg ?: 0.0), color = TextLight, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                "Trend %+.0f°".format(wind.trendDeg), color = TextDim, fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
