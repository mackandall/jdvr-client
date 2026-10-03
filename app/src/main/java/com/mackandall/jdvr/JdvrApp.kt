package com.mackandall.jdvr

import android.app.Application
import com.mackandall.jdvr.data.Jdvr
import timber.log.Timber

class JdvrApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        Jdvr.init(this)
    }
}
