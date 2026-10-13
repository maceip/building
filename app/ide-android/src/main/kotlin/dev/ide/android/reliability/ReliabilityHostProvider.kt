package dev.ide.android.reliability

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import dev.ide.workbench.reliability.DurableIncidentRepository
import dev.ide.workbench.reliability.Incident
import dev.ide.workbench.reliability.IncidentCaller
import dev.ide.workbench.reliability.IncidentReceiver
import dev.ide.workbench.reliability.IncidentRepository
import dev.ide.workbench.reliability.IncidentWire
import dev.ide.workbench.reliability.PairingRegistry
import dev.ide.workbench.reliability.PairingSecret
import dev.ide.workbench.reliability.PairingStatus
import dev.ide.workbench.reliability.ReliabilityError
import dev.ide.workbench.reliability.ReliabilityException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Shared by the service, current inbox, build injection and future local AI consumers. */
object ReliabilityHostProvider {
    @Volatile private var instance: ReliabilityHost? = null
    fun get(context: Context): ReliabilityHost = instance ?: synchronized(this) {
        instance ?: ReliabilityHost(context.applicationContext).also { instance = it }
    }
}

/** Contains a credential. Only use assetJson for a generated private DEBUG asset; never log this object. */
class DebugPairingGrant internal constructor(
    val packageName: String,
    val buildId: String,
    val sourceRevision: String,
    val pairingNonce: String,
    val idePackage: String,
    val ideService: String,
    val ideSignerSha256: String,
) {
    fun assetJson(): String = buildJsonObject {
        put("version", 1); put("enabled", true); put("consentGranted", true)
        put("packageName", packageName); put("buildId", buildId); put("sourceRevision", sourceRevision)
        put("pairingNonce", pairingNonce); put("idePackage", idePackage); put("ideService", ideService)
        put("ideSignerSha256", ideSignerSha256)
        put("captureCrashes", true); put("captureLifecycle", true); put("capturePreviousExitInfo", true)
        put("captureNetwork", false); put("teeStdout", false); put("productionEnabled", false)
    }.toString()
    override fun toString(): String = "DebugPairingGrant(packageName=$packageName, buildId=$buildId, pairingNonce=[REDACTED])"
}

data class PairingApkPreview(val packageName: String, val buildId: String, val sourceRevision: String, val signerSha256: Set<String>)

class ReliabilityHost internal constructor(private val context: Context) {
    private val directory = context.noBackupFilesDir.toPath().resolve("reliability")
    val pairings = PairingRegistry(EncryptedPairingStorage(context, directory.resolve("pairings.enc")))
    private val durable by lazy { DurableIncidentRepository(androidReliabilityFile(directory.resolve("incidents.json"), DurableIncidentRepository.MAX_SNAPSHOT_BYTES)) }
    val repository: IncidentRepository get() = durable
    /** Main may attach a mirror; it must not collect these events a second time into the durable inbox. */
    @Volatile var onCommitted: ((List<Incident>) -> Unit)? = null
    internal val receiver by lazy { IncidentReceiver(durable, pairings, onCommitted = { events -> onCommitted?.invoke(events) }) }

    /** Cross-process-safe build gate. Do not use a daemon's potentially stale StateFlow.value for decisions. */
    fun debugCaptureAllowed(): Boolean = pairings.isDebugCaptureEnabled()

    fun createPendingDebugPairing(packageName: String, buildId: String, sourceRevision: String = ""): DebugPairingGrant {
        val recipient = recipientCertificate()
        return grant(pairings.createPendingDebugPairing(packageName, buildId, sourceRevision.ifBlank { "unknown" }), recipient)
    }

    fun prepareDebugPairing(
        packageName: String,
        buildId: String,
        sourceRevision: String = "",
        expectedAppSignerSha256: Set<String>,
        expectedInstallerPackage: String? = context.packageName,
        allowUnknownInstaller: Boolean = false,
    ): DebugPairingGrant {
        val recipient = recipientCertificate()
        return grant(pairings.prepareDebugPairing(packageName, buildId, sourceRevision.ifBlank { "unknown" }, normalizePins(expectedAppSignerSha256),
            expectedInstallerPackage, allowUnknownInstaller), recipient)
    }

    fun prepareDebugPairing(
        appPackage: String, sourceBuildId: String, expectedAppCertSha256: String, expectedInstallerPackage: String?,
    ): DebugPairingGrant = prepareDebugPairing(appPackage, sourceBuildId, "", setOf(expectedAppCertSha256), expectedInstallerPackage)

    /** Explicit pairing is never auto-enabled by release packaging; external relay needs separate consent. */
    fun prepareExplicitPairing(
        packageName: String,
        buildId: String,
        sourceRevision: String = "",
        expectedAppSignerSha256: Set<String>,
        expectedInstallerPackage: String?,
        consentGranted: Boolean,
        allowUnknownInstaller: Boolean = false,
    ): DebugPairingGrant {
        val recipient = recipientCertificate()
        return grant(pairings.prepareExplicitPairing(packageName, buildId, sourceRevision.ifBlank { "unknown" }, normalizePins(expectedAppSignerSha256),
            expectedInstallerPackage, consentGranted, allowUnknownInstaller), recipient)
    }

