package io.githuh.onehandedmode

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class ModuleApplication : Application(), XposedServiceHelper.OnServiceListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    var service by mutableStateOf<XposedService?>(null)
        private set

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        mainHandler.post {
            HandySettings.migrate(service.getRemotePreferences(HandySettings.GROUP))
            this.service = service
        }
    }

    override fun onServiceDied(service: XposedService) {
        mainHandler.post { this.service = null }
    }
}
