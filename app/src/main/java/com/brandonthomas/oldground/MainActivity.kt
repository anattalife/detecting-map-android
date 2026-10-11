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
import com.brandonthomas.oldground.ui.HomeScreen
import com.brandonthomas.oldground.ui.HuntScreen
import com.brandonthomas.oldground.ui.OldGroundTheme
import com.brandonthomas.oldground.ui.PinsScreen

sealed interface Screen {
    data object Home : Screen
    data class Align(val mapId: String) : Screen
    data class Hunt(val mapId: String?, val pinId: String? = null) : Screen
    data object Pins : Screen
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
        setContent { OldGroundTheme { App() } }
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
private fun App() {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val nav = remember { Nav(stack) }
    BackHandler(enabled = stack.size > 1) { nav.back() }
    val s = stack.last()
    // A fresh screen (and fresh maps) for each step in the stack.
    key(stack.size, s) {
        when (s) {
            Screen.Home -> HomeScreen(nav)
            is Screen.Align -> AlignScreen(nav, s.mapId)
            is Screen.Hunt -> HuntScreen(nav, s.mapId, s.pinId)
            Screen.Pins -> PinsScreen(nav)
        }
    }
}
