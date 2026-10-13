package dev.ide.workbench.publish

import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.jar.JarFile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Local format/integrity checks, not a claim of Play policy compliance or review approval. */
object PlayPreflight {
    fun validatePackageName(packageName: String) {
        if (packageName.length !in 3..255 || !packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) {
            invalid("Use a valid Android application ID with at least two dot-separated segments.")
        }
    }

    fun validateLanguage(language: String) {
        if (!language.matches(Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*"))) invalid("Use a valid BCP-47 listing language, such as en-US.")
    }

    fun validateTrackId(track: String) {
        if (track.isBlank() || track.length > 200 || track in setOf(".", "..") || track.any { it.isISOControl() || it in "/\\?#" }) {
            invalid("Choose a valid track ID returned by Google Play.")
        }
    }

    fun validateListing(listing: PlayListing) {
        validateLanguage(listing.language)
        textLength(listing.title, 30, "App title")
        textLength(listing.shortDescription, 80, "Short description")
        textLength(listing.fullDescription, 4000, "Full description")
        listing.video?.takeIf { it.isNotBlank() }?.let {
            val uri = runCatching { URI(it) }.getOrNull()
            if (uri?.scheme != "https" || uri.host !in setOf("youtube.com", "www.youtube.com", "youtu.be") || uri.userInfo != null) {
                invalid("The promotional video must be an HTTPS YouTube URL.")
            }
        }
    }

    fun validateTrack(track: PlayTrack) {
        validateTrackId(track.track)
        if (track.releases.isEmpty()) invalid("Choose at least one explicit release; empty track replacement is not supported.")
        if (track.releases.count { it.status == PlayReleaseStatus.IN_PROGRESS } > 1) invalid("A track cannot have multiple in-progress releases in this client.")
        track.releases.forEach { release ->
            if (release.versionCodes.isEmpty() || release.versionCodes.distinct().size != release.versionCodes.size || release.versionCodes.any { it !in 1..2_100_000_000L }) {
                invalid("Supply distinct valid version codes for every release, retaining older versions where needed.")
            }
            release.userFraction?.let {
                if (!it.isFinite() || it <= 0.0 || it >= 1.0 || release.status !in setOf(PlayReleaseStatus.IN_PROGRESS, PlayReleaseStatus.HALTED)) {
                    invalid("A staged fraction must be between zero and one and is allowed only for in-progress or halted releases.")
                }
            }
            if (release.status == PlayReleaseStatus.IN_PROGRESS && release.userFraction == null) invalid("An in-progress release needs an explicit rollout fraction.")
            if (release.inAppUpdatePriority != null && release.inAppUpdatePriority !in 0..5) invalid("In-app update priority must be between zero and five.")
            release.countryTargeting?.let { target ->
                if (release.status != PlayReleaseStatus.IN_PROGRESS || !(track.track == "production" || track.track.endsWith(":production"))) invalid("Country targeting is supported only for in-progress production releases.")
                if (target.countries.distinct().size != target.countries.size || target.countries.any { !it.matches(Regex("[A-Z]{2}")) }) invalid("Country targeting requires distinct two-letter CLDR country codes.")
            }
            if (release.releaseNotes.map { it.language }.distinct().size != release.releaseNotes.size) invalid("Supply only one release note per language.")
            release.releaseNotes.forEach { validateLanguage(it.language); textLength(it.text, 500, "Release notes") }
        }
    }

    fun inspectAab(file: File, expectedPackageName: String? = null): AabArtifact = safeFileValidation {
        expectedPackageName?.let(::validatePackageName)
        if (!file.isFile || file.length() <= 0 || !file.name.endsWith(".aab", ignoreCase = true)) invalid("Select a readable, nonempty Android App Bundle (.aab).")
        if (file.length() > 53_687_091_200L) invalid("The bundle exceeds the API upload limit.")
        val originalSize = file.length()
        val originalModified = file.lastModified()
        var manifest: ByteArray? = null
        var certificate: String? = null
        JarFile(file, true).use { jar ->
            val seen = HashSet<String>()
            var unpacked = 0L
            var entryCount = 0
            val entries = jar.entries()
            val buffer = ByteArray(64 * 1024)
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (++entryCount > 200_000 || !seen.add(entry.name) || entry.name.startsWith("/") || entry.name.split('/').any { it == ".." } || '\\' in entry.name) {
                    invalid("The bundle contains duplicate, unsafe, or excessive ZIP entries.")
                }
                if (entry.isDirectory) continue
                if (entry.size > 2_147_483_648L) invalid("A bundle entry is too large for local verification.")
                val manifestBytes = if (entry.name == "base/manifest/AndroidManifest.xml") java.io.ByteArrayOutputStream() else null
                jar.getInputStream(entry).use { stream ->
                    var currentSize = 0L
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        currentSize += read
                        unpacked += read
                        if (currentSize > 2_147_483_648L || unpacked > 68_719_476_736L) invalid("The bundle exceeds local decompression safety limits.")
                        if (manifestBytes != null) {
                            if (currentSize > 8_388_608) invalid("The bundle manifest is too large.")
                            manifestBytes.write(buffer, 0, read)
                        }
                    }
                }
                if (manifestBytes != null) manifest = manifestBytes.toByteArray()
                val signatureMetadata = entry.name.uppercase().let {
                    it == "META-INF/MANIFEST.MF" || it.matches(Regex("META-INF/[^/]+\\.(SF|RSA|DSA|EC)")) || it.startsWith("META-INF/SIG-")
                }
                if (!signatureMetadata) {
                    val signers = entry.codeSigners
                    if (signers.isNullOrEmpty()) invalid("The AAB contains unsigned content. Build and sign a release bundle with the upload key.")
                    if (signers.size != 1) invalid("Use one upload signing identity for the AAB.")
                    val signingCertificate = signers.first().signerCertPath.certificates.first()
                    val subject = (signingCertificate as? java.security.cert.X509Certificate)?.subjectX500Principal?.name.orEmpty()
                    if (Regex("(?:^|,)\\s*CN\\s*=\\s*\\\"?Android Debug\\\"?\\s*(?:,|$)", RegexOption.IGNORE_CASE).containsMatchIn(subject)) {
                        invalid("This bundle is signed with the Android debug certificate. Assign a release upload keystore and rebuild.")
                    }
                    val fingerprint = sha256(signingCertificate.encoded)
                    if (certificate != null && certificate != fingerprint) invalid("All AAB content must use the same upload signing identity.")
                    certificate = fingerprint
                }
            }
            if ("BundleConfig.pb" !in seen || manifest == null || certificate == null) invalid("The file is not a signed base-module Android App Bundle.")
        }
        val info = AabManifest.parse(manifest!!)
        validatePackageName(info.packageName)
        if (expectedPackageName != null && expectedPackageName != info.packageName) invalid("The bundle application ID does not match the selected Play app.")
        if (info.versionCode !in 1..2_100_000_000L) invalid("The bundle version code is outside Google Play's supported range.")
        if (info.debuggable) invalid("Build a non-debuggable release bundle before publishing.")
        val digest = sha256(file)
        if (file.length() != originalSize || file.lastModified() != originalModified) invalid("The bundle changed during verification. Build again and reselect it.")
        AabArtifact(file.absoluteFile, info.packageName, info.versionCode, info.versionName, info.minSdk, info.targetSdk, digest, originalSize, certificate!!, info.permissions)
    }

