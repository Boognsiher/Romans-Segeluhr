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
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SegelnApp(viewModel: SegeluhrWatchViewModel) {
    val state by viewModel.uiState.collectAsState()
    val targetPage by viewModel.currentPage.collectAsState()

    Box(Modifier.fillMaxSize().background(BgDark)) {
        val pagerState = rememberPagerState(pageCount = { TAB_TITLES.size })

        // Zwei-Wege-Sync mit dem physischen Taster (siehe MainActivity/
        // SegeluhrWatchViewModel.onPhysicalButtonNextPage, Roman-Wunsch
        // "Taster statt Touch bei Nässe"): ein Tastendruck setzt
        // viewModel.currentPage, das hier den Pager dorthin scrollt. Ein
        // manueller Wisch (Touch funktioniert ja z.B. an Land/im Stillstand
        // weiterhin) hält umgekehrt currentPage aktuell, damit der nächste
        // Tastendruck vom tatsächlich sichtbaren Tab aus weiterzählt statt zu
        // einem alten Stand zurückzuspringen. Je ein Guard verhindert, dass
        // sich beide Effekte gegenseitig hochschaukeln.
        LaunchedEffect(targetPage) {
            if (pagerState.currentPage != targetPage) pagerState.animateScrollToPage(targetPage)
        }
        LaunchedEffect(pagerState.currentPage) {
            if (viewModel.currentPage.value != pagerState.currentPage) viewModel.setCurrentPage(pagerState.currentPage)
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
