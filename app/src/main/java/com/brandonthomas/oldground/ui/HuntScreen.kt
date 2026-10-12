package com.brandonthomas.oldground.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.brandonthomas.oldground.Nav
import com.brandonthomas.oldground.data.Layer
import com.brandonthomas.oldground.data.LidarStyle
import com.brandonthomas.oldground.data.OldMap
import com.brandonthomas.oldground.data.Pin
import com.brandonthomas.oldground.data.Repo
import com.brandonthomas.oldground.data.Settings
import com.brandonthomas.oldground.data.Slot
import com.brandonthomas.oldground.data.TrackPt
import com.brandonthomas.oldground.geo.Georef
import com.brandonthomas.oldground.geo.LngLat
import com.brandonthomas.oldground.geo.Mercator
import com.brandonthomas.oldground.geo.TileMath
import com.brandonthomas.oldground.geo.XY
import com.brandonthomas.oldground.location.Fix
import com.brandonthomas.oldground.location.LocationHub
import com.brandonthomas.oldground.location.TrackService
import com.brandonthomas.oldground.map.Base
import com.brandonthomas.oldground.map.Cam
import com.brandonthomas.oldground.map.Data
import com.brandonthomas.oldground.map.Features
import com.brandonthomas.oldground.map.OfflineAreas
import com.brandonthomas.oldground.map.OgMap
import com.brandonthomas.oldground.map.PublicLand
import com.brandonthomas.oldground.map.Sources
import com.brandonthomas.oldground.map.SwipeFrame
import com.brandonthomas.oldground.map.TileJobs
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.roundToInt

enum class Mode(val label: String) { OVERLAY("Overlay"), SWIPE("Swipe"), SPLIT("Split"), DRIVE("Drive") }

/** Converts between real-world positions and the open old map's scan frame. */
class ScanFrame(val map: OldMap, val georef: Georef) {
    private val w = map.width
    private val h = map.height
    /** World -> scan-frame position, or null when off the sheet. */
    fun place(g: LngLat): LngLat? = georef.geoToImg(g)?.let { (img, on) -> if (on) TileMath.scanToLngLat(img, w, h) else null }
    /** World -> scan-frame position, even off the sheet (for lines and syncing). */
    fun placeAny(g: LngLat): LngLat? = georef.geoToImg(g)?.first?.let { TileMath.scanToLngLat(it, w, h) }
    fun toGeo(s: LngLat): LngLat? = georef.imgToGeo(TileMath.lngLatToScan(s, w, h))
    /** Zoom difference: scan zoom = map zoom + this, near a world position. */
    fun zoomShift(g: LngLat): Double {
        val img = georef.geoToImg(g)?.first ?: return 0.0
        val mpp = georef.mercPerImgPx(img) ?: return 0.0
        return TileMath.log2(mpp / TileMath.SCAN_MERC_PER_PX)
    }
    /** Which way is north on the scan, degrees clockwise from the scan's "up". */
    fun northAngle(g: LngLat): Double {
        val a = georef.geoToImg(g)?.first ?: return 0.0
        val b = georef.geoToImg(LngLat(g.lng, g.lat + 0.0002))?.first ?: return 0.0
        return Math.toDegrees(atan2(b.x - a.x, -(b.y - a.y)))
    }
    fun ready() = map.scan?.ready == true
    fun base(): Base? = map.scan?.takeIf { it.ready }?.let { Base.Scan(Sources.tiles(map.id, it, "scan-${it.zDone}")) }
}

data class PinDraft(val geo: LngLat, val name: String, val kind: com.brandonthomas.oldground.data.PinKind, val note: String, val editing: String?)