    fun inspectImage(file: File, type: PlayImageType): ImageAsset = safeFileValidation {
        if (!file.isFile || file.length() !in 1..15_728_640L) invalid("Select a PNG or JPEG image within the API's 15 MiB limit.")
        val originalSize = file.length()
        val info = file.inputStream().buffered().use(::imageInfo)
        if (info.width <= 0 || info.height <= 0) invalid("The image has invalid dimensions.")
        when (type) {
            PlayImageType.ICON -> {
                if (info.mime != "image/png" || info.width != 512 || info.height != 512 || file.length() > 1_048_576) {
                    invalid("A Play icon must be a 512 by 512 PNG of at most 1024 KiB.")
                }
                if (info.pngDepth != 8 || info.pngColorType != 6) invalid("The Play icon must use 32-bit RGBA PNG encoding (opaque pixels are allowed).")
            }
            PlayImageType.FEATURE_GRAPHIC -> {
                if (info.width != 1024 || info.height != 500) invalid("The feature graphic must measure 1024 by 500 pixels.")
                requireOpaque24Bit(info)
            }
            PlayImageType.TV_BANNER -> {
                if (info.width != 1280 || info.height != 720) invalid("The TV banner must measure 1280 by 720 pixels.")
                requireOpaque24Bit(info)
            }
            PlayImageType.WEAR_SCREENSHOTS -> {
                if (info.width != info.height || info.width !in 384..3840) invalid("Wear screenshots must be square and at least 384 pixels.")
                requireOpaque24Bit(info)
            }
            else -> {
                if (info.width !in 320..3840 || info.height !in 320..3840 || maxOf(info.width, info.height) > 2 * minOf(info.width, info.height)) {
                    invalid("Screenshots must be 320 to 3840 pixels per side, with the longer side no more than twice the shorter side.")
                }
                requireOpaque24Bit(info)
            }
        }
        val digest = sha256(file)
        if (file.length() != originalSize) invalid("The image changed during verification.")
        ImageAsset(file.absoluteFile, type, info.width, info.height, info.mime, digest, originalSize)
    }

