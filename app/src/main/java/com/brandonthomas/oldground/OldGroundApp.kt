package com.brandonthomas.oldground

import android.app.Application
import com.brandonthomas.oldground.data.Repo
import com.brandonthomas.oldground.map.TileJobs
import org.maplibre.android.MapLibre

class OldGroundApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        MapLibre.getInstance(this)
        Repo.init(this)
        // Finish any map tiles that were still being built when the app last closed.
        TileJobs.resumeAll(this)
    }

    companion object {
        lateinit var instance: OldGroundApp
            private set
    }
}
