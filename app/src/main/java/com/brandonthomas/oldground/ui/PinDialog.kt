package com.brandonthomas.oldground.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brandonthomas.oldground.data.PinKind
import com.brandonthomas.oldground.geo.Mercator
import java.util.Locale

fun hexColor(hex: String): Color = Color(android.graphics.Color.parseColor(hex))

/** Add or edit a pin: name, kind, note. */
@Composable
fun PinDialog(
    d: PinDraft,
    distance: Double?,
    metric: Boolean,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (PinDraft) -> Unit,
) {
    var name by remember { mutableStateOf(d.name) }
    var kind by remember { mutableStateOf(d.kind) }
    var note by remember { mutableStateOf(d.note) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (d.editing != null) "Edit pin" else "New pin") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    placeholder = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (k in PinKind.entries) {
                        val on = k == kind
                        Row(
                            Modifier.clip(RoundedCornerShape(20.dp))
                                .background(if (on) C.ink else C.panel)
                                .border(1.dp, if (on) C.ink else C.line, RoundedCornerShape(20.dp))
                                .clickable { kind = k }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(hexColor(k.color)))
                            Text(k.label, fontSize = 13.sp, color = if (on) Color.White else C.ink, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                OutlinedTextField(value = note, onValueChange = { note = it }, minLines = 2, maxLines = 5,
                    placeholder = { Text("Note") }, modifier = Modifier.fillMaxWidth())
                Text(String.format(Locale.US, "%.6f, %.6f", d.geo.lat, d.geo.lng), color = C.muted, fontSize = 13.sp)
                distance?.let { Text("${Mercator.formatDistance(it, metric)} from you", color = C.muted, fontSize = 13.sp) }
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete pin", color = C.bad) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(d.copy(name = name.trim(), kind = kind, note = note.trim())) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        containerColor = C.panel,
    )
    if (confirmDelete && onDelete != null) {
        ConfirmDialog("Delete this pin?", "\"${name.ifBlank { "Pin" }}\" will be removed.", "Delete",
            onCancel = { confirmDelete = false }, onConfirm = { confirmDelete = false; onDelete() })
    }
}