/**
 * The hunt screen. Four views, each showing layers you choose (LiDAR, Satellite, Topo,
 * Streets, and the old map when one is open). Tap a ▾ label to change a layer.
 *   Overlay - one layer over another, with a fade slider
 *   Swipe   - drag a divider between two layers
 *   Split   - two panes, one above the other, kept in sync
 *   Drive   - one full-screen layer that follows you
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HuntScreen(nav: Nav, mapId: String?, pinId: String?, startMode: String? = null) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val data by Repo.data.collectAsState()
    val trackPts by Repo.trackPoints.collectAsState()
    val fix by LocationHub.fix.collectAsState()
    val progress by TileJobs.progress.collectAsState()
    val settings = data.settings
    val map = data.maps.firstOrNull { it.id == mapId }
    val georef = remember(map?.anchors, map?.width, map?.height) { map?.let { Georef(it.anchorPoints(), it.width, it.height) } }
    val frame = remember(map, georef) { if (map != null && georef?.ready == true) ScanFrame(map, georef) else null }
    val hasOld = frame != null

    var mode by rememberSaveable { mutableStateOf(Mode.entries.firstOrNull { it.name == startMode } ?: Mode.OVERLAY) }
    val chosen = remember { mutableStateMapOf<Slot, Layer>() }
    var picking by remember { mutableStateOf<Slot?>(null) }
    var follow by remember { mutableStateOf(false) }
    var pinMode by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<PinDraft?>(null) }
    var moreOpen by remember { mutableStateOf(false) }
    var download by remember { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirmDownload by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var landInfo by remember { mutableStateOf<PublicLand.Info?>(null) }
    val camMemory = remember { arrayOfNulls<Cam>(1) }
    val userTookOver = remember { BooleanArray(1) }

    // Make sure tiles exist (and keep building in the background).
    LaunchedEffect(map?.anchors) {
        map?.let {
            TileJobs.ensureScan(it)
            if (it.aligned) TileJobs.ensureWarp(it)
        }
    }

    fun slot(k: Slot): Layer {
        chosen[k]?.let { if (it != Layer.OLDMAP || hasOld) return it }
        if (hasOld && k.primary) return Layer.OLDMAP
        return settings.slots[k] ?: k.default
    }
    fun choose(k: Slot, l: Layer) {
        chosen[k] = l
        if (l != Layer.OLDMAP) Repo.updateSettings { it.copy(slots = it.slots + (k to l)) }
    }

    val focus = pinId?.let { id -> data.pins.firstOrNull { it.id == id } }
    val initial = remember {
        val c = focus?.geo ?: map?.anchors?.takeIf { it.isNotEmpty() }?.let { a ->
            LngLat(a.map { it.lng }.average(), a.map { it.lat }.average())
        } ?: LocationHub.fix.value?.geo ?: LngLat(-82.99, 39.96)
        Cam(c, 16.0, 0.0)
    }
    fun startCam() = camMemory[0] ?: initial

    // Maps currently on screen, with how to place real-world positions on each.
    val live = remember { mutableStateMapOf<String, Pair<OgMap, ScanFrame?>>() }

    // ---- shared map behaviour ----
    fun onTapAt(scan: ScanFrame?): (LngLat, String?, Map<String, String>?) -> Unit = { at, hit, land ->
        val geo = if (scan != null) scan.toGeo(at) else at
        when {
            pinMode && geo != null -> {
                pinMode = false
                draft = PinDraft(geo, "", com.brandonthomas.oldground.data.PinKind.HOMESITE, "", null)
            }
            hit != null -> data.pins.firstOrNull { it.id == hit }?.let { p -> draft = PinDraft(p.geo, p.name, p.kind, p.note, p.id) }
            land != null -> landInfo = PublicLand.describe(land)
        }
    }
    fun rememberCam(c: Cam, scan: ScanFrame?) {
        camMemory[0] = if (scan == null) c else scan.toGeo(c.center)?.let { g -> Cam(g, c.zoom - scan.zoomShift(g), 0.0) } ?: camMemory[0]
    }

    val statusTop = with(density) { WindowInsets.statusBars.getTop(this) }
    val compassTop = statusTop + with(density) { 112.dp.roundToPx() }

    Box(Modifier.fillMaxSize().background(Color(0xFFE9E6DC))) {
        key(mode, hasOld) {
            when (mode) {
                Mode.OVERLAY -> {
                    val m = rememberOgMap("overlay")
                    val top = slot(Slot.OVERLAY_TOP)
                    val bottom = slot(Slot.OVERLAY_BOTTOM)
                    LaunchedEffect(Unit) {
                        m.compassTopPx = compassTop
                        startCam().let { m.setCamera(it.center, it.zoom, it.bearing) }
                    }
                    LaunchedEffect(bottom, settings.lidarStyle, settings.lidarAzimuth) { m.setBase(Base.Geo(bottom, lidarKey(settings)), settings) }
                    LaunchedEffect(top, settings, map?.warp?.zDone, map?.warp?.dir) {
                        m.setOverlay(Sources.raster(top, settings, map), settings.overlayOpacity)
                    }
                    LiveMap("main", m, null, live)
                    SideEffect {
                        m.onTap = onTapAt(null)
                        m.onMove = { c, user -> rememberCam(c, null); if (user) { follow = false; userTookOver[0] = true } }
                    }
                    MapBox(m, Modifier.fillMaxSize())
                    FlowRow(
                        Modifier.statusBarsPadding().padding(top = 60.dp, start = 8.dp, end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        LayerTag(top.label, { picking = Slot.OVERLAY_TOP })
                        Text("over", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterVertically))
                        LayerTag(bottom.label, { picking = Slot.OVERLAY_BOTTOM })
                    }
                }

                Mode.SWIPE -> {
                    val lower = rememberOgMap("swipe-lower")
                    val upper = rememberOgMap("swipe-upper", texture = true, interactive = false)
                    val left = slot(Slot.SWIPE_LEFT)
                    val right = slot(Slot.SWIPE_RIGHT)
                    LaunchedEffect(Unit) {
                        lower.compassTopPx = compassTop
                        lower.rotateEnabled = false
                        startCam().let {
                            lower.setCamera(it.center, it.zoom, 0.0)
                            upper.setCamera(it.center, it.zoom, 0.0)
                        }
                    }
                    LaunchedEffect(right, settings.lidarStyle, settings.lidarAzimuth) { lower.setBase(Base.Geo(right, lidarKey(settings)), settings) }
                    LaunchedEffect(left, right, settings, map?.warp?.zDone, map?.warp?.dir) {
                        if (left == Layer.STREETS) {
                            upper.setBase(Base.Geo(Layer.STREETS), settings)
                            upper.setOverlay(null)
                        } else {
                            upper.setBase(Base.Geo(right, lidarKey(settings)), settings)
                            upper.setOverlay(Sources.raster(left, settings, map), 1f)
                        }
                    }
                    LiveMap("main", lower, null, live)
                    LiveMap("upper", upper, null, live)
                    SideEffect {
                        lower.onTap = onTapAt(null)
                        lower.onMove = { c, user ->
                            upper.setCamera(c.center, c.zoom, c.bearing)
                            rememberCam(c, null)
                            if (user) { follow = false; userTookOver[0] = true }
                        }
                        lower.onIdle = { c, _ -> upper.setCamera(c.center, c.zoom, c.bearing) }
                    }
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val widthPx = constraints.maxWidth
                        var dividerPx by remember { mutableFloatStateOf(widthPx / 2f) }
                        AndroidView(
                            factory = { SwipeFrame(it, lower.view, upper.view) },
                            update = { it.setDivider(dividerPx.roundToInt()) },
                            modifier = Modifier.fillMaxSize(),
                        )
                        // Divider handle: only this strip takes touches.
                        Box(
                            Modifier
                                .offset { IntOffset(dividerPx.roundToInt() - with(density) { 24.dp.roundToPx() }, 0) }
                                .width(48.dp)
                                .fillMaxHeight()
                                .pointerInput(widthPx) {
                                    detectDragGestures { change, drag ->
                                        change.consume()
                                        dividerPx = (dividerPx + drag.x).coerceIn(24f, widthPx - 24f)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.width(3.dp).fillMaxHeight().background(Color.White))
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).background(C.accent),
                                contentAlignment = Alignment.Center,
                            ) { Text("‹ ›", color = Color.White, fontWeight = FontWeight.ExtraBold) }
                        }
                    }
                    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 150.dp, start = 8.dp, end = 8.dp)) {
                        LayerTag(left.label, { picking = Slot.SWIPE_LEFT }, Modifier.align(Alignment.BottomStart))
                        LayerTag(right.label, { picking = Slot.SWIPE_RIGHT }, Modifier.align(Alignment.BottomEnd))
                    }
                }

                Mode.SPLIT -> {
                    val topLayer = slot(Slot.SPLIT_TOP)
                    val bottomLayer = slot(Slot.SPLIT_BOTTOM)
                    val leader = remember { arrayOf("top") }
                    Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 56.dp)) {
                        val panes = listOf("top" to topLayer, "bottom" to bottomLayer)
                        val maps = HashMap<String, Pair<OgMap, ScanFrame?>>()
                        for ((side, layer) in panes) {
                            val scan = if (layer == Layer.OLDMAP) frame else null
                            key(side, layer, scan != null) {
                                val m = rememberOgMap("split-$side-$layer")
                                maps[side] = m to scan
                                LaunchedEffect(Unit) {
                                    m.rotateEnabled = false
                                    val c = startCam()
                                    if (scan != null) {
                                        scan.placeAny(c.center)?.let { m.setCamera(it, c.zoom + scan.zoomShift(c.center), 0.0) }
                                    } else m.setCamera(c.center, c.zoom, 0.0)
                                }
                                LaunchedEffect(layer, settings.lidarStyle, settings.lidarAzimuth, map?.scan?.zDone) {
                                    val b = if (scan != null) scan.base() else Base.Geo(layer, lidarKey(settings))
                                    b?.let { m.setBase(it, settings) }
                                }
                                LiveMap(side, m, scan, live)
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    MapBox(m, Modifier.fillMaxSize())
                                    LayerTag(layer.label, { picking = if (side == "top") Slot.SPLIT_TOP else Slot.SPLIT_BOTTOM }, Modifier.padding(8.dp))
                                }
                            }
                            if (side == "top") Box(Modifier.fillMaxWidth().height(3.dp).background(C.accent))
                        }
                        SideEffect {
                            for ((side, pair) in maps) {
                                val (m, scan) = pair
                                val otherSide = if (side == "top") "bottom" else "top"
                                m.onTap = onTapAt(scan)
                                m.onMove = { c, user ->
                                    if (user) {
                                        leader[0] = side
                                        follow = false
                                        userTookOver[0] = true
                                    }
                                    if (leader[0] == side) {
                                        rememberCam(c, scan)
                                        val geo = if (scan != null) scan.toGeo(c.center) else c.center
                                        val geoZoom = if (scan != null && geo != null) c.zoom - scan.zoomShift(geo) else c.zoom
                                        val other = maps[otherSide]
                                        if (geo != null && other != null) {
                                            val (om, oscan) = other
                                            if (oscan != null) oscan.placeAny(geo)?.let { om.setCamera(it, geoZoom + oscan.zoomShift(geo), 0.0) }
                                            else om.setCamera(geo, geoZoom, 0.0)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Mode.DRIVE -> {
                    val layer = slot(Slot.DRIVE)
                    val scan = if (layer == Layer.OLDMAP) frame else null
                    key(layer, scan != null) {
                        val m = rememberOgMap("drive-$layer")
                        LaunchedEffect(Unit) {
                            follow = true
                            m.compassTopPx = compassTop
                            m.rotateEnabled = scan == null
                            val c = fix?.geo?.let { Cam(it, maxOf(startCam().zoom, 17.0), 0.0) } ?: startCam()
                            if (scan != null) scan.placeAny(c.center)?.let { m.setCamera(it, c.zoom + scan.zoomShift(c.center), 0.0) }
                            else m.setCamera(c.center, c.zoom, 0.0)
                        }
                        LaunchedEffect(layer, settings.lidarStyle, settings.lidarAzimuth, map?.scan?.zDone) {
                            val b = if (scan != null) scan.base() else Base.Geo(layer, lidarKey(settings))
                            b?.let { m.setBase(it, settings) }
                        }
                        LiveMap("main", m, scan, live)
                        SideEffect {
                            m.onTap = onTapAt(scan)
                            m.onMove = { c, user -> rememberCam(c, scan); if (user) follow = false }
                        }
                        MapBox(m, Modifier.fillMaxSize())
                        if (scan != null && fix != null && scan.place(fix!!.geo) == null) {
                            Toast("You're off the edge of this map", Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 110.dp), C.bad)
                        }
                    }
                    LayerTag(layer.label, { picking = Slot.DRIVE }, Modifier.statusBarsPadding().padding(top = 60.dp, start = 8.dp))
                }
            }
        }

        // ---- keep data on every map current ----
        for ((_, pair) in live) {
            val (m, scan) = pair
            MapDataSync(m, scan, data.pins, trackPts, fix, settings.publicLand)
        }

        // ---- follow me ----
        LaunchedEffect(fix, follow, mode) {
            val f = fix ?: return@LaunchedEffect
            // Without an old map, open on where you are once GPS finds you.
            if (!follow && mapId == null && pinId == null && !userTookOver[0] && camMemory[0] == null) {
                userTookOver[0] = true
                for ((_, p) in live) if (p.second == null) p.first.setCamera(f.geo)
                return@LaunchedEffect
            }
            if (!follow) return@LaunchedEffect
            for ((name, p) in live) {
                val (m, scan) = p
                if (name == "upper") continue
                if (scan != null) scan.placeAny(f.geo)?.let { m.setCamera(it) }
                else if (mode == Mode.DRIVE && f.heading != null) m.setCamera(f.geo, bearing = f.heading.toDouble())
                else m.setCamera(f.geo)
            }
        }

        // ---- top bar ----
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(C.scrim).clickable { nav.back() },
                contentAlignment = Alignment.Center,
            ) { Text("‹", color = Color.White, fontSize = 26.sp) }
            Segmented(mode, Mode.entries.map { it to it.label }, { mode = it }, Modifier.weight(1f), dark = true)
        }

        // ---- status toasts ----
        val building = mapId?.let { progress["warp:$it"] ?: progress["scan:$it"] }
        Column(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 112.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (building != null) Toast("Preparing old map… ${(building * 100).toInt()}%")
            download?.let { Toast("Saving area offline… $it%") }
            if (pinMode) Toast("Tap the map to place a pin", color = C.accent)
        }

        // ---- bottom controls ----
        BottomControls(
            mode, settings, follow, pinMode, data.recording != null, fix,
            topLabel = slot(Slot.OVERLAY_TOP).label,
            onMore = { moreOpen = true },
            onPin = { pinMode = !pinMode },
            onPinHere = {
                val f = LocationHub.fix.value
                if (f == null) message = "No GPS fix yet" to "Wait for the blue dot, or tap Pin and then tap the map."
                else draft = PinDraft(f.geo, "", com.brandonthomas.oldground.data.PinKind.HOMESITE, "", null)
            },
            onLocate = {
                val f = LocationHub.fix.value
                if (!follow && f != null) for ((name, p) in live) {
                    if (name == "upper") continue
                    val (m, scan) = p
                    if (scan != null) scan.placeAny(f.geo)?.let { m.setCamera(it) } else m.setCamera(f.geo)
                }
                follow = !follow
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    // ---- sheets and dialogs ----
    picking?.let { k ->
        val current = slot(k)
        ChoiceSheet(k.title, k.options.filter { it != Layer.OLDMAP || hasOld }.map { l -> Choice(l.label, l == current) { choose(k, l) } }) { picking = null }
    }
    if (moreOpen) {
        ModalBottomSheet(onDismissRequest = { moreOpen = false }, containerColor = C.panel) {
            Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("LiDAR look")
                Segmented(settings.lidarStyle, LidarStyle.entries.map { it to it.label }, { v -> Repo.updateSettings { it.copy(lidarStyle = v) } })
                if (settings.lidarStyle == LidarStyle.SUN) {
                    var az by remember { mutableFloatStateOf(settings.lidarAzimuth.toFloat()) }
                    Text("Sun ${az.roundToInt()}°", color = C.ink)
                    Slider(
                        value = az, onValueChange = { az = it }, valueRange = 0f..345f, steps = 22,
                        onValueChangeFinished = { Repo.updateSettings { it.copy(lidarAzimuth = az.roundToInt()) } },
                        colors = SliderDefaults.colors(thumbColor = C.accent, activeTrackColor = C.accent),
                    )
                }
                SectionTitle("Public land")
                Segmented(settings.publicLand, listOf(true to "Show", false to "Hide"), { v -> Repo.updateSettings { it.copy(publicLand = v) } })
                Text("Federal, state, county and city land from USGS. Tap an area to see who manages it.", color = C.muted, fontSize = 13.sp)
                SectionTitle("Units")
                Segmented(settings.metric, listOf(false to "Feet / miles", true to "Meters / km"), { v -> Repo.updateSettings { it.copy(metric = v) } })
                Pill("Save this area for offline", {
                    moreOpen = false
                    val main = live["main"]?.takeIf { it.second == null } ?: live.values.firstOrNull { it.second == null }
                    val b = main?.first?.visibleBounds()
                    if (b == null) {
                        message = "Nothing to save here" to "Old maps are already stored on this device. Switch a pane to a map layer to save it."
                    } else {
                        val layers = visibleLayers(mode, ::slot).filter { it != Layer.OLDMAP }.ifEmpty { listOf(Layer.AERIAL) }
                        val tiles = OfflineAreas.estimateTiles(b[0], b[1], b[2], b[3], 10, 17) * layers.size
                        if (tiles > 20000) message = "Area too large" to "Zoom in closer to the area you'll hunt and try again."
                        else confirmDownload = "${layers.joinToString(" and ") { it.label }}, about $tiles tiles. Old maps are always available offline." to {
                            download = 0
                            val name = "${map?.name ?: "Hunt area"} ${java.text.DateFormat.getDateInstance().format(java.util.Date())}"
                            if (settings.publicLand) Repo.scope.launch { PublicLand.prefetch(ctx, b) }
                            OfflineAreas.download(ctx, name, b, layers, settings, { download = it }) { err ->
                                download = null
                                if (err != null) message = "Download stopped" to err
                            }
                        }
                    }
                }, Modifier.fillMaxWidth())
            }
        }
    }
    confirmDownload?.let { (text, go) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDownload = null },
            title = { Text("Save this area for offline use?") },
            text = { Text(text) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmDownload = null; go() }) { Text("Download") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDownload = null }) { Text("Cancel") } },
            containerColor = C.panel,
        )
    }
    draft?.let { d ->
        PinDialog(d, fix?.let { Mercator.distanceMeters(it.geo, d.geo) }, settings.metric,
            onCancel = { draft = null },
            onDelete = d.editing?.let { id -> { Repo.deletePin(id); draft = null } },
        ) { saved ->
            if (saved.editing != null) Repo.updatePin(saved.editing) { it.copy(name = saved.name, kind = saved.kind, note = saved.note) }
            else Repo.addPin(Pin(Repo.newId(), saved.name.ifBlank { "Pin" }, saved.kind, saved.geo.lng, saved.geo.lat, saved.note, mapId, System.currentTimeMillis()))
            draft = null
        }
    }
    message?.let { (t, b) -> MessageDialog(t, b) { message = null } }
    landInfo?.let { i ->
        MessageDialog(i.title, listOf(i.manager, i.access).filter { it.isNotBlank() }.joinToString(" · ") + "\n\n" + i.advice) { landInfo = null }
    }
}

private fun lidarKey(s: Settings) = "${s.lidarStyle}-${s.lidarAzimuth}"

private fun visibleLayers(mode: Mode, slot: (Slot) -> Layer): List<Layer> = when (mode) {
    Mode.OVERLAY -> listOf(slot(Slot.OVERLAY_TOP), slot(Slot.OVERLAY_BOTTOM))
    Mode.SWIPE -> listOf(slot(Slot.SWIPE_LEFT), slot(Slot.SWIPE_RIGHT))
    Mode.SPLIT -> listOf(slot(Slot.SPLIT_TOP), slot(Slot.SPLIT_BOTTOM))
    Mode.DRIVE -> listOf(slot(Slot.DRIVE))
}

/** Registers a map as on screen while this is in the composition. */
@Composable
private fun LiveMap(name: String, m: OgMap, scan: ScanFrame?, live: MutableMap<String, Pair<OgMap, ScanFrame?>>) {
    androidx.compose.runtime.DisposableEffect(m, scan) {
        live[name] = m to scan
        onDispose { if (live[name]?.first === m) live.remove(name) }
    }
}

