package dev.podlink

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.podlink.data.Prefs
import dev.podlink.service.PodsRepo
import dev.podlink.service.PodsService
import dev.podlink.ui.screens.HistoryScreen
import dev.podlink.ui.screens.HomeScreen
import dev.podlink.ui.screens.LabScreen
import dev.podlink.ui.screens.OnboardingScreen
import dev.podlink.ui.screens.SettingsScreen
import dev.podlink.ui.theme.PodlinkTheme
import dev.podlink.util.Permissions

class MainActivity : ComponentActivity() {
    private var openRadar by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PodsRepo.restore(this)
        openRadar = intent?.getBooleanExtra("find", false) == true
        setContent {
            val prefs = Prefs(this)
            val settings by prefs.flow.collectAsState(initial = null)
            val s = settings ?: return@setContent
            PodlinkTheme(theme = s.theme, dynamic = s.dynamicColor) {
                if (!s.onboardingDone || !Permissions.bluetoothGranted(this)) {
                    OnboardingScreen(prefs) { PodsService.start(this) }
                } else {
                    LaunchedEffect(s.serviceEnabled) { if (s.serviceEnabled && PodsRepo.service == null) PodsService.start(this@MainActivity) }
                    Root(prefs, openRadar) { openRadar = false }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("find", false)) openRadar = true
    }

    // The service suppresses the lid-open popup while the app is on screen.
    override fun onResume() { super.onResume(); PodsRepo.update { copy(appInForeground = true) } }
    override fun onPause() { super.onPause(); PodsRepo.update { copy(appInForeground = false) } }
}

private data class Tab(val route: String, val label: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
private fun Root(prefs: Prefs, openRadar: Boolean, onRadarConsumed: () -> Unit) {
    val nav = rememberNavController()
    val tabs = listOf(
        Tab("home", R.string.nav_home, Icons.Rounded.Home),
        Tab("history", R.string.nav_history, Icons.Rounded.Timeline),
        Tab("lab", R.string.nav_lab, Icons.Rounded.Science),
        Tab("settings", R.string.nav_settings, Icons.Rounded.Settings),
    )
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    fun go(route: String) { if (current != route) nav.navigate(route) { popUpTo("home") { saveState = true }; launchSingleTop = true; restoreState = true } }
    LaunchedEffect(openRadar) { if (openRadar) go("home") }
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = current == t.route,
                        onClick = { go(t.route) },
                        icon = { Icon(t.icon, contentDescription = stringResource(t.label)) },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(pad)) {
            composable("home") { HomeScreen(prefs, onNavigate = ::go, openRadar = openRadar); LaunchedEffect(openRadar) { if (openRadar) onRadarConsumed() } }
            composable("history") { HistoryScreen() }
            composable("lab") { LabScreen() }
            composable("settings") { SettingsScreen(prefs) }
        }
    }
}
