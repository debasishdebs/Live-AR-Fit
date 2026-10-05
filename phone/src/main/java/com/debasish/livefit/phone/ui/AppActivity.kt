package com.debasish.livefit.phone.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.debasish.livefit.phone.SpikeActivity
import com.debasish.livefit.phone.LiveFitHubService
import com.debasish.livefit.phone.services
import com.debasish.livefit.phone.ui.linked.LinkedGlassesScreen
import com.debasish.livefit.phone.ui.linked.LinkedMusicScreen
import com.debasish.livefit.phone.ui.linked.LinkedWatchScreen
import com.debasish.livefit.phone.ui.music.MusicScreen
import com.debasish.livefit.phone.ui.settings.AboutScreen
import com.debasish.livefit.phone.ui.settings.UnitsScreen
import com.debasish.livefit.phone.ui.workout.WorkoutScreen
import com.debasish.livefit.phone.ui.home.HomeScreen
import com.debasish.livefit.phone.ui.home.PillNav
import com.debasish.livefit.phone.ui.home.Tab
import androidx.navigation.compose.currentBackStackEntryAsState
import com.debasish.livefit.phone.ui.list.ListScreen
import com.debasish.livefit.phone.ui.list.ListSources
import com.debasish.livefit.phone.ui.settings.SettingsScreen
import com.debasish.livefit.phone.ui.theme.LiveFitTheme
import kotlinx.coroutines.launch

class AppActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        LiveFitHubService.start(this)
        // App opened: connect now if no session is in flight (authorization runs through AuthActivity).
        if (savedInstanceState == null) (services.glasses as? com.debasish.livefit.services.glasses.CxrGlassesLink)?.connect()
        setContent {
            LiveFitTheme {
                val nav = rememberNavController()
                val snackbar = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                val toast: (String) -> Unit = { msg -> scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(msg) } }
                val services = this@AppActivity.services

                val entry by nav.currentBackStackEntryAsState()
                val route = entry?.destination?.route.orEmpty()
                val currentSource = entry?.arguments?.getString("source")
                // Footer everywhere except the live workout screen (it has its own bottom controls).
                val showNav = route != "workout"
                val tab = when {
                    route == "home" -> Tab.Home
                    route.startsWith("list/") && currentSource == ListSources.WORKOUTS -> Tab.Activity
                    route == "music" -> Tab.Music
                    route == "settings" || route == "hud" || route == "units" || route == "about" || route.startsWith("linked/") || route.startsWith("list/") -> Tab.Settings
                    else -> null
                }
                val goTab: (Tab) -> Unit = { t ->
                    val dest = when (t) {
                        Tab.Home -> "home"
                        Tab.Activity -> listRoute(ListSources.WORKOUTS, filter = null)
                        Tab.Music -> "music"
                        Tab.Settings -> "settings"
                    }
                    nav.navigate(dest) {
                        popUpTo("home") { saveState = false }
                        launchSingleTop = true
                    }
                }

                Box(Modifier.fillMaxSize()) {
                    NavHost(nav, startDestination = "home", modifier = if (showNav) Modifier.padding(bottom = 84.dp) else Modifier) {
                        composable("home") {
                            HomeScreen(
                                services = services,
                                onSettings = { nav.navigate("settings") },
                                onWorkout = { nav.navigate("workout") },
                                onMusic = { nav.navigate("music") },
                                onActivity = { nav.navigate(listRoute(ListSources.WORKOUTS, filter = null)) },
                                onDevice = { nav.navigate("linked/$it") },
                            )
                        }
                        composable("workout") { WorkoutScreen(services, onBack = { nav.popBackStack() }, onMusic = { nav.navigate("music") }) }
                        composable("music") { MusicScreen(services, onBack = { nav.popBackStack() }) }
                        composable("linked/glasses") { LinkedGlassesScreen(services, onBack = { nav.popBackStack() }, onDisplay = { nav.navigate("hud") }, toast = toast) }
                        composable("linked/watch") { LinkedWatchScreen(services, onBack = { nav.popBackStack() }, toast = toast) }
                        composable("linked/music") { LinkedMusicScreen(services, onBack = { nav.popBackStack() }) }
                        composable("hud") {
                            val glasses by services.glasses.status.collectAsStateWithLifecycle()
                            com.debasish.livefit.phone.ui.settings.HudDisplayScreen(
                                store = services.settings,
                                glassesConnected = glasses.link == com.debasish.livefit.model.LinkState.Connected,
                                onApplied = toast,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable("units") { UnitsScreen(onBack = { nav.popBackStack() }) }
                        composable("about") { AboutScreen(onBack = { nav.popBackStack() }) }
                        composable("settings") {
                            SettingsScreen(
                                services = services,
                                onBack = { nav.popBackStack() },
                                onLanguages = { nav.navigate(listRoute(ListSources.LANGUAGES, filter = null)) },
                                onDeveloper = { startActivity(Intent(this@AppActivity, SpikeActivity::class.java)) },
                                onNavigate = { route ->
                                    nav.navigate(if (route == "permissions") listRoute(ListSources.PERMISSIONS, filter = null) else route)
                                },
                            )
                        }
                        composable(
                            "list/{source}?filter={filter}",
                            arguments = listOf(
                                navArgument("source") { type = NavType.StringType },
                                navArgument("filter") { type = NavType.StringType; nullable = true; defaultValue = null },
                            ),
                        ) { entry ->
                            ListScreen(
                                sourceId = entry.arguments?.getString("source").orEmpty(),
                                filterJson = entry.arguments?.getString("filter"),
                                onBack = { nav.popBackStack() },
                                onMessage = toast,
                            )
                        }
                    }
                    if (showNav) {
                        PillNav(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp), tab, goTab)
                    }
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp))
                }
            }
        }
    }

    companion object {
        /** Route into the generic list screen; [filter] is an optional JSON object string. */
        fun listRoute(source: String, filter: String?) =
            "list/$source" + if (filter != null) "?filter=${Uri.encode(filter)}" else ""
    }
}