/** Pins, tracks and the GPS dot on one map, kept current. */
@Composable
private fun MapDataSync(m: OgMap, scan: ScanFrame?, pins: List<Pin>, tracks: Map<String, List<TrackPt>>, fix: Fix?, publicLand: Boolean) {
    val ctx = LocalContext.current
    // Public land on real-world maps, refreshed whenever the map stops moving.
    androidx.compose.runtime.DisposableEffect(m, scan, publicLand) {
        if (scan != null || !publicLand) {
            m.setData(Data.LAND, Features.empty)
            onDispose { }
        } else {
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
            var job: kotlinx.coroutines.Job? = null
            val refresh: () -> Unit = {
                job?.cancel()
                job = scope.launch {
                    val b = m.visibleBounds() ?: return@launch
                    val fc = try {
                        PublicLand.features(ctx, b, m.cam().zoom)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        return@launch // keep what's shown
                    }
                    m.setData(Data.LAND, fc ?: Features.empty)
                }
            }
            m.idleListeners.add(refresh)
            refresh()
            onDispose {
                m.idleListeners.remove(refresh)
                scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            }
        }
    }
    val place: (LngLat) -> LngLat? = { g -> if (scan == null) g else scan.place(g) }
    val placeLine: (LngLat) -> LngLat? = { g -> if (scan == null) g else scan.placeAny(g) }
    LaunchedEffect(m, scan, pins) { m.setData(Data.PINS, Features.pins(pins, place)) }
    LaunchedEffect(m, scan, tracks) { m.setData(Data.TRACKS, Features.tracks(tracks.values, placeLine)) }
    LaunchedEffect(m, scan, fix) {
        val north = if (scan != null && fix != null) scan.northAngle(fix.geo) else 0.0
        m.setData(Data.GPS, Features.gps(fix, place, north))
    }
}

