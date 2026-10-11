package com.brandonthomas.oldground.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.view.Gravity
import com.brandonthomas.oldground.data.Layer
import com.brandonthomas.oldground.geo.LngLat
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.FeatureCollection

/** What a map shows underneath everything else. */
sealed class Base {
    /** A real-world map: Streets, Topo, Satellite or LiDAR. */
    data class Geo(val layer: Layer, val lidarKey: String = "") : Base()
    /** The old map scan in its own flat frame (tiles built on the phone). */
    data class Scan(val raster: Sources.Raster) : Base()
}

/** The data drawn on top of a map, in drawing order. */
enum class Data { TRACKS, MESH, ANCHORS, PINS, GHOST, GPS }

data class Cam(val center: LngLat, val zoom: Double, val bearing: Double)

/**
 * One MapLibre map plus everything the app draws on it. Holds the wanted state (base,
 * overlay, data) and re-applies it whenever the style reloads, so callers can set things
 * at any time.
 */
class OgMap(context: Context, texture: Boolean = false, val interactive: Boolean = true) {
    val view: MapView
    var map: MapLibreMap? = null
        private set
    private var style: Style? = null

    private var base: Base? = null
    private var baseUrlOrJson: Pair<String?, String?>? = null
    private var overlay: Sources.Raster? = null
    private var overlayOpacity = 1f
    private val data = HashMap<Data, FeatureCollection>()
    private var pendingCam: Cam? = null

    var onMove: ((Cam, Boolean) -> Unit)? = null
    var onIdle: ((Cam, Boolean) -> Unit)? = null
    var onTap: ((LngLat, String?) -> Unit)? = null
    var onReady: ((OgMap) -> Unit)? = null
    var rotateEnabled = true
        set(v) { field = v; map?.uiSettings?.isRotateGesturesEnabled = v && interactive }
    var compassTopPx = 0
        set(v) { field = v; applyCompass() }

    private var userGesture = false

    init {
        val opts = MapLibreMapOptions.createFromAttributes(context)
            .textureMode(texture)
            .logoEnabled(false)
            .attributionEnabled(true)
            .compassEnabled(interactive)
        view = MapView(context, opts)
        view.onCreate(null)
        view.getMapAsync { m ->
            map = m
            m.uiSettings.isTiltGesturesEnabled = false
            if (!interactive) m.uiSettings.setAllGesturesEnabled(false)
            m.uiSettings.isRotateGesturesEnabled = rotateEnabled && interactive
            m.uiSettings.attributionGravity = Gravity.BOTTOM or Gravity.START
            applyCompass()
            m.addOnCameraMoveStartedListener { reason -> userGesture = reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE }
            m.addOnCameraMoveListener { onMove?.invoke(cam(), userGesture) }
            m.addOnCameraIdleListener {
                onIdle?.invoke(cam(), userGesture)
                userGesture = false
            }
            m.addOnMapClickListener { ll ->
                val p = m.projection.toScreenLocation(ll)
                val hit = runCatching {
                    m.queryRenderedFeatures(RectF(p.x - 24, p.y - 24, p.x + 24, p.y + 24), "pins-circle", "anchors-circle")
                        .firstOrNull()?.getStringProperty("id")
                }.getOrNull()
                onTap?.invoke(LngLat(ll.longitude, ll.latitude), hit)
                onTap != null
            }
            pendingCam?.let { setCamera(it.center, it.zoom, it.bearing) }
            pendingCam = null
            loadBase()
            onReady?.invoke(this)
        }
    }

    private fun applyCompass() {
        val m = map ?: return
        val d = view.resources.displayMetrics.density
        m.uiSettings.compassGravity = Gravity.TOP or Gravity.END
        m.uiSettings.setCompassMargins(0, compassTopPx + (8 * d).toInt(), (12 * d).toInt(), 0)
    }

    fun cam(): Cam {
        val c = map?.cameraPosition ?: return pendingCam ?: Cam(LngLat(0.0, 0.0), 1.0, 0.0)
        val t = c.target ?: LatLng(0.0, 0.0)
        return Cam(LngLat(t.longitude, t.latitude), c.zoom, c.bearing)
    }

