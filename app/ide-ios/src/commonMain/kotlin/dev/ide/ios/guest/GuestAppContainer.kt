package dev.ide.ios.guest

import dev.ide.ios.IosFiles
import platform.Foundation.NSDictionary
import platform.Foundation.dictionaryWithContentsOfFile

data class GuestAppMetadata(
    val bundleId: String,
    val executableName: String,
    val displayName: String,
    val version: String,
    val appBundlePath: String,
    val executablePath: String,
)

data class GuestContainerPaths(
    val containerRoot: String,
    val bundleDir: String,
    val appPath: String,
    val dataDir: String,
    val documentsDir: String,
    val libraryDir: String,
    val cachesDir: String,
    val preferencesDir: String,
    val tmpDir: String,
)

/**
 * Manages isolated container directory layout for guest apps running inside the IDE.
 *
 * Implements standard iOS application container structure:
 * - Bundle/Application/<UUID>/<AppName>.app
 * - Data/Application/<UUID>/Documents
 * - Data/Application/<UUID>/Library/{Caches,Preferences}
 * - Data/Application/<UUID>/tmp
 */
object GuestAppContainer {

    fun liveContainerRoot(): String {
        val root = IosFiles.join(IosFiles.documentsDir(), "LiveContainer")
        if (!IosFiles.exists(root)) IosFiles.mkdirs(root)
        return root
    }

    fun prepareContainer(appId: String, appName: String): GuestContainerPaths {
        val safeAppId = IosFiles.sanitize(appId)
        val containerRoot = IosFiles.join(liveContainerRoot(), "Containers", safeAppId)
        val bundleDir = IosFiles.join(containerRoot, "Bundle")
        val appPath = IosFiles.join(bundleDir, "$appName.app")
        val dataDir = IosFiles.join(containerRoot, "Data")
        val documentsDir = IosFiles.join(dataDir, "Documents")
        val libraryDir = IosFiles.join(dataDir, "Library")
        val cachesDir = IosFiles.join(libraryDir, "Caches")
        val preferencesDir = IosFiles.join(libraryDir, "Preferences")
        val tmpDir = IosFiles.join(dataDir, "tmp")

        IosFiles.mkdirs(containerRoot)
        IosFiles.mkdirs(bundleDir)
        IosFiles.mkdirs(appPath)
        IosFiles.mkdirs(documentsDir)
        IosFiles.mkdirs(cachesDir)
        IosFiles.mkdirs(preferencesDir)
        IosFiles.mkdirs(tmpDir)

        return GuestContainerPaths(
            containerRoot = containerRoot,
            bundleDir = bundleDir,
            appPath = appPath,
            dataDir = dataDir,
            documentsDir = documentsDir,
            libraryDir = libraryDir,
            cachesDir = cachesDir,
            preferencesDir = preferencesDir,
            tmpDir = tmpDir,
        )
    }

    fun inspectApp(appPath: String): GuestAppMetadata? {
        val plistPath = IosFiles.join(appPath, "Info.plist")
        if (!IosFiles.exists(plistPath)) return null

        val dict = NSDictionary.dictionaryWithContentsOfFile(plistPath)
        val bundleId = (dict?.get("CFBundleIdentifier") as? String)
            ?: "guest.app.${IosFiles.nameOf(appPath).removeSuffix(".app")}"
        val executableName = (dict?.get("CFBundleExecutable") as? String)
            ?: IosFiles.nameOf(appPath).removeSuffix(".app")
        val displayName = (dict?.get("CFBundleDisplayName") as? String)
            ?: (dict?.get("CFBundleName") as? String)
            ?: executableName
        val version = (dict?.get("CFBundleShortVersionString") as? String)
            ?: (dict?.get("CFBundleVersion") as? String)
            ?: "1.0"

        val executablePath = IosFiles.join(appPath, executableName)

        return GuestAppMetadata(
            bundleId = bundleId,
            executableName = executableName,
            displayName = displayName,
            version = version,
            appBundlePath = appPath,
            executablePath = executablePath,
        )
    }
}
