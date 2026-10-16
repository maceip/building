package dev.ide.core.discovery

/**
 * Extracts and classifies high-level runtime capabilities, security permissions,
 * and functional features of a repository.
 *
 * Answers the questions GitHub never answers:
 * - Does this run completely offline without cloud servers?
 * - What hardware and OS capabilities does it demand (Bluetooth, Camera, P2P)?
 * - Does it bundle native NDK binaries (ARM64 / 16KB alignment)?
 * - What are the concrete feature primitives that define this app?
 */
object CapabilityAnalyzer {

    enum class NetworkTopology {
        OFFLINE_ONLY,
        PEER_TO_PEER_LAN,
        LOCAL_NETWORK_MDNS,
        BLUETOOTH_LE,
        WEBSOCKET_STREAMING,
        CLOUD_API_CLIENT,
        HYBRID_SYNC
    }

    enum class ExecutionMode {
        LIGHTWEIGHT_SCRIPT,
        FOREGROUND_APP,
        BACKGROUND_SERVICE,
        DAEMON_SERVER,
        ON_DEVICE_COMPILER,
        TERMINAL_CLI
    }

    data class CapabilityProfile(
        val networkTopology: NetworkTopology,
        val executionMode: ExecutionMode,
        val isZeroCloud: Boolean,
        val isOfflineFirst: Boolean,
        val usesNativeNdk: Boolean,
        val requestedPermissions: List<String>,
        val hardwareFeatures: List<String>,
        val coreFeatureTags: List<String>,
        val summaryStatement: String
    )

