package dev.ide.android

import android.app.Application
import android.os.Build

/** Makes Play-delivered feature assets/native libraries available after a cold start too. */
class BuildingApplication : com.google.android.play.core.splitcompat.SplitCompatApplication() {
    override fun onCreate() {
        super.onCreate()
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            packageName
        }
        if (!processName.contains(':')) {
            AndroidIde.prewarmBootstrap(this)
        }
    }
}
