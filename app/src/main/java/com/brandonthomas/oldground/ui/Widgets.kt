package com.brandonthomas.oldground.ui

import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.brandonthomas.oldground.map.OgMap

// ---------- maps in Compose ----------

/** A map that follows the screen's lifecycle and is cleaned up when it leaves the screen. */
@Composable
fun rememberOgMap(key: Any, texture: Boolean = false, interactive: Boolean = true): OgMap {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val m = remember(key) { OgMap(ctx, texture, interactive) }
    DisposableEffect(m, lifecycle) {
        var started = false
        var resumed = false
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> if (!started) { m.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> if (!resumed) { m.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> if (resumed) { m.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> if (started) { m.onStop(); started = false }
                else -> {}
            }
        }
        lifecycle.addObserver(obs) // also brings the map up to the current state
        onDispose {
            lifecycle.removeObserver(obs)
            if (resumed) m.onPause()
            if (started) m.onStop()
            m.onDestroy()
        }
    }
    return m
}

@Composable
fun MapBox(m: OgMap, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { (m.view.parent as? ViewGroup)?.removeView(m.view); m.view },
        modifier = modifier,
    )
}

// ---------- controls ----------

@Composable
fun Pill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: String = "plain", enabled: Boolean = true) {
    val bg = when (kind) {
        "primary" -> C.accent
        "danger" -> C.bad
        "dark" -> C.scrim
        "green" -> C.green
        else -> C.panel
    }
    val fg = if (kind == "plain") C.ink else Color.White
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) bg else bg.copy(alpha = 0.45f))
            .then(if (kind == "plain") Modifier.border(1.dp, C.line, RoundedCornerShape(10.dp)) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@Composable
fun <T> Segmented(value: T, options: List<Pair<T, String>>, onChange: (T) -> Unit, modifier: Modifier = Modifier, dark: Boolean = false) {
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (dark) Color(0x73000000) else C.panel)
            .then(if (dark) Modifier else Modifier.border(1.dp, C.line, RoundedCornerShape(10.dp)))
            .padding(2.dp),
    ) {
        for ((v, label) in options) {
            val on = v == value
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) C.accent else Color.Transparent)
                    .clickable { onChange(v) }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on || dark) Color.White else C.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
            }
        }
    }
}

/** A label on a map that opens a layer picker. */
@Composable
fun LayerTag(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(C.scrim)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) { Text("$text ▾", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp) }
}

@Composable
fun Crosshair(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(48.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val s = 2.dp.toPx()
        drawLine(color, Offset(0f, c.y), Offset(size.width, c.y), s)
        drawLine(color, Offset(c.x, 0f), Offset(c.x, size.height), s)
        drawCircle(color, 9.dp.toPx(), c, style = Stroke(s))
    }
}

@Composable
fun Toast(text: String, modifier: Modifier = Modifier, color: Color = C.scrim) {
    Box(modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

// ---------- sheets and dialogs ----------

data class Choice(val text: String, val selected: Boolean = false, val danger: Boolean = false, val onPick: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChoiceSheet(title: String, choices: List<Choice>, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = C.panel) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = C.ink, modifier = Modifier.padding(bottom = 8.dp))
            for (c in choices) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onDismiss(); c.onPick() }.padding(vertical = 14.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(c.text, fontSize = 17.sp, color = if (c.danger) C.bad else C.ink, fontWeight = if (c.selected) FontWeight.ExtraBold else FontWeight.Normal)
                    if (c.selected) Text("✓", color = C.accent, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                }
            }
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, placeholder: String = "", onCancel: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text(placeholder) }) },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        containerColor = C.panel,
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = C.bad) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        containerColor = C.panel,
    )
}

@Composable
fun MessageDialog(title: String, text: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onClose) { Text("OK") } },
        containerColor = C.panel,
    )
}

@Composable
fun ScrollColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content() }
}

@Composable
fun Divider() = HorizontalDivider(color = C.line)
