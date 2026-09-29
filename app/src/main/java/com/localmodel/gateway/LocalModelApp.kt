package com.localmodel.gateway

import android.app.Application

class LocalModelApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        DiagLog.init(this)
    }
}
