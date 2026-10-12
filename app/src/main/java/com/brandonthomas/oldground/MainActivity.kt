package com.brandonthomas.oldground

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import com.brandonthomas.oldground.location.LocationHub
import com.brandonthomas.oldground.ui.AlignScreen
import com.brandonthomas.oldground.ui.CrashReport
import com.brandonthomas.oldground.ui.HomeScreen
import com.brandonthomas.oldground.ui.HuntScreen
import com.brandonthomas.oldground.ui.OldGroundTheme
import com.brandonthomas.oldground.ui.SiteScreen
import com.brandonthomas.oldground.ui.SitesScreen
import com.brandonthomas.oldground.ui.VisitScreen

sealed interface Screen {
    data object Home : Screen
    data class Align(val mapId: String) : Screen
    data class Hunt(
        val mapId: String?, val pinId: String? = null, val mode: String? = null, val debug: String? = null,
        val draw: String? = null, val siteId: String? = null, val startVisit: Boolean = false,
    ) : Screen
    /** Sites, All finds, Pins & tracks (tab 0, 1, 2). */
    data class Sites(val tab: Int = 0) : Screen
    data class Site(val id: String, val debug: String? = null) : Screen
    data class Visit(val siteId: String, val day: String) : Screen
}

class Nav(private val stack: MutableList<Screen>) {
    val current get() = stack.last()
    val canGoBack get() = stack.size > 1
    fun go(s: Screen) { stack.add(s) }
    fun replace(s: Screen) { stack[stack.size - 1] = s }
    fun back() { if (stack.size > 1) stack.removeAt(stack.size - 1) }
}

class MainActivity : ComponentActivity() {
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        LocationHub.restart(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!LocationHub.hasPermission(this)) {
            askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        // Used by the automated test to open a map straight away.
        val start = buildList<Screen> {
            add(Screen.Home)
            val i = intent
            val mapExtra = i?.getStringExtra("openMap")?.takeIf { it != "none" }
            when (i?.getStringExtra("screen")) {
                "align" -> mapExtra?.let { add(Screen.Align(it)) }
                "sites" -> add(Screen.Sites(when (i.getStringExtra("debug")) { "finds" -> 1; "pins" -> 2; else -> 0 }))
                "site" -> i.getStringExtra("site")?.let { add(Screen.Site(it, i.getStringExtra("debug"))) }
                "visit" -> {
                    val site = i.getStringExtra("site")
                    val day = i.getStringExtra("day")
                    if (site != null && day != null) { add(Screen.Site(site)); add(Screen.Visit(site, day)) }
                }
                else -> if (i?.hasExtra("openMap") == true) {
                    add(
                        Screen.Hunt(
                            mapExtra, i.getStringExtra("pin"), i.getStringExtra("mode"), i.getStringExtra("debug"),
                            draw = i.getStringExtra("draw"), siteId = i.getStringExtra("site"),
                        ),
                    )
                }
            }
        }
        setContent { OldGroundTheme { App(start) } }
    }

    override fun onResume() {
        super.onResume()
        LocationHub.acquire(this)
    }

    override fun onPause() {
        LocationHub.release()
        super.onPause()
    }
}

@Composable
private fun App(start: List<Screen>) {
    val stack = remember { mutableStateListOf<Screen>().apply { addAll(start) } }
    val nav = remember { Nav(stack) }
    BackHandler(enabled = stack.size > 1) { nav.back() }
    val s = stack.last()
    // A fresh screen (and fresh maps) for each step in the stack.
    key(stack.size, s) {
        when (s) {
            Screen.Home -> HomeScreen(nav)
            is Screen.Align -> AlignScreen(nav, s.mapId)
            is Screen.Hunt -> HuntScreen(nav, s.mapId, s.pinId, s.mode, s.debug, s.draw, s.siteId, s.startVisit)
            is Screen.Sites -> SitesScreen(nav, s.tab)
            is Screen.Site -> SiteScreen(nav, s.id, s.debug)
            is Screen.Visit -> VisitScreen(nav, s.siteId, s.day)
        }
    }
    CrashReport()
}