@Composable
private fun BottomControls(
    mode: Mode, settings: Settings, follow: Boolean, pinMode: Boolean, recording: Boolean, fix: Fix?, topLabel: String,
    onMore: () -> Unit, onPin: () -> Unit, onPinHere: () -> Unit, onLocate: () -> Unit, modifier: Modifier,
) {
    val ctx = LocalContext.current
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { TrackService.start(ctx) }
    Column(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (mode == Mode.OVERLAY) {
            var v by remember { mutableFloatStateOf(settings.overlayOpacity) }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.bg).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("$topLabel ${(v * 100).roundToInt()}%", Modifier.width(130.dp), color = C.ink, fontWeight = FontWeight.SemiBold)
                Slider(
                    value = v, onValueChange = { v = it }, valueRange = 0f..1f,
                    onValueChangeFinished = { Repo.updateSettings { it.copy(overlayOpacity = v) } },
                    colors = SliderDefaults.colors(thumbColor = C.accent, activeTrackColor = C.accent),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            Pill("More", onMore, kind = "dark")
            Pill(if (pinMode) "Cancel pin" else "Pin", onPin, kind = if (pinMode) "primary" else "dark")
            Pill("Pin here", onPinHere, kind = "dark")
            Pill(if (recording) "Stop track" else "Record", {
                if (recording) TrackService.stop(ctx)
                else if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                else TrackService.start(ctx)
            }, kind = if (recording) "danger" else "dark")
            Pill(if (follow) "Following" else "Locate", onLocate, kind = if (follow) "primary" else "dark")
        }
        fix?.accuracy?.let {
            Toast("GPS ±${Mercator.formatDistance(it.toDouble(), settings.metric)}", Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp))
        }
        Spacer(Modifier.height(2.dp))
    }
}
