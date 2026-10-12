package com.brandonthomas.oldground.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Field palette: readable in bright sun. Rust accent, survey green, warm paper. */
object C {
    val bg = Color(0xFFF2EFE6)
    val panel = Color(0xFFFFFFFF)
    val ink = Color(0xFF1F2622)
    val muted = Color(0xFF5D675F)
    val line = Color(0xFFD6D2C4)
    val accent = Color(0xFFA4541C)
    val green = Color(0xFF2F5D46)
    val good = Color(0xFF2E8B57)
    val check = Color(0xFFD4A017)
    val bad = Color(0xFFC0392B)
    val scrim = Color(0xB8141816)

    const val goodHex = "#2E8B57"
    const val checkHex = "#D4A017"
    const val badHex = "#C0392B"
    const val unknownHex = "#7F8C8D"
    const val rustHex = "#A4541C"
}

@Composable
fun OldGroundTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = C.accent,
            onPrimary = Color.White,
            secondary = C.green,
            background = C.bg,
            surface = C.panel,
            onSurface = C.ink,
            onBackground = C.ink,
            error = C.bad,
        ),
        content = content,
    )
}
