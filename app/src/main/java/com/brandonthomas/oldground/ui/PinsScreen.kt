package com.brandonthomas.oldground.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.brandonthomas.oldground.Nav
import com.brandonthomas.oldground.Screen
import com.brandonthomas.oldground.data.Pin
import com.brandonthomas.oldground.data.PinKind
import com.brandonthomas.oldground.data.Repo
import com.brandonthomas.oldground.data.TrackMeta
import com.brandonthomas.oldground.geo.Gpx
import com.brandonthomas.oldground.geo.Mercator
import com.brandonthomas.oldground.location.LocationHub
import java.io.File
import java.text.DateFormat
import java.time.Instant
import java.util.Date

/** Every pin, nearest first, plus recorded tracks and GPX export. */
@Composable
fun PinsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val data by Repo.data.collectAsState()
    val trackPts by Repo.trackPoints.collectAsState()
    val fix by LocationHub.fix.collectAsState()
    val metric = data.settings.metric

    var filter by remember { mutableStateOf<PinKind?>(null) }
    var deletingTrack by remember { mutableStateOf<TrackMeta?>(null) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }

    val here = fix?.geo
    val pins = data.pins
        .filter { filter == null || it.kind == filter }
        .sortedWith(compareBy<Pin> { p -> here?.let { Mercator.distanceMeters(it, p.geo) } ?: 0.0 }.thenByDescending { it.createdAt })

    fun exportGpx() {
        if (data.pins.isEmpty() && data.tracks.isEmpty()) {
            message = "Nothing to export" to "Drop a pin or record a track first."
            return
        }
        val gpx = Gpx.build(
            data.pins.map { Gpx.Waypoint(it.name, it.geo, listOf(it.kind.label, it.note).filter { s -> s.isNotBlank() }.joinToString(" — "), Instant.ofEpochMilli(it.createdAt).toString()) },
            data.tracks.mapNotNull { t ->
                val pts = trackPts[t.id].orEmpty()
                if (pts.size < 2) null else Gpx.Track(t.name, pts.map { Gpx.TrackPoint(com.brandonthomas.oldground.geo.LngLat(it.lng, it.lat), it.timeMs) })
            },
        )
        val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
        val file = File(dir, "oldground.gpx")
        file.writeText(gpx)
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "OldGround pins and tracks")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { ctx.startActivity(Intent.createChooser(send, "Export GPX")) }
            .onFailure { message = "Couldn't share" to "No app on this device can receive the GPX file." }
    }

    Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton({ nav.back() }, "Back") { GlyphIcon(Glyph.BACK, C.ink) }
            Text("Pins & tracks", Modifier.weight(1f).padding(horizontal = 12.dp), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = C.ink)
            Pill("Export GPX", { exportGpx() }, kind = "primary")
        }
        ScrollColumn(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Pins (${data.pins.size})", Modifier.weight(1f))
                Segmented(metric, listOf(false to "ft / mi", true to "m / km"), { v -> Repo.updateSettings { it.copy(metric = v) } }, Modifier.width(160.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip("All", null, filter == null) { filter = null }
                for (k in PinKind.entries) FilterChip(k.label, k.color, filter == k) { filter = k }
            }
            Spacer(Modifier.height(8.dp))
            if (here == null && pins.isNotEmpty()) {
                Text("Waiting for GPS to show distances.", color = C.muted, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
            }
            if (pins.isEmpty()) {
                Text(
                    if (data.pins.isEmpty()) "No pins yet. On the hunt map, tap Pin to mark a home site, a find or a LiDAR feature."
                    else "No pins of this kind.",
                    color = C.muted, modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            for (p in pins) {
                val dist = here?.let { Mercator.distanceMeters(it, p.geo) }
                val dir = here?.let { Mercator.compass(Mercator.bearing(it, p.geo)) }
                Row(
                    Modifier.fillMaxWidth().card()
                        .clickable { nav.go(Screen.Hunt(p.mapId?.takeIf { id -> Repo.map(id)?.aligned == true }, p.id)) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(hexColor(p.kind.color)))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontWeight = FontWeight.Bold, color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = listOfNotNull(p.kind.label, p.mapId?.let { id -> Repo.map(id)?.name }, p.note.takeIf { it.isNotBlank() }).joinToString(" · ")
                        Text(sub, color = C.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (dist != null) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(Mercator.formatDistance(dist, metric), fontWeight = FontWeight.Bold, color = C.ink)
                            Text(dir ?: "", color = C.muted, fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle("Tracks (${data.tracks.size})")
            Spacer(Modifier.height(8.dp))
            if (data.tracks.isEmpty()) {
                Text("No tracks yet. Tap Record on the hunt map; it keeps recording with the screen off.", color = C.muted, modifier = Modifier.padding(vertical = 8.dp))
            }
            for (t in data.tracks) {
                val pts = trackPts[t.id].orEmpty()
                val length = pts.zipWithNext { a, b -> Mercator.distanceMeters(com.brandonthomas.oldground.geo.LngLat(a.lng, a.lat), com.brandonthomas.oldground.geo.LngLat(b.lng, b.lat)) }.sum()
                val minutes = if (pts.size >= 2) (pts.last().timeMs - pts.first().timeMs) / 60000 else 0
                val recording = data.recording == t.id
                Row(
                    Modifier.fillMaxWidth().card().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name + if (recording) " • recording" else "", fontWeight = FontWeight.Bold, color = if (recording) C.bad else C.ink)
                        Text(
                            "${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t.startedAt))} · ${Mercator.formatDistance(length, metric)} · ${minutes / 60}h ${minutes % 60}m",
                            color = C.muted, fontSize = 13.sp,
                        )
                    }
                    Pill("Delete", { deletingTrack = t }, kind = "soft", enabled = !recording)
                }
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    deletingTrack?.let { t ->
        ConfirmDialog("Delete this track?", "\"${t.name}\" will be removed for good.", "Delete",
            onCancel = { deletingTrack = null }) { Repo.deleteTrack(t.id); deletingTrack = null }
    }
    message?.let { (t, b) -> MessageDialog(t, b) { message = null } }
}

@Composable
private fun FilterChip(label: String, color: String?, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp))
            .background(if (on) C.accent else C.panel)
            .border(1.dp, if (on) C.accent else C.line, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (color != null) Box(Modifier.size(10.dp).clip(CircleShape).background(hexColor(color)))
        Text(label, fontSize = 13.sp, color = if (on) Color.White else C.ink, fontWeight = FontWeight.Bold)
    }
}