    /** Preview reads and validates the signed debug APK and its exact private pending grant before confirmation. */
    fun inspectDebugPairingApk(packageName: String, buildId: String, apkPath: String): PairingApkPreview = safePackageOperation {
        val path = Paths.get(apkPath)
        if (!path.isAbsolute || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) !in 1..MAX_APK_BYTES) invalid()
        val info = context.packageManager.getPackageArchiveInfo(path.toString(), packageFlags() or PackageManager.GET_META_DATA) ?: invalid()
        if (info.packageName != packageName || info.applicationInfo?.flags?.and(ApplicationInfo.FLAG_DEBUGGABLE) == 0) invalid()
        val appInfo = info.applicationInfo ?: invalid()
        val assetName = appInfo.metaData?.getString("dev.ide.reliability.CONFIG_ASSET") ?: invalid()
        if (assetName != "ide-reliability.json") invalid()
        val signers = currentSigners(info)
        if (signers.isEmpty()) invalid()
        val asset = ZipFile(path.toFile()).use { zip ->
            val entries = zip.entries()
            var match: java.util.zip.ZipEntry? = null
            var count = 0
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (++count > 100_000) invalid()
                if (entry.name == "assets/$assetName") {
                    if (match != null) invalid()
                    match = entry
                }
            }
            val entry = match ?: invalid()
            if (entry.size > 16_384 || entry.isDirectory) invalid()
            val bytes = zip.getInputStream(entry).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1_024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (output.size() + n > 16_384) invalid()
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
            // Asset is tiny, and the bounded pre-scan stops pathological nesting before JSON parsing.
            boundedConfig(bytes.toString(Charsets.UTF_8))
        }
        fun text(name: String): String = (asset[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
        fun bool(name: String) = (asset[name] as? JsonPrimitive)?.booleanOrNull ?: false
        if ((asset["version"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull != 1 ||
            text("buildId") != buildId || text("idePackage") != context.packageName || text("ideService") != IncidentWire.SERVICE_CLASS ||
            !bool("enabled") || !bool("consentGranted") || bool("productionEnabled") || text("ideSignerSha256") != recipientCertificate()) invalid()
        if (asset.containsKey("packageName") && text("packageName") != packageName) invalid()
        val revision = text("sourceRevision")
        pairings.verifyPendingAsset(packageName, buildId, revision, text("pairingNonce"))
        PairingApkPreview(packageName, buildId, revision, signers)
    }

    /** Re-inspects on confirmation; a substituted APK cannot reuse the displayed certificate preview. */
    fun activateDebugPairing(
        packageName: String,
        buildId: String,
        apkPath: String,
        expectedInstallerPackage: String? = context.packageName,
        allowUnknownInstaller: Boolean = false,
        confirmedSignerSha256: Set<String>? = null,
    ) {
        val preview = inspectDebugPairingApk(packageName, buildId, apkPath)
        if (confirmedSignerSha256 != null && preview.signerSha256 != normalizePins(confirmedSignerSha256)) invalid()
        pairings.activateDebugPairing(packageName, buildId, preview.signerSha256, expectedInstallerPackage, allowUnknownInstaller)
    }

    fun revoke(packageName: String, buildId: String) = pairings.revoke(packageName, buildId)

    /** UID/PID come from Binder before doing anything that could clear its calling identity. */
    internal fun caller(packageName: String, uid: Int, pid: Int): IncidentCaller = safePackageOperation {
        val manager = context.packageManager
        val packages = manager.getPackagesForUid(uid)?.toSet().orEmpty()
        if (packages != setOf(packageName)) unauthorized()
        val info = manager.getPackageInfo(packageName, packageFlags())
        if (info.applicationInfo?.uid != uid) unauthorized()
        val installer = if (Build.VERSION.SDK_INT >= 30) manager.getInstallSourceInfo(packageName).installingPackageName
            else @Suppress("DEPRECATION") manager.getInstallerPackageName(packageName)
        IncidentCaller(uid, pid, packages, currentSigners(info), installer, signerHistory(info))
    }

    private fun grant(secret: PairingSecret, recipient: String): DebugPairingGrant = DebugPairingGrant(
        secret.pairing.packageName, secret.pairing.buildId, secret.pairing.sourceRevision, secret.pairingNonce,
        context.packageName, IncidentWire.SERVICE_CLASS, recipient,
    )

    private fun recipientCertificate(): String = safePackageOperation {
        // The current SDK contract pins one recipient certificate; multi-signer hosts fail closed.
        currentSigners(context.packageManager.getPackageInfo(context.packageName, packageFlags())).singleOrNull() ?: invalid()
    }

    private fun normalizePins(values: Set<String>) = values.map { it.replace(":", "").lowercase(java.util.Locale.ROOT) }.toSet()
    @Suppress("DEPRECATION") private fun packageFlags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    @Suppress("DEPRECATION") private fun currentSigners(info: PackageInfo): Set<String> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
    @Suppress("DEPRECATION") private fun signerHistory(info: PackageInfo): Set<String> =
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo?.hasMultipleSigners() == false) {
            info.signingInfo?.signingCertificateHistory?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
        } else currentSigners(info)

    private inline fun <T> safePackageOperation(operation: () -> T): T = try { operation() }
    catch (failure: ReliabilityException) { throw failure }
    catch (_: Exception) { unauthorized() }

    private fun invalid(): Nothing = throw ReliabilityException(ReliabilityError.INVALID)
    private fun unauthorized(): Nothing = throw ReliabilityException(ReliabilityError.UNAUTHORIZED)
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun boundedConfig(text: String): JsonObject {
        var depth = 0; var quoted = false; var escaped = false
        for (ch in text) {
            if (quoted) { if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false }
            else when (ch) { '"' -> quoted = true; '{', '[' -> { if (++depth > 4) invalid() }; '}', ']' -> { if (--depth < 0) invalid() } }
        }
        return Json.parseToJsonElement(text) as? JsonObject ?: invalid()
    }

    companion object { private const val MAX_APK_BYTES = 512L * 1_024 * 1_024 }
}
