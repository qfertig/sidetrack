package com.sidetrack

import android.app.Application
import com.sidetrack.bridge.NativeBridge

class SidetrackApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NativeBridge.init()
        NativeBridge.setTmpDir(cacheDir.absolutePath)
    }
}
