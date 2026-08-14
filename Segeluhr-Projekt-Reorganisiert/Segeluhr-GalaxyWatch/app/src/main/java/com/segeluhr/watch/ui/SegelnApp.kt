package com.segeluhr.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.segeluhr.watch.core.GeoPoint
import com.segeluhr.watch.ui.components.RoundingConfirmBanner
import com.segeluhr.watch.ui.components.SailScreenScaffold
import com.segeluhr.watch.ui.screens.CountdownScreen
import com.segeluhr.watch.ui.screens.HomeScreen
import com.segeluhr.watch.ui.screens.ManeuverScreen
import com.segeluhr.watch.ui.screens.MenuScreen
import com.segeluhr.watch.ui.screens.NavScreen
import com.segeluhr.watch.ui.screens.WaypointMapPickScreen
import com.segeluhr.watch.ui.screens.WindScreen
import com.segeluhr.watch.ui.theme.BgDark
import com.segeluhr.watch.viewmodel.SegeluhrWatchViewModel

private val TAB_TITLES = listOf("Nav", "Wind", "Heim", "CD", "Man", "Menu")

/**
 * Wurzel-Composable — analog zu buildSegelnScreen()/tabview auf der Ultra:
 * horizontal wischbare Tabs (Nav/Wind/Heim/CD/Man/Menu) statt lv_tabview,
 * PLUS zwei bildschirmfüllende Overlays: Bojen-Rundungs-Rückfrage (siehe
 * ManeuverScreen.kt-Kommentar) und der neue Wegpunkt-Kartenpicker. KEIN
 * eigener "Alltags"-Screen (Uhr/Timer/Akku/Setup) wie auf der Ultra — die
 * Galaxy Watch hat dafür bereits ihr eigenes Ziffernblatt/System-Apps,
 * siehe docs/Erweiterung_GalaxyWatch_App.md.
 */
@Composable
fun SegelnApp(viewModel: SegeluhrWatchViewModel) {
    val state by viewModel.uiState.collectAsState()
    var waypointPick by remember { mutableStateOf<Pair<Int, String>?>(null) }

    Box(Modifier.fillMaxSize().background(BgDark)) {
        val pick = waypointPick
        if (pick != null) {
            val (id, label) = pick
            val startCenter = state.gps?.let { GeoPoint(it.lat, it.lon) }
            WaypointMapPickScreen(
                label = label,
                startCenter = startCenter,
                onFinish = { point ->
                    viewModel.setWaypointAtCoords(id, point.lat, point.lon)
                    waypointPick = null
                },
                onCancel = { waypointPick = null },
            )
        } else {
            val pagerState = rememberPagerState(pageCount = { TAB_TITLES.size })
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                SailScreenScaffold(
                    connectionState = state.connectionState,
                    phoneBatteryPct = state.phoneBatteryPct,
                    ownBatteryPct = state.ownBatteryPct,
                    commandOverlay = state.commandOverlay,
                ) {
                    when (page) {
                        0 -> NavScreen(state)
                        1 -> WindScreen(state)
                        2 -> HomeScreen(state)
                        3 -> CountdownScreen(state)
                        4 -> ManeuverScreen(state)
                        5 -> MenuScreen(state, viewModel, onOpenMapPicker = { id, label -> waypointPick = id to label })
                    }
                }
            }
        }

        // Bildschirmfüllendes Overlay, unabhängig vom aktiven Tab (siehe
        // ManeuverScreen.kt-Kommentar).
        if (state.race?.roundingConfirmPending == true) {
            RoundingConfirmBanner(
                onConfirm = viewModel::confirmBuoyRounding,
                onReject = viewModel::rejectBuoyRounding,
            )
        }
    }
}
