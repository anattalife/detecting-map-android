package com.brandonthomas.oldground.map

import com.brandonthomas.oldground.data.Layer
import com.brandonthomas.oldground.data.LidarStyle
import com.brandonthomas.oldground.data.OldMap
import com.brandonthomas.oldground.data.Pyramid
import com.brandonthomas.oldground.data.Repo
import com.brandonthomas.oldground.data.Settings
import com.brandonthomas.oldground.geo.Mercator
import com.brandonthomas.oldground.geo.XY
import java.io.File
import java.net.URLEncoder

/**
 * Free map sources only:
 *  - Streets: OpenFreeMap (OpenStreetMap data, no key, commercial use allowed)
 *  - Topo / Satellite: USGS The National Map (US government, no key)
 *  - LiDAR: USGS 3DEP bare-earth elevation, shaded by the USGS server
 */
object Sources {
    const val STREETS_STYLE = "https://tiles.openfreemap.org/styles/liberty"
    const val GLYPHS = "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf"
    const val FONT = "Noto Sans Regular"
    private const val USGS = "https://basemap.nationalmap.gov/arcgis/rest/services"
    const val TOPO = "$USGS/USGSTopo/MapServer/tile/{z}/{y}/{x}"
    const val AERIAL = "$USGS/USGSImageryOnly/MapServer/tile/{z}/{y}/{x}"

    fun lidar(s: Settings): String {
        val rule = when (s.lidarStyle) {
            LidarStyle.STRETCH -> """{"rasterFunction":"Hillshade Gray-Stretch"}"""
            LidarStyle.MULTI -> """{"rasterFunction":"Hillshade Multidirectional"}"""
            LidarStyle.SLOPE -> """{"rasterFunction":"Slope Map"}"""
            // Moving the sun makes old roads and walls that run parallel to the light appear.
            LidarStyle.SUN -> """{"rasterFunction":"Hillshade","rasterFunctionArguments":{"Azimuth":${s.lidarAzimuth % 360},"Altitude":35,"ZFactor":1},"variableName":"DEM"}"""
        }
        return "https://elevation.nationalmap.gov/arcgis/rest/services/3DEPElevation/ImageServer/exportImage" +
            "?bbox={bbox-epsg-3857}&bboxSR=3857&imageSR=3857&size=256,256&format=png&f=image" +
            "&renderingRule=" + URLEncoder.encode(rule, "UTF-8").replace("+", "%20")
    }

    /** A raster tile layer to draw over a base map. */
    data class Raster(val id: String, val url: String, val maxZoom: Float, val minZoom: Float = 0f, val bounds: FloatArray? = null, val attribution: String = "")

    /** A tile layer for anything except Streets (which is a full vector style). */
    fun raster(layer: Layer, s: Settings, map: OldMap?): Raster? = when (layer) {
        Layer.AERIAL -> Raster("aerial", AERIAL, 16f, attribution = "USGS The National Map")
        Layer.TOPO -> Raster("topo", TOPO, 16f, attribution = "USGS The National Map")
        Layer.LIDAR -> Raster("lidar-${s.lidarStyle}-${s.lidarAzimuth}", lidar(s), 18f, attribution = "USGS 3DEP")
        Layer.OLDMAP -> map?.warp?.takeIf { it.ready }?.let { tiles(map.id, it, "old-${it.dir}-${it.zDone}") }
        Layer.STREETS -> null
    }

    /** Tiles built on the phone (old map overlay or scan). */
    fun tiles(mapId: String, p: Pyramid, id: String): Raster {
        val dir = File(Repo.mapDir(mapId), p.dir).absolutePath
        val sw = Mercator.toLngLat(XY(p.bounds[0], p.bounds[1]))
        val ne = Mercator.toLngLat(XY(p.bounds[2], p.bounds[3]))
        return Raster(
            id, "file://$dir/{z}/{x}/{y}.webp", maxZoom = p.zDone.toFloat(), minZoom = p.zMin.toFloat(),
            bounds = floatArrayOf(sw.lng.toFloat(), sw.lat.toFloat(), ne.lng.toFloat(), ne.lat.toFloat()),
        )
    }

    /** A complete style with one raster layer (used for Topo / Satellite / LiDAR bases and offline downloads). */
    fun rasterStyleJson(r: Raster?, withAerialUnder: Boolean = false, background: String = "#e9e6dc"): String {
        val src = StringBuilder()
        val layers = StringBuilder("""{"id":"bg","type":"background","paint":{"background-color":"$background"}}""")
        fun add(id: String, url: String, max: Float) {
            if (src.isNotEmpty()) src.append(',')
            src.append(""""$id":{"type":"raster","tiles":["$url"],"tileSize":256,"maxzoom":${max.toInt()}}""")
            layers.append(""",{"id":"$id","type":"raster","source":"$id"}""")
        }
        if (withAerialUnder) add("aerial-under", AERIAL, 16f)
        if (r != null) add(r.id, r.url, r.maxZoom)
        return """{"version":8,"glyphs":"$GLYPHS","sources":{$src},"layers":[$layers]}"""
    }
}
