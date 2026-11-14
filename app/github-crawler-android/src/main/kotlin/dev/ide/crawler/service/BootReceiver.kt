package dev.ide.crawler.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Restarts the crawler after a reboot.
 *
 * The index is only worth anything if it keeps accumulating, so the service
 * comes back up with the phone.
 *
 * Two things this must survive:
 *
 *  * Recent Android versions refuse a foreground-service start straight out
 *    of a boot broadcast, because the app is still in the background. The
 *    start is therefore deferred until the device has settled.
 *  * The same broadcast is re-delivered when the app is (re)installed, so
 *    this path runs at install time too - and a refused start must degrade
 *    to "not running", never to a crash.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i("CrawlerBoot", "boot broadcast received - starting the crawler shortly")

        // goAsync() returns null when the receiver is invoked outside a real
        // broadcast delivery (which is what a direct unit-style call does),
        // so the result must be treated as optional.
        val pending = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                CrawlerService.start(context.applicationContext)
            } catch (e: Exception) {
                Log.w("CrawlerBoot", "could not start the crawler after boot: ${e.message}")
            } finally {
                try {
                    pending?.finish()
                } catch (_: Exception) {
                    // The broadcast already expired.
                }
            }
        }, START_DELAY_MS)
    }

    companion object {
        /** Long enough for the boot broadcast restrictions to settle. */
        private const val START_DELAY_MS = 5_000L
    }
}