    fun setCamera(center: LngLat, zoom: Double? = null, bearing: Double? = null) {
        val m = map
        if (m == null) {
            val p = pendingCam
            pendingCam = Cam(center, zoom ?: p?.zoom ?: 15.0, bearing ?: p?.bearing ?: 0.0)
            return
        }
        val b = CameraPosition.Builder().target(LatLng(center.lat, center.lng))
        zoom?.let { b.zoom(it) }
        bearing?.let { b.bearing(it) }
        m.cameraPosition = b.build()
    }

    fun visibleBounds(): DoubleArray? {
        val b = map?.projection?.visibleRegion?.latLngBounds ?: return null
        return doubleArrayOf(b.longitudeWest, b.latitudeSouth, b.longitudeEast, b.latitudeNorth)
    }

    // ---------- base ----------

    fun setBase(b: Base, settings: com.brandonthomas.oldground.data.Settings) {
        val want: Pair<String?, String?> = when (b) {
            is Base.Geo -> when (b.layer) {
                Layer.STREETS -> Sources.STREETS_STYLE to null
                Layer.LIDAR -> null to Sources.rasterStyleJson(Sources.raster(Layer.LIDAR, settings, null), withAerialUnder = true)
                Layer.TOPO, Layer.AERIAL -> null to Sources.rasterStyleJson(Sources.raster(b.layer, settings, null))
                Layer.OLDMAP -> null to Sources.rasterStyleJson(null)
            }
            is Base.Scan -> null to Sources.rasterStyleJson(b.raster, background = "#ddd8c8")
        }
        base = b
        if (want == baseUrlOrJson) return
        baseUrlOrJson = want
        loadBase()
    }

    private fun loadBase() {
        val m = map ?: return
        val (url, json) = baseUrlOrJson ?: return
        val builder = if (url != null) Style.Builder().fromUri(url) else Style.Builder().fromJson(json!!)
        style = null
        m.setStyle(builder) { s ->
            style = s
            s.addImage("og-arrow", arrowBitmap())
            applyOverlay(force = true)
            for (d in Data.entries) addDataLayer(s, d)
        }
    }

    // ---------- overlay raster ----------

    fun setOverlay(r: Sources.Raster?, opacity: Float = 1f) {
        val changed = r?.id != overlay?.id
        overlay = r
        overlayOpacity = opacity
        applyOverlay(force = changed)
    }

    private fun applyOverlay(force: Boolean) {
        val s = style ?: return
        if (force) {
            s.getLayer("ov")?.let { s.removeLayer(it) }
            s.getSource("ov")?.let { s.removeSource(it) }
            val r = overlay ?: return
            val ts = TileSet("2.2.0", r.url).apply {
                minZoom = r.minZoom
                maxZoom = r.maxZoom
                r.bounds?.let { setBounds(it[0], it[1], it[2], it[3]) }
                if (r.attribution.isNotEmpty()) attribution = r.attribution
            }
            s.addSource(RasterSource("ov", ts, 256))
            val layer = RasterLayer("ov", "ov").withProperties(
                PropertyFactory.rasterOpacity(overlayOpacity),
                PropertyFactory.rasterFadeDuration(0f),
            )
            // Below the app's own data (tracks, pins…), above the base map.
            val firstData = s.layers.firstOrNull { it.id.startsWith("og-") }
            if (firstData != null) s.addLayerBelow(layer, firstData.id) else s.addLayer(layer)
        } else {
            (s.getLayer("ov") as? RasterLayer)?.setProperties(PropertyFactory.rasterOpacity(overlayOpacity))
        }
    }

    // ---------- data layers ----------

    fun setData(d: Data, fc: FeatureCollection) {
        data[d] = fc
        (style?.getSource("og-src-${d.name}") as? GeoJsonSource)?.setGeoJson(fc)
    }

