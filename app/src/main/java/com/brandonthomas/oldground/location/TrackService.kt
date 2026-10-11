package com.brandonthomas.oldground.location

import android.app.Service
import android.content.Intent
import android.os.IBinder

class TrackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
