package dev.ide.android.workbench.runtime

import android.content.Context
import dev.ide.android.AndroidIde
import dev.ide.workbench.runtime.RuntimePreferences
import java.util.Properties

/** Same file and keys as the existing Settings backend; reads remain fresh across app processes. */
internal object AndroidRuntimeSettings {
    fun read(context: Context): RuntimePreferences {
        val file = AndroidIde.appHomeDir(context.applicationContext).resolve("prefs.properties")
        val values = Properties()
        if (file.exists()) file.inputStream().use { values.load(it) }
        return RuntimePreferences(values::getProperty)
    }
}