    internal fun ensureUnchanged(artifact: AabArtifact) {
        if (!artifact.file.isFile || artifact.file.length() != artifact.sizeBytes || sha256(artifact.file) != artifact.sha256) invalid("The bundle changed after preflight. Verify it again before upload.")
    }
    internal fun ensureUnchanged(asset: ImageAsset) {
        if (!asset.file.isFile || asset.file.length() != asset.sizeBytes || sha256(asset.file) != asset.sha256) invalid("The image changed after preflight. Verify it again before upload.")
    }
    internal fun validateDataSafety(draft: DataSafetyDraft) {
        validatePackageName(draft.packageName)
        if (draft.csv.isBlank() || draft.csv.length > 4_194_304 || '\u0000' in draft.csv || ',' !in draft.csv || '\n' !in draft.csv) {
            invalid("Supply the contents of the current Play Console Data safety CSV template, not a file name or JSON object.")
        }
        // Google owns the evolving CSV schema. Syntax checks must not pretend to certify policy answers.
    }
    private fun textLength(value: String, max: Int, label: String) {
        if (value.isBlank() || value.codePointCount(0, value.length) > max || '\u0000' in value) invalid("$label must contain between one and $max characters.")
    }
    private fun requireOpaque24Bit(info: ImageInfo) {
        if (info.alpha || info.mime == "image/png" && (info.pngDepth != 8 || info.pngColorType != 2)) invalid("Use a JPEG or 24-bit RGB PNG without an alpha channel for this asset.")
    }

    private data class ImageInfo(val width: Int, val height: Int, val mime: String, val alpha: Boolean, val pngDepth: Int = 0, val pngColorType: Int = 0)
    private fun imageInfo(input: InputStream): ImageInfo {
        input.mark(40)
        val signature = input.readNBytesCompat(8)
        input.reset()
        if (signature.contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))) {
            input.readNBytesCompat(8)
            var info: ImageInfo? = null
            var idat = false
            while (true) {
                val header = input.readNBytesCompat(8)
                if (header.size != 8) invalid("The PNG is truncated.")
                val length = ByteBuffer.wrap(header, 0, 4).order(ByteOrder.BIG_ENDIAN).int
                val type = header.copyOfRange(4, 8).toString(Charsets.US_ASCII)
                if (info == null && type != "IHDR") invalid("The PNG must begin with its image header.")
                if (length !in 0..15_728_640) invalid("The PNG contains an invalid chunk.")
                val payload = input.readNBytesCompat(length)
                val checksum = input.readNBytesCompat(4)
                if (payload.size != length || checksum.size != 4) invalid("The PNG is truncated.")
                val crc = java.util.zip.CRC32().apply { update(header, 4, 4); update(payload) }.value
                if (crc != (ByteBuffer.wrap(checksum).int.toLong() and 0xffffffffL)) invalid("The PNG checksum is invalid.")
                when (type) {
                    "IHDR" -> {
                        if (info != null || payload.size != 13) invalid("The PNG header is invalid.")
                        val buffer = ByteBuffer.wrap(payload)
                        val w = buffer.int; val h = buffer.int; val depth = payload[8].toInt() and 255; val color = payload[9].toInt() and 255
                        if (payload[10].toInt() != 0 || payload[11].toInt() != 0 || payload[12].toInt() !in 0..1) invalid("The PNG compression, filter, or interlace method is invalid.")
                        info = ImageInfo(w, h, "image/png", color == 4 || color == 6, depth, color)
                    }
                    "tRNS" -> info = (info ?: invalid("The PNG header is missing.")).copy(alpha = true)
                    "IDAT" -> { if (info == null) invalid("The PNG header is missing."); idat = true }
                    "IEND" -> {
                        if (length != 0 || input.read() != -1) invalid("The PNG end marker or trailing content is invalid.")
                        return if (idat && info != null) info else invalid("The PNG has no image data.")
                    }
                }
            }
        }
        if (input.read() != 0xff || input.read() != 0xd8) invalid("Only PNG and JPEG store assets are supported.")
        while (true) {
            var prefix = input.read()
            while (prefix != 0xff && prefix >= 0) prefix = input.read()
            if (prefix < 0) invalid("The JPEG is truncated.")
            var marker = input.read()
            while (marker == 0xff) marker = input.read()
            if (marker == 0xd9 || marker == 0xda || marker < 0) invalid("The JPEG has no readable dimensions.")
            if (marker == 0x01 || marker in 0xd0..0xd8) continue
            val lengthHigh = input.read(); val lengthLow = input.read()
            if (lengthHigh < 0 || lengthLow < 0) invalid("The JPEG is truncated.")
            val length = (lengthHigh shl 8) or lengthLow
            if (length < 2) invalid("The JPEG marker is invalid.")
            val payload = input.readNBytesCompat(length - 2)
            if (payload.size != length - 2) invalid("The JPEG is truncated.")
            if (marker in setOf(0xc0, 0xc1, 0xc2)) {
                if (payload.size < 6 || payload[0].toInt() != 8 || payload[5].toInt() != 3) invalid("Use a standard 24-bit RGB JPEG.")
                val h = ((payload[1].toInt() and 255) shl 8) or (payload[2].toInt() and 255)
                val w = ((payload[3].toInt() and 255) shl 8) or (payload[4].toInt() and 255)
                return ImageInfo(w, h, "image/jpeg", false)
            }
        }
    }
}

