package com.directrouter.app

import android.app.Application
import com.directrouter.app.data.SettingsRepository

class DirectRouterApp : Application() {
    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(applicationContext)
    }
}
