package com.mackandall.jdvr

import android.app.Application
import android.util.Log
import com.mackandall.jdvr.data.Jdvr
import timber.log.Timber

class JdvrApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Timber.plant(if (BuildConfig.DEBUG) Timber.DebugTree() else ProblemsTree)
        Jdvr.init(this)
    }
}

/** The release build's log: warnings and errors only, under "JDVR" (`adb logcat -s JDVR`). */
private object ProblemsTree : Timber.Tree() {
    override fun isLoggable(
        tag: String?,
        priority: Int,
    ) = priority >= Log.WARN

    override fun log(
        priority: Int,
        tag: String?,
        message: String,
        t: Throwable?,
    ) {
        Log.println(priority, "JDVR", message)
    }
}