internal fun sha256(file: File): String = safeFileValidation {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
    digest.digest().hex()
}
internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
private inline fun <T> safeFileValidation(block: () -> T): T = try { block() } catch (e: PlayPublishingException) { throw e } catch (_: Exception) { invalid("The selected artifact could not be verified. Check its file format and signing, then rebuild or reselect it.") }
private fun InputStream.readNBytesCompat(count: Int): ByteArray {
    val bytes = ByteArray(count); var offset = 0
    while (offset < count) { val n = read(bytes, offset, count - offset); if (n < 0) break; offset += n }
    return if (offset == count) bytes else bytes.copyOf(offset)
}

/** Minimal bounded reader of AAPT2 Resources.proto; unknown fields are skipped, not guessed. */
internal object AabManifest {
    data class Info(val packageName: String, val versionCode: Long, val versionName: String?, val minSdk: Int?, val targetSdk: Int?, val debuggable: Boolean, val permissions: Set<String>)
    private const val ANDROID = "http://schemas.android.com/apk/res/android"
    private data class Attribute(val namespace: String, val name: String, val raw: String?, val number: Long?)
    private data class Element(val name: String, val attributes: List<Attribute>, val children: List<Element>) {
        fun attr(name: String, namespace: String = ANDROID): Attribute? = attributes.singleOrNull { it.name == name && it.namespace == namespace }
    }
    fun parse(bytes: ByteArray): Info {
        val root = node(bytes, 0) ?: invalid("The AAB manifest is not an AAPT2 protobuf XML element.")
        if (root.name != "manifest") invalid("The bundle manifest root is invalid.")
        fun Attribute?.long(): Long? = this?.number ?: this?.raw?.let { raw -> if (raw.startsWith("0x")) raw.drop(2).toLongOrNull(16) else raw.toLongOrNull() }
        val application = root.children.singleOrNull { it.name == "application" } ?: invalid("The bundle has no unique application declaration.")
        val sdk = root.children.firstOrNull { it.name == "uses-sdk" }
        val debug = application.attr("debuggable")
        if (debug != null && debug.number == null && debug.raw !in setOf("true", "false")) invalid("The bundle debuggable flag could not be resolved.")
        return Info(
            root.attr("package", "")?.raw ?: invalid("The bundle has no package name."),
            root.attr("versionCode").long() ?: invalid("The bundle has no numeric version code."),
            root.attr("versionName")?.raw,
            sdk?.attr("minSdkVersion").long()?.toInt(), sdk?.attr("targetSdkVersion").long()?.toInt(),
            debug?.number?.let { it != 0L } ?: (debug?.raw == "true"),
            root.children.filter { it.name == "uses-permission" || it.name == "uses-permission-sdk-23" }.mapNotNull { it.attr("name")?.raw }.toSet(),
        )
    }
    private fun node(bytes: ByteArray, depth: Int): Element? {
        if (depth > 64) invalid("The bundle manifest is too deeply nested.")
        return fields(bytes).firstOrNull { it.number == 1 }?.bytes?.let { element(it, depth) }
    }
    private fun element(bytes: ByteArray, depth: Int): Element {
        val fields = fields(bytes)
        return Element(fields.text(3) ?: "", fields.filter { it.number == 4 }.map { attribute(it.bytes ?: invalid("Invalid manifest attribute.")) }, fields.filter { it.number == 5 }.mapNotNull { it.bytes?.let { b -> node(b, depth + 1) } })
    }
    private fun attribute(bytes: ByteArray): Attribute {
        val fields = fields(bytes)
        val item = fields.firstOrNull { it.number == 6 }?.bytes?.let(::fields)
        val primitive = item?.firstOrNull { it.number == 7 }?.bytes?.let(::fields)
        val compiledString = item?.firstOrNull { it.number == 2 || it.number == 3 }?.bytes?.let(::fields)?.text(1)
        return Attribute(fields.text(1) ?: "", fields.text(2) ?: "", fields.text(3) ?: compiledString, primitive?.firstOrNull { it.number in 6..8 }?.value)
    }
    private data class Field(val number: Int, val bytes: ByteArray? = null, val value: Long? = null)
    private fun List<Field>.text(number: Int): String? = lastOrNull { it.number == number }?.bytes?.toString(Charsets.UTF_8)
    private fun fields(bytes: ByteArray): List<Field> {
        var cursor = 0
        fun varint(): Long {
            var value = 0L; var shift = 0
            while (shift < 64 && cursor < bytes.size) {
                val b = bytes[cursor++].toInt() and 255
                value = value or ((b and 127).toLong() shl shift)
                if (b and 128 == 0) return value
                shift += 7
            }
            invalid("The bundle manifest contains an invalid protobuf value.")
        }
        val result = ArrayList<Field>()
        while (cursor < bytes.size) {
            if (result.size > 100_000) invalid("The bundle manifest contains too many fields.")
            val tag = varint(); val number = (tag ushr 3).toInt()
            if (number <= 0) invalid("The bundle manifest contains an invalid protobuf field.")
            when ((tag and 7).toInt()) {
                0 -> result += Field(number, value = varint())
                1 -> cursor += 8
                2 -> {
                    val length = varint()
                    if (length < 0 || length > bytes.size - cursor) invalid("The bundle manifest is truncated.")
                    result += Field(number, bytes.copyOfRange(cursor, cursor + length.toInt()))
                    cursor += length.toInt()
                }
                5 -> cursor += 4
                else -> invalid("The bundle manifest has an unsupported protobuf wire type.")
            }
            if (cursor > bytes.size) invalid("The bundle manifest is truncated.")
        }
        return result
    }
}

