package com.nomad.droid

import android.app.Application
import com.nomad.droid.shizuku.ShizukuManager
import com.nomad.droid.termux.TermuxManager

class NomadDroidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ShizukuManager.initialize(this)
        TermuxManager.initialize(this)
    }
}
