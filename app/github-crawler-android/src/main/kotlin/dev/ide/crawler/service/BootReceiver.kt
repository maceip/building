package dev.ide.crawler.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts the crawler after a reboot.
 *
 * The index is only worth anything if it keeps accumulating, so the service
 * comes back up with the phone.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i("CrawlerBoot", "reboot detected - restarting the crawler service")
        CrawlerService.start(context.applicationContext)
    }
}