internal fun PlayListing.json(): JsonObject = buildJsonObject {
    put("language", language); put("title", title); put("shortDescription", shortDescription); put("fullDescription", fullDescription)
    video?.let { put("video", it) }
}
internal val RELEASE_FIELDS = setOf("versionCodes", "status", "name", "releaseNotes", "userFraction", "inAppUpdatePriority", "countryTargeting")

internal fun PlayTrack.json(): JsonObject = buildJsonObject {
    additionalFields.filterKeys { it !in setOf("track", "releases") }.forEach { (key, value) -> put(key, value) }
    put("track", track)
    put("releases", buildJsonArray {
        releases.forEach { release -> add(buildJsonObject {
            release.additionalFields.filterKeys { it !in RELEASE_FIELDS }.forEach { (key, value) -> put(key, value) }
            put("versionCodes", buildJsonArray { release.versionCodes.forEach { add(JsonPrimitive(it.toString())) } })
            put("status", release.status.wireValue)
            release.name?.let { put("name", it) }
            release.userFraction?.let { put("userFraction", it) }
            release.inAppUpdatePriority?.let { put("inAppUpdatePriority", it) }
            release.countryTargeting?.let { targeting -> put("countryTargeting", buildJsonObject {
                targeting.additionalFields.filterKeys { it !in setOf("countries", "includeRestOfWorld") }.forEach { (key, value) -> put(key, value) }
                put("countries", buildJsonArray { targeting.countries.forEach { add(JsonPrimitive(it)) } })
                put("includeRestOfWorld", targeting.includeRestOfWorld)
            }) }
            if (release.releaseNotes.isNotEmpty()) put("releaseNotes", buildJsonArray { release.releaseNotes.forEach { add(buildJsonObject { put("language", it.language); put("text", it.text) }) } })
        }) }
    })
}
