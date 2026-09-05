package dev.podlink

import android.app.Application
import dev.podlink.service.Notifications
import dev.podlink.util.HiddenApi

class PodlinkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        HiddenApi.unlock()
    }
}
