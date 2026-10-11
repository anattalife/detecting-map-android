package com.brandonthomas.oldground.map

import com.brandonthomas.oldground.data.Pin
import com.brandonthomas.oldground.data.TrackPt
import com.brandonthomas.oldground.geo.LngLat
import com.brandonthomas.oldground.location.Fix
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Builds map features. [place] turns a real-world position into the map's own frame:
 * the identity for real maps, or the old map's scan frame (null when off the sheet).
 */
object Features {
    val empty: FeatureCollection get() = FeatureCollection.fromFeatures(emptyList())

    private fun pt(g: LngLat) = Point.fromLngLat(g.lng, g.lat)

    fun pins(pins: List<Pin>, place: (LngLat) -> LngLat?): FeatureCollection =
        FeatureCollection.fromFeatures(pins.mapNotNull { p ->
            val g = place(p.geo) ?: return@mapNotNull null
            Feature.fromGeometry(pt(g)).apply {
                addStringProperty("id", p.id)
                addStringProperty("name", p.name)
                addStringProperty("color", p.kind.color)
            }
        })

    fun tracks(tracks: Collection<List<TrackPt>>, place: (LngLat) -> LngLat?): FeatureCollection =
        FeatureCollection.fromFeatures(tracks.mapNotNull { pts ->
            val line = pts.mapNotNull { place(LngLat(it.lng, it.lat))?.let(::pt) }
            if (line.size < 2) null else Feature.fromGeometry(LineString.fromLngLats(line))
        })

    fun gps(fix: Fix?, place: (LngLat) -> LngLat?, mapBearing: Double = 0.0): FeatureCollection {
        val g = fix?.let { place(it.geo) } ?: return empty
        return FeatureCollection.fromFeature(Feature.fromGeometry(pt(g)).apply {
            addNumberProperty("heading", (fix.heading ?: 0f).toDouble() + mapBearing)
            addNumberProperty("hasHeading", if (fix.heading != null) 1 else 0)
        })
    }

    fun ghost(g: LngLat?, color: String): FeatureCollection =
        if (g == null) empty else FeatureCollection.fromFeature(Feature.fromGeometry(pt(g)).apply { addStringProperty("color", color) })

    data class AnchorDot(val id: String, val at: LngLat, val color: String, val selected: Boolean)

    fun anchors(dots: List<AnchorDot>): FeatureCollection =
        FeatureCollection.fromFeatures(dots.map { a ->
            Feature.fromGeometry(pt(a.at)).apply {
                addStringProperty("id", a.id)
                addStringProperty("color", a.color)
                addNumberProperty("r", if (a.selected) 10 else 7)
            }
        })

    fun mesh(tris: List<Pair<List<LngLat>, Boolean>>): FeatureCollection =
        FeatureCollection.fromFeatures(tris.map { (corners, folded) ->
            Feature.fromGeometry(LineString.fromLngLats((corners + corners.first()).map(::pt))).apply {
                addStringProperty("color", if (folded) "#c0392b" else "rgba(30,90,200,0.75)")
                addNumberProperty("w", if (folded) 2.5 else 1.2)
            }
        })
}
