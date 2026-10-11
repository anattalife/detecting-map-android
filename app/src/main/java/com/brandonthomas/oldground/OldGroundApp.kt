package com.brandonthomas.oldground

import android.app.Application
import org.maplibre.android.MapLibre

class OldGroundApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        MapLibre.getInstance(this)
    }

    companion object {
        lateinit var instance: OldGroundApp
            private set
    }
}
