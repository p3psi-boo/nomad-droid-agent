package com.nomad.droid

import android.app.Application
import com.nomad.droid.root.RootManager
import com.nomad.droid.shizuku.ShizukuManager
import com.nomad.droid.termux.TermuxManager

class NomadDroidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RootManager.initialize(this)
        ShizukuManager.initialize(this)
        TermuxManager.initialize(this)
    }
}
