package com.segeluhr.watch.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.segeluhr.watch.ui.components.RoundingConfirmBanner
import com.segeluhr.watch.ui.components.SailScreenScaffold
import com.segeluhr.watch.ui.screens.CountdownScreen
import com.segeluhr.watch.ui.screens.HomeScreen
import com.segeluhr.watch.ui.screens.ManeuverScreen
import com.segeluhr.watch.ui.screens.MenuScreen
import com.segeluhr.watch.ui.screens.NavScreen
import com.segeluhr.watch.ui.screens.WindScreen
import com.segeluhr.watch.ui.theme.BgDark
import com.segeluhr.watch.viewmodel.SegeluhrWatchViewModel

private val TAB_TITLES = listOf("Nav", "Wind", "Heim", "CD", "Man", "Menu")

/**
 * Wurzel-Composable — analog zu buildSegelnScreen()/tabview auf der Ultra:
 * horizontal wischbare Tabs (Nav/Wind/Heim/CD/Man/Menu) statt lv_tabview,
 * PLUS ein bildschirmfüllendes Overlay (Bojen-Rundungs-Rückfrage, siehe
 * ManeuverScreen.kt-Kommentar). KEIN eigener "Alltags"-Screen (Uhr/Timer/
 * Akku/Setup) wie auf der Ultra — die Galaxy Watch hat dafür bereits ihr
 * eigenes Ziffernblatt/System-Apps, siehe docs/Erweiterung_GalaxyWatch_App.md.
 * Der ursprünglich geplante Wegpunkt-Kartenpicker auf der Uhr (osmdroid) ist
 * 14.08. Abend wieder gestrichen — Roman-Feedback: Display zu klein dafür.
 * Wegpunkte lassen sich hier nur noch "an der aktuellen Position" setzen,
 * wie auf der Ultra.
 *
 * **02.09.2026:** Tab-Wechsel per Wisch-Geste bleibt bestehen, ist im
 * Wasserdicht-Modus (Touch deaktiviert) aber nicht nutzbar — die beiden
 * LaunchedEffects unten koppeln den Pager zusätzlich an die physische
 * Hardware-Taste (langer Druck = nächster Tab, siehe MainActivity.kt/
 * SegeluhrWatchViewModel.kt).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SegelnApp(viewModel: SegeluhrWatchViewModel) {
    val state by viewModel.uiState.collectAsState()

    Box(Modifier.fillMaxSize().background(BgDark)) {
        val pagerState = rememberPagerState(pageCount = { TAB_TITLES.size })

        // Hardware-Tasten-Bedienung + Auto-Fokus (siehe SegeluhrWatchViewModel-
        // Klassendoku): aktiver Tab ans ViewModel melden (für die
        // Kontextaktion der kurzen Taste), umgekehrt auf navigateToTab
        // reagieren (langer Tastendruck = nächster Tab, ODER automatischer
        // Sprung auf den Nav-Tab beim Wettfahrt-Start).
        LaunchedEffect(pagerState.currentPage) { viewModel.onTabChanged(pagerState.currentPage) }
        LaunchedEffect(Unit) {
            viewModel.navigateToTab.collect { target -> pagerState.animateScrollToPage(target) }
        }

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
                    3 -> CountdownScreen(state, viewModel)
                    4 -> ManeuverScreen(state)
                    5 -> MenuScreen(state.waypoints, state.home?.active == true, viewModel)
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