    private fun addDataLayer(s: Style, d: Data) {
        val src = GeoJsonSource("og-src-${d.name}", data[d] ?: FeatureCollection.fromFeatures(emptyList()))
        s.addSource(src)
        val sid = src.id
        val get = { k: String -> Expression.get(k) }
        when (d) {
            Data.TRACKS -> s.addLayer(
                LineLayer("og-tracks", sid).withProperties(
                    PropertyFactory.lineColor("#ff6d00"), PropertyFactory.lineWidth(3f), PropertyFactory.lineOpacity(0.85f),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND), PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                ),
            )
            Data.MESH -> s.addLayer(
                LineLayer("og-mesh", sid).withProperties(
                    PropertyFactory.lineColor(Expression.toColor(get("color"))), PropertyFactory.lineWidth(Expression.toNumber(get("w"))),
                ),
            )
            Data.ANCHORS -> s.addLayer(
                CircleLayer("anchors-circle", sid).withProperties(
                    PropertyFactory.circleRadius(Expression.toNumber(get("r"))),
                    PropertyFactory.circleColor(Expression.toColor(get("color"))),
                    PropertyFactory.circleStrokeColor("#ffffff"), PropertyFactory.circleStrokeWidth(2f),
                ),
            )
            Data.PINS -> {
                s.addLayer(
                    CircleLayer("pins-circle", sid).withProperties(
                        PropertyFactory.circleRadius(8f), PropertyFactory.circleColor(Expression.toColor(get("color"))),
                        PropertyFactory.circleStrokeColor("#ffffff"), PropertyFactory.circleStrokeWidth(2.5f),
                    ),
                )
                s.addLayer(
                    SymbolLayer("og-pins-label", sid).withProperties(
                        PropertyFactory.textField(get("name")), PropertyFactory.textFont(arrayOf(Sources.FONT)),
                        PropertyFactory.textSize(13f), PropertyFactory.textOffset(arrayOf(0f, 1.3f)),
                        PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP), PropertyFactory.textColor("#1f2622"),
                        PropertyFactory.textHaloColor("#ffffff"), PropertyFactory.textHaloWidth(1.6f), PropertyFactory.textOptional(true),
                    ),
                )
            }
            Data.GHOST -> {
                s.addLayer(
                    CircleLayer("og-ghost-halo", sid).withProperties(
                        PropertyFactory.circleRadius(15f), PropertyFactory.circleColor("rgba(0,0,0,0)"),
                        PropertyFactory.circleStrokeColor("#ffffff"), PropertyFactory.circleStrokeWidth(5f),
                    ),
                )
                s.addLayer(
                    CircleLayer("og-ghost", sid).withProperties(
                        PropertyFactory.circleRadius(15f), PropertyFactory.circleColor("rgba(0,0,0,0)"),
                        PropertyFactory.circleStrokeColor(Expression.toColor(get("color"))), PropertyFactory.circleStrokeWidth(2.5f),
                    ),
                )
            }
            Data.GPS -> {
                s.addLayer(
                    CircleLayer("og-gps-halo", sid).withProperties(
                        PropertyFactory.circleRadius(22f), PropertyFactory.circleColor("rgba(26,115,232,0.22)"),
                    ),
                )
                s.addLayer(
                    SymbolLayer("og-gps-arrow", sid).withProperties(
                        PropertyFactory.iconImage("og-arrow"), PropertyFactory.iconRotate(Expression.toNumber(get("heading"))),
                        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                        PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
                        PropertyFactory.iconOpacity(Expression.toNumber(get("hasHeading"))),
                    ),
                )
                s.addLayer(
                    CircleLayer("og-gps", sid).withProperties(
                        PropertyFactory.circleRadius(7f), PropertyFactory.circleColor("#1a73e8"),
                        PropertyFactory.circleStrokeColor("#ffffff"), PropertyFactory.circleStrokeWidth(3f),
                    ),
                )
            }
        }
    }

    private fun arrowBitmap(): Bitmap {
        val d = view.resources.displayMetrics.density
        val size = (64 * d).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Path().apply {
            moveTo(size / 2f, size * 0.02f)
            lineTo(size / 2f + 10 * d, size / 2f - 8 * d)
            lineTo(size / 2f - 10 * d, size / 2f - 8 * d)
            close()
        }
        c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(26, 115, 232) })
        return bmp
    }

    // ---------- lifecycle ----------

    fun onStart() = view.onStart()
    fun onResume() = view.onResume()
    fun onPause() = view.onPause()
    fun onStop() = view.onStop()
    fun onDestroy() {
        onMove = null; onIdle = null; onTap = null; onReady = null
        view.onDestroy()
    }
    fun onLowMemory() = view.onLowMemory()

    fun screenPoint(geo: LngLat): PointF? = map?.projection?.toScreenLocation(LatLng(geo.lat, geo.lng))
}