    /**
     * Inspects repository file contents (AndroidManifest.xml, build scripts, package manifests)
     * and extracts the full capability profile.
     */
    fun analyze(
        manifestXml: String? = null,
        buildScriptContent: String? = null,
        readmeContent: String? = null,
        filePaths: List<String> = emptyList()
    ): CapabilityProfile {
        val permissions = mutableListOf<String>()
        val hardware = mutableListOf<String>()
        val featureTags = mutableListOf<String>()

        val manifest = manifestXml.orEmpty()
        val build = buildScriptContent.orEmpty()
        val readme = readmeContent.orEmpty().lowercase()
        val pathSet = filePaths.toSet()

        // 1. Extract Android Permissions (stripping XML comments first)
        val sanitizedManifest = manifest.replace(Regex("""<!--[\s\S]*?-->"""), "")
        val permRegex = Regex("""<uses-permission\s+android:name=["'](?:android\.permission\.)?([^"']+)["']""")
        for (match in permRegex.findAll(sanitizedManifest)) {
            permissions.add(match.groupValues[1])
        }

        // 2. Extract Hardware Features
        val featureRegex = Regex("""<uses-feature\s+android:name=["'](?:android\.hardware\.)?([^"']+)["']""")
        for (match in featureRegex.findAll(sanitizedManifest)) {
            hardware.add(match.groupValues[1])
        }

        // 3. Classify Network Topology
        val hasInternet = if (manifest.isNotBlank()) {
            permissions.contains("INTERNET")
        } else {
            readme.contains("backend") || readme.contains("cloud api") || readme.contains("firebase") ||
                readme.contains("supabase") || readme.contains("rest api")
        }
        val hasP2p = permissions.contains("NEARBY_WIFI_DEVICES") || permissions.contains("ACCESS_WIFI_STATE") ||
            readme.contains("multicast") || readme.contains("p2p") || readme.contains("airdrop")
        val hasBle = permissions.contains("BLUETOOTH") || permissions.contains("BLUETOOTH_CONNECT") ||
            permissions.contains("BLUETOOTH_SCAN") || hardware.contains("bluetooth_le")
        val hasWs = readme.contains("websocket") || build.contains("ktor-client-websockets")

        val topology = when {
            !hasInternet && !hasP2p && !hasBle -> NetworkTopology.OFFLINE_ONLY
            hasBle -> NetworkTopology.BLUETOOTH_LE
            hasP2p -> NetworkTopology.PEER_TO_PEER_LAN
            hasWs -> NetworkTopology.WEBSOCKET_STREAMING
            readme.contains("mdns") || readme.contains("bonjour") || readme.contains("zeroconf") -> NetworkTopology.LOCAL_NETWORK_MDNS
            readme.contains("sync") || readme.contains("offline-first") -> NetworkTopology.HYBRID_SYNC
            else -> NetworkTopology.CLOUD_API_CLIENT
        }

        val isZeroCloud = topology in listOf(NetworkTopology.OFFLINE_ONLY, NetworkTopology.PEER_TO_PEER_LAN, NetworkTopology.BLUETOOTH_LE)
        val isOfflineFirst = isZeroCloud || readme.contains("offline-first") || readme.contains("local-first")

        // 4. Native NDK & Native binaries
        val usesNativeNdk = pathSet.any { it.endsWith(".so") || it.contains("jniLibs") || it.endsWith("CMakeLists.txt") } ||
            build.contains("externalNativeBuild") || build.contains("cargo-ndk")

        // 5. Detect Core Feature Tags
        if (isZeroCloud) featureTags.add("100% Zero-Cloud / Private")
        if (isOfflineFirst) featureTags.add("Offline-First Architecture")
        if (usesNativeNdk) featureTags.add("Native C++/Rust Acceleration")
        if (readme.contains("e2ee") || readme.contains("end-to-end encrypt") || readme.contains("encryption")) {
            featureTags.add("End-to-End Encryption")
        }
        if (readme.contains("pip") || readme.contains("picture-in-picture") || permissions.contains("SYSTEM_ALERT_WINDOW")) {
            featureTags.add("Picture-in-Picture (PiP)")
        }
        if (permissions.contains("FOREGROUND_SERVICE") || readme.contains("background play") || readme.contains("background service")) {
            featureTags.add("Background Execution")
        }
        if (hasP2p || hasBle) {
            featureTags.add("Local Device Discovery (Zero-Config)")
        }
        if (readme.contains("no ads") || readme.contains("ad-free") || readme.contains("no telemetry") || readme.contains("no tracking")) {
            featureTags.add("No Telemetry & Ad-Free")
        }

        // 6. Execution Mode
        val executionMode = when {
            permissions.contains("FOREGROUND_SERVICE") -> ExecutionMode.BACKGROUND_SERVICE
            readme.contains("compiler") || readme.contains("runtime") || readme.contains("interpreter") -> ExecutionMode.ON_DEVICE_COMPILER
            readme.contains("terminal") || readme.contains("pty") || readme.contains("shell") -> ExecutionMode.TERMINAL_CLI
            readme.contains("daemon") || readme.contains("server") -> ExecutionMode.DAEMON_SERVER
            else -> ExecutionMode.FOREGROUND_APP
        }

        // 7. Human-readable summary statement
        val summary = buildString {
            append(if (isZeroCloud) "Private local-only tool" else "Connected mobile client")
            append(" running as ${executionMode.name.replace('_', ' ').lowercase()}")
            if (featureTags.isNotEmpty()) {
                append(" with ${featureTags.take(3).joinToString(", ")}")
            }
            append(".")
        }

        return CapabilityProfile(
            networkTopology = topology,
            executionMode = executionMode,
            isZeroCloud = isZeroCloud,
            isOfflineFirst = isOfflineFirst,
            usesNativeNdk = usesNativeNdk,
            requestedPermissions = permissions,
            hardwareFeatures = hardware,
            coreFeatureTags = featureTags,
            summaryStatement = summary
        )
    }

    /**
     * Produces a side-by-side capability diff between two repositories.
     */
    data class CapabilityDiff(
        val repoA: String,
        val repoB: String,
        val sharedFeatures: List<String>,
        val repoAExclusiveFeatures: List<String>,
        val repoBExclusiveFeatures: List<String>,
        val privacyAdvantage: String?
    )

    fun diffCapabilities(
        slugA: String,
        profileA: CapabilityProfile,
        slugB: String,
        profileB: CapabilityProfile
    ): CapabilityDiff {
        val shared = profileA.coreFeatureTags.intersect(profileB.coreFeatureTags.toSet()).toList()
        val exclusiveA = profileA.coreFeatureTags.subtract(profileB.coreFeatureTags.toSet()).toList()
        val exclusiveB = profileB.coreFeatureTags.subtract(profileA.coreFeatureTags.toSet()).toList()

        val privacyAdvantage = when {
            profileA.isZeroCloud && !profileB.isZeroCloud -> "$slugA operates 100% offline without cloud relays (unlike $slugB)."
            profileB.isZeroCloud && !profileA.isZeroCloud -> "$slugB operates 100% offline without cloud relays (unlike $slugA)."
            else -> null
        }

        return CapabilityDiff(
            repoA = slugA,
            repoB = slugB,
            sharedFeatures = shared,
            repoAExclusiveFeatures = exclusiveA,
            repoBExclusiveFeatures = exclusiveB,
            privacyAdvantage = privacyAdvantage
        )
    }
}
