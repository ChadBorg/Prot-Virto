package com.protvirto.capri

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** The Thor boots (or Capri was just updated): she comes back, unless you turned her off. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val config = runCatching { Config(context) }.getOrNull() ?: return
        val boot = intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        if (boot && !config.bool("startOnBoot", true)) return
        if (!boot && !config.bool("running", true)) return
        if (!Settings.canDrawOverlays(context)) return
        runCatching { CapriService.start(context) }
    }
}
