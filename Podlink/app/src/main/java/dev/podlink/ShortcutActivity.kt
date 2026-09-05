package dev.podlink

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.podlink.service.PodsService

/** Invisible trampoline for app shortcuts and automation: forwards an action to the service and finishes. */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = when (intent.getStringExtra("action")) {
            "connect" -> PodsService.ACTION_CONNECT_NOW
            "popup" -> PodsService.ACTION_SHOW_POPUP
            else -> null
        }
        if (action != null) {
            PodsService.start(this)
            startService(Intent(this, PodsService::class.java).setAction(action))
        }
        finish()
    }
}
