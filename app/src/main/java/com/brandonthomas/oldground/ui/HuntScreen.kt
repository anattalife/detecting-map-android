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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import com.brandonthomas.oldground.data.Photos
import com.brandonthomas.oldground.data.Pin
import com.brandonthomas.oldground.data.SITE_ACCESS
import com.brandonthomas.oldground.data.Site
import com.brandonthomas.oldground.data.Sites
import com.brandonthomas.oldground.Screen
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
fun HuntScreen(
    nav: Nav, mapId: String?, pinId: String?, startMode: String? = null, debug: String? = null,
    /** "new" to outline a new site, or a site's id to redraw its outline. */
    draw: String? = null,
    /** Open on this site. */
    siteId: String? = null,
    /** Start recording a track as soon as the map opens (Start a visit). */
    startVisit: Boolean = false,
) {
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
    var cardPin by remember { mutableStateOf<String?>(null) }
    var guideTo by remember { mutableStateOf<String?>(null) }
    // Outlining a site: "new" or the id of the site being redrawn.
    val redraw = draw?.takeIf { it != "new" }?.let { id -> data.sites.firstOrNull { it.id == id } }
    var drawing by remember { mutableStateOf(draw) }
    val corners = remember { mutableStateListOf<LngLat>().apply { redraw?.let { addAll(it.ring) } } }
    var siteName by remember { mutableStateOf(redraw?.name ?: "") }
    var siteAccess by remember { mutableStateOf(redraw?.access ?: SITE_ACCESS.first()) }
    var savedNote by remember { mutableStateOf<String?>(null) }
    // Used by the automated screenshots to open a sheet or dialog.
    LaunchedEffect(debug) {
        kotlinx.coroutines.delay(1500)
        when (debug) {
            null -> if (pinId != null) cardPin = pinId
            "more" -> moreOpen = true
            "pin" -> Repo.current.pins.firstOrNull { it.id == pinId }?.let { p -> draft = PinDraft.of(p) }
            "card" -> cardPin = pinId
            "new" -> Repo.current.pins.firstOrNull { it.id == pinId }?.let { p -> draft = PinDraft(p.geo, "", com.brandonthomas.oldground.data.PinKind.FIND, "", null) }

        }
    }
    LaunchedEffect(startVisit) {
        if (startVisit) {
            if (Repo.current.recording == null) TrackService.start(ctx)
            follow = true
        }
    }
    LaunchedEffect(savedNote) {
        if (savedNote != null) { kotlinx.coroutines.delay(4000); savedNote = null }
    }
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
    val focusSite = (siteId ?: redraw?.id)?.let { id -> data.sites.firstOrNull { it.id == id } }
    val conf = LocalConfiguration.current
    val initial = remember {
        val siteBounds = focusSite?.let { Sites.bounds(it.ring) }
        if (focus == null && siteBounds != null) {
            Cam(Sites.center(focusSite.ring)!!, Sites.fitZoom(siteBounds, conf.screenWidthDp.toFloat(), conf.screenHeightDp * 0.55f), 0.0)
        } else {
            val c = focus?.geo ?: map?.anchors?.takeIf { it.isNotEmpty() }?.let { a ->
                LngLat(a.map { it.lng }.average(), a.map { it.lat }.average())
            } ?: LocationHub.fix.value?.geo ?: LngLat(-82.99, 39.96)
            Cam(c, if (draw == "new") 17.5 else 16.0, 0.0)
        }
    }
    fun startCam() = camMemory[0] ?: initial

    // Maps currently on screen, with how to place real-world positions on each.
    val live = remember { mutableStateMapOf<String, Pair<OgMap, ScanFrame?>>() }

    // Screenshots: four corners as if tapped, on open ground next to the seeded sites.
    LaunchedEffect(debug) {
        if (debug != "drawdemo") return@LaunchedEffect
        kotlinx.coroutines.delay(1500)
        val c = LngLat(-82.99080, 39.96060)
        for ((_, p) in live) p.first.setCamera(c, 16.7)
        corners.clear()
        corners.addAll(listOf(LngLat(c.lng - 0.0010, c.lat + 0.0006), LngLat(c.lng + 0.0008, c.lat + 0.0008), LngLat(c.lng + 0.0010, c.lat - 0.0005), LngLat(c.lng - 0.0007, c.lat - 0.0007)))
        siteName = "Hendricks pasture"
    }

    // ---- shared map behaviour ----
    fun onTapAt(scan: ScanFrame?): (LngLat, String?, Map<String, String>?) -> Unit = { at, hit, land ->
        val geo = if (scan != null) scan.toGeo(at) else at
        when {
            drawing != null -> if (geo != null) corners.add(geo)
            pinMode && geo != null -> {
                pinMode = false
                draft = PinDraft(geo, "", com.brandonthomas.oldground.data.PinKind.FIND, "", null)
            }
            hit != null -> cardPin = hit
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
                        Modifier.statusBarsPadding().padding(top = 64.dp, start = 12.dp, end = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        LayerTag(top.label, { picking = Slot.OVERLAY_TOP })
                        Text("over", color = C.ink, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterVertically).clip(RoundedCornerShape(50)).background(Color(0xD9FFFFFF)).padding(horizontal = 8.dp, vertical = 4.dp))
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
                                Modifier.size(44.dp).shadow(4.dp, CircleShape).clip(CircleShape).background(C.panel),
                                contentAlignment = Alignment.Center,
                            ) { Text("‹ ›", color = C.accent, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp) }
                        }
                    }
                    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 64.dp, start = 12.dp, end = 12.dp)) {
                        LayerTag(left.label, { picking = Slot.SWIPE_LEFT }, Modifier.align(Alignment.TopStart))
                        LayerTag(right.label, { picking = Slot.SWIPE_RIGHT }, Modifier.align(Alignment.TopEnd))
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
            MapDataSync(
                m, scan, data.pins, trackPts, fix, settings.publicLand, guideTo?.let { id -> data.pins.firstOrNull { it.id == id } },
                // While redrawing, the old outline is hidden so only the new one shows.
                if (drawing != null && redraw != null) data.sites.filter { it.id != redraw.id } else data.sites,
                if (drawing != null) corners.toList() else emptyList(),
            )
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
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (drawing != null) {
                RoundButton({ if (draw != null) nav.back() else { drawing = null; corners.clear() } }, "Cancel") { GlyphIcon(Glyph.CLOSE, C.ink) }
                Text(
                    if (corners.size < 3) "Tap each corner of the field or yard" else "Keep tapping corners, or save",
                    Modifier.weight(1f).card(radius = 999).background(C.panel).padding(horizontal = 16.dp, vertical = 13.dp),
                    fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = C.ink, maxLines = 1,
                )
            } else {
                RoundButton({ nav.back() }, "Back") { GlyphIcon(Glyph.BACK, C.ink) }
                Segmented(mode, Mode.entries.map { it to it.label }, { mode = it }, Modifier.weight(1f), dark = true)
            }
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
            savedNote?.let { Toast(it, color = C.accent) }
            if (drawing == null && !pinMode) fix?.let { f -> Sites.siteAt(data.sites, f.geo) }?.let { here ->
                Text(
                    "In ${here.name}",
                    Modifier.card(radius = 999).background(C.panel).clickable { nav.go(Screen.Site(here.id)) }.padding(horizontal = 14.dp, vertical = 7.dp),
                    color = C.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                )
            }
            if (pinMode) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Toast("Tap the map to place a pin", color = C.accent)
                Pill("Pin my spot", {
                    pinMode = false
                    val f = LocationHub.fix.value
                    if (f == null) message = "No GPS fix yet" to "Wait for the blue dot, or tap the map where you want the pin."
                    else draft = PinDraft(f.geo, "", com.brandonthomas.oldground.data.PinKind.FIND, "", null)
                })
            }
        }

        // ---- bottom controls ----
        val recordingSince = data.recording?.let { r -> data.tracks.firstOrNull { it.id == r }?.startedAt }
        if (drawing != null) DrawPanel(
            corners.size, Sites.areaM2(corners), settings.metric, siteName, { siteName = it }, siteAccess, { siteAccess = it },
            editing = redraw != null,
            onUndo = { if (corners.isNotEmpty()) corners.removeAt(corners.lastIndex) },
            onSave = {
                val outline = corners.map { listOf(it.lng, it.lat) }
                val name = siteName.trim().ifBlank { "Site ${data.sites.size + 1}" }
                if (redraw != null) {
                    Repo.updateSite(redraw.id) { it.copy(name = name, access = siteAccess, outline = outline) }
                    nav.back()
                } else {
                    Repo.addSite(Site(Repo.newId(), name, siteAccess, outline, createdAt = System.currentTimeMillis()))
                    drawing = null
                    corners.clear()
                    savedNote = "$name saved. Finds inside it go there."
                    if (data.recording == null) TrackService.start(ctx)
                    follow = true
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) else BottomPanel(
            mode, settings, follow, pinMode, recordingSince, fix, data.pins,
            topLabel = slot(Slot.OVERLAY_TOP).label,
            onLayers = { moreOpen = true },
            onPin = { pinMode = !pinMode },
            onOpenPin = { p -> cardPin = p.id },
            guided = guideTo?.let { id -> data.pins.firstOrNull { it.id == id } },
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
                        if (tiles > 20000) message = "Area too large" to "Zoom in closer to the area you'll explore and try again."
                        else confirmDownload = "${layers.joinToString(" and ") { it.label }}, about $tiles tiles. Old maps are always available offline." to {
                            download = 0
                            val name = "${map?.name ?: "Saved area"} ${java.text.DateFormat.getDateInstance().format(java.util.Date())}"
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
        PinDialog(d, fix?.let { Mercator.distanceMeters(it.geo, d.geo) }, settings.metric, settings.lastDetector, Sites.siteAt(data.sites, d.geo)?.name,
            onCancel = { draft = null },
            onDelete = d.editing?.let { id -> { Repo.deletePin(id); if (guideTo == id) guideTo = null; draft = null } },
        ) { s ->
            if (s.editing != null) Repo.updatePin(s.editing) {
                it.copy(
                    name = s.name.ifBlank { it.name }, kind = s.kind, note = s.note, photos = s.photos, category = s.category,
                    material = s.material, depthIn = s.depthIn, vdi = s.vdi, detector = s.detector, rating = s.rating,
                )
            } else Repo.addPin(
                Pin(
                    Repo.newId(), s.name.ifBlank { if (s.kind == com.brandonthomas.oldground.data.PinKind.FIND) s.category ?: "Find" else "Pin" },
                    s.kind, s.geo.lng, s.geo.lat, s.note, mapId, System.currentTimeMillis(), s.photos, s.category, s.material,
                    s.depthIn, s.vdi, s.detector, s.rating,
                ),
            )
            if (!s.detector.isNullOrBlank() && s.detector != settings.lastDetector) Repo.updateSettings { it.copy(lastDetector = s.detector) }
            draft = null
        }
    }
    cardPin?.let { id -> data.pins.firstOrNull { it.id == id } }?.let { p ->
        FindCard(
            p, fix, settings.metric, p.mapId?.let { mid -> data.maps.firstOrNull { it.id == mid }?.name }, guideTo == p.id,
            onGuide = { guideTo = if (guideTo == p.id) null else p.id; cardPin = null },
            onEdit = { cardPin = null; draft = PinDraft.of(p) },
            onDismiss = { cardPin = null },
        )
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
private fun MapDataSync(
    m: OgMap, scan: ScanFrame?, pins: List<Pin>, tracks: Map<String, List<TrackPt>>, fix: Fix?, publicLand: Boolean, guided: Pin?,
    sites: List<Site> = emptyList(), drawCorners: List<LngLat> = emptyList(),
) {
    val ctx = LocalContext.current
    val markerPx = with(LocalDensity.current) { 40.dp.roundToPx() }
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
    LaunchedEffect(m, scan, sites) { m.setData(Data.SITES, Features.sites(sites, placeLine)) }
    LaunchedEffect(m, scan, drawCorners) { m.setData(Data.DRAFT, Features.draft(drawCorners, placeLine)) }
    LaunchedEffect(m, scan, pins) {
        // Round photo markers first, so photo pins never show without their picture.
        val firsts = pins.mapNotNull { it.photos.firstOrNull() }.distinct().filter { !m.hasImage(Features.photoImageId(it)) }
        if (firsts.isNotEmpty()) {
            val imgs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                firsts.mapNotNull { n -> Photos.mapMarker(ctx, n, markerPx)?.let { Features.photoImageId(n) to it } }.toMap()
            }
            m.addImages(imgs)
        }
        m.setData(Data.PINS, Features.pins(pins.map { p -> if (p.photos.isNotEmpty() && !m.hasImage(Features.photoImageId(p.photos.first()))) p.copy(photos = emptyList()) else p }, place))
    }
    LaunchedEffect(m, scan, fix, guided) {
        val from = fix?.geo?.let(placeLine)
        val to = guided?.geo?.let(placeLine)
        m.setData(Data.GUIDE, Features.guide(from, to))
    }
    LaunchedEffect(m, scan, tracks) { m.setData(Data.TRACKS, Features.tracks(tracks.values, placeLine)) }
    LaunchedEffect(m, scan, fix) {
        val north = if (scan != null && fix != null) scan.northAngle(fix.geo) else 0.0
        m.setData(Data.GPS, Features.gps(fix, place, north))
    }
}

@Composable
private fun BottomPanel(
    mode: Mode, settings: Settings, follow: Boolean, pinMode: Boolean, recordingSince: Long?, fix: Fix?, pins: List<Pin>, topLabel: String,
    onLayers: () -> Unit, onPin: () -> Unit, onOpenPin: (Pin) -> Unit, onLocate: () -> Unit, modifier: Modifier, guided: Pin? = null,
) {
    val ctx = LocalContext.current
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { TrackService.start(ctx) }
    var collapsed by rememberSaveable { mutableStateOf(false) }
    // Elapsed recording time, refreshed every 20 seconds.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(recordingSince) {
        while (recordingSince != null) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(20_000)
        }
    }
    val elapsed = recordingSince?.let {
        val mins = ((now - it) / 60_000).coerceAtLeast(0)
        "${mins / 60}:${"%02d".format(mins % 60)}"
    }
    val nearest = guided ?: fix?.let { f -> pins.minByOrNull { Mercator.distanceMeters(f.geo, it.geo) } }
    val shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    Column(
        modifier.fillMaxWidth()
            .shadow(16.dp, shape, ambientColor = Color(0x5514201D), spotColor = Color(0x5514201D))
            .clip(shape)
            .background(C.panel)
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Handle: tap or drag down to shrink the panel to just the buttons.
        Box(
            Modifier.fillMaxWidth().height(22.dp)
                .clickable(onClickLabel = if (collapsed) "Show more" else "Show less") { collapsed = !collapsed }
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        if (drag.y > 6) collapsed = true else if (drag.y < -6) collapsed = false
                    }
                },
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(width = 40.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFD5DBD9))) }

        if (!collapsed) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (nearest != null && fix != null) {
                    val d = Mercator.distanceMeters(fix.geo, nearest.geo)
                    val dir = Mercator.compass(Mercator.bearing(fix.geo, nearest.geo))
                    val photo = nearest.photos.firstOrNull()
                    if (photo != null) PhotoThumb(photo, Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).clickable { onOpenPin(nearest) }, px = 160)
                    else Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(C.soft).clickable { onOpenPin(nearest) }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(hexColor(nearest.kind.color)))
                    }
                    Column(Modifier.weight(1f).clickable { onOpenPin(nearest) }) {
                        Text(nearest.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = C.ink, maxLines = 1)
                        val what = if (nearest.kind == com.brandonthomas.oldground.data.PinKind.FIND)
                            listOfNotNull(nearest.material, nearest.depthIn?.let { depthLabel(it, settings.metric) }).joinToString(" · ").ifEmpty { nearest.kind.label }
                        else nearest.kind.label
                        Text(
                            (if (guided != null) "Guiding · " else "") + "${Mercator.formatDistance(d, settings.metric)} $dir · $what",
                            fontSize = 13.sp, color = if (guided != null) C.accent else C.muted, maxLines = 1,
                            fontWeight = if (guided != null) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                } else {
                    Column(Modifier.weight(1f)) {
                        Text(if (fix == null) "Finding your location…" else "No pins yet", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = C.ink)
                        Text("Tap Pin to mark a home site, a find or a LiDAR feature.", fontSize = 13.sp, color = C.muted, maxLines = 2)
                    }
                }
                fix?.accuracy?.let {
                    Text(
                        "GPS ±${Mercator.formatDistance(it.toDouble(), settings.metric)}",
                        Modifier.clip(RoundedCornerShape(50)).background(C.accentSoft).padding(horizontal = 10.dp, vertical = 5.dp),
                        color = C.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (mode == Mode.OVERLAY) {
                var v by remember { mutableFloatStateOf(settings.overlayOpacity) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$topLabel ${(v * 100).roundToInt()}%", Modifier.width(118.dp), color = C.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    SlimSlider(v, { v = it }, { Repo.updateSettings { it.copy(overlayOpacity = v) } }, Modifier.weight(1f))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolButton("Layers", Glyph.LAYERS, onLayers, Modifier.weight(1f))
            ToolButton(if (pinMode) "Cancel" else "Pin", Glyph.PIN, onPin, Modifier.weight(1f), kind = if (pinMode) "primary" else "soft")
            ToolButton(elapsed ?: "Record", if (elapsed != null) Glyph.STOP else Glyph.RECORD, {
                if (recordingSince != null) TrackService.stop(ctx)
                else if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                else TrackService.start(ctx)
            }, Modifier.weight(1f), kind = "danger")
            ToolButton(if (follow) "Following" else "Follow", Glyph.FOLLOW, onLocate, Modifier.weight(1f), kind = if (follow) "primary" else "soft")
        }
    }
}

/** The panel shown while outlining a site. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DrawPanel(
    cornerCount: Int, areaM2: Double, metric: Boolean,
    name: String, onName: (String) -> Unit, access: String, onAccess: (String) -> Unit,
    editing: Boolean, onUndo: () -> Unit, onSave: () -> Unit, modifier: Modifier,
) {
    val shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    val fieldColors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(unfocusedBorderColor = C.line, focusedBorderColor = C.accent)
    Column(
        modifier.fillMaxWidth()
            .shadow(16.dp, shape, ambientColor = Color(0x5514201D), spotColor = Color(0x5514201D))
            .clip(shape).background(C.panel).navigationBarsPadding().imePadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 40.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFD5DBD9)))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (editing) "Redraw outline" else "New site", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = C.ink)
            Text(
                if (cornerCount < 3) "$cornerCount of 3+ corners" else "$cornerCount corners · ${Sites.formatArea(areaM2, metric)}",
                Modifier.clip(RoundedCornerShape(50)).background(C.accentSoft).padding(horizontal = 10.dp, vertical = 5.dp),
                color = C.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            )
        }
        LabeledField("Name") {
            androidx.compose.material3.OutlinedTextField(
                name, onName, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = fieldColors,
                placeholder = { Text("e.g. Miller homestead") },
            )
        }
        LabeledField("Access") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (a in SITE_ACCESS) SoftChip(a, a == access) { onAccess(a) }
            }
        }
        Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelButton("Undo corner", C.soft, C.ink, cornerCount > 0, onUndo, Modifier.weight(1f))
            PanelButton(if (editing) "Save outline" else "Save and explore", C.accent, Color.White, cornerCount >= 3, onSave, Modifier.weight(1.4f))
        }
    }
}

@Composable
private fun PanelButton(label: String, bg: Color, fg: Color, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier.fillMaxHeight().clip(RoundedCornerShape(24.dp)).background(if (enabled) bg else bg.copy(alpha = 0.45f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) fg else fg.copy(alpha = 0.6f), fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
}
