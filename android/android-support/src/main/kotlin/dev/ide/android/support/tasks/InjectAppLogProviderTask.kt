package dev.ide.android.support.tasks

import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskInputsImpl
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskOutputsImpl
import dev.ide.build.TaskResult
import dev.ide.build.engine.debug
import dev.ide.build.engine.warn
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * `injectAppLogProvider`: on a DEBUG build, weave a `<provider>` for the IDE's log bridge
 * ([dev.ide.android.support.tools.AndroidAppLogRuntime]) into the merged manifest, producing a separate
 * instrumented manifest that `aapt2 link` consumes instead of the plain merged one. The bridge's
 * `ContentProvider.onCreate` runs early in the app's startup and forwards its logs to the IDE.
 *
 * Kept as a distinct output ([outManifest], not an in-place rewrite of [mergedManifest]) so the un-instrumented
 * merged manifest stays available for other consumers (e.g. the layout preview relink), and so the build graph
 * has a clean producer→consumer edge rather than two tasks writing the same file.
 */
internal class InjectAppLogProviderTask(
    override val name: TaskName,
    private val mergedManifest: Path,
    private val providerClass: String,
    private val authority: String,
    private val sinkAction: String?,
    private val outManifest: Path,
    /** Non-null only for explicitly consented reliability collection. Legacy injection has no consent. */
    private val configAsset: String? = null,
    /** Contains ONLY the explicit recipient package, not the pairing nonce. Generated in a private cache. */
    private val recipientPackageFile: Path? = null,
) : Task {
    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            filePaths("manifest", listOf(mergedManifest).filter { Files.exists(it) })
            property("provider", providerClass)
            property("authority", authority)
            property("sinkAction", sinkAction.orEmpty())
            property("configAsset", configAsset.orEmpty())
            filePaths("recipient", listOfNotNull(recipientPackageFile))
        }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { filePath("manifest", outManifest) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        if (!Files.isRegularFile(mergedManifest))
            return TaskResult.Failed("merged manifest not found: $mergedManifest")

        val doc = try {
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                isExpandEntityReferences = false
            }.newDocumentBuilder().parse(mergedManifest.toFile())
        } catch (t: Throwable) {
            return TaskResult.Failed("app-log injection could not parse the merged manifest: ${t.message}", t)
        }

        val application = firstChildTag(doc.documentElement, "application")
        if (application == null) {
            if (configAsset != null) return TaskResult.Failed("Reliability: opted-in app manifest has no <application> element.")
            // No <application> — nothing to instrument; pass the manifest through untouched.
            outManifest.parent?.let { Files.createDirectories(it) }
            Files.copy(mergedManifest, outManifest, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            ctx.warn("injectAppLogProvider: no <application> element — skipped")
            return TaskResult.Success
        }

        // An app that declares it has no code is one the platform loads no dex for, so a <provider> naming a
        // Java class would not resolve and the app would die on launch with ClassNotFoundException. A
        // NativeActivity app is exactly that shape, and it is also the app that needs the bridge most: every
        // line it logs goes through `__android_log_print`, which only the bridge's logcat reader can forward.
        // This task only runs when the bridge's runtime is dexed into the APK, so the debug APK does carry
        // code; saying so is what lets the platform load the bridge. NativeActivity itself is a framework
        // class and runs the same either way. Release builds are never instrumented and keep the attribute.
        if (application.androidAttr("hasCode") == "false") {
            application.setAttributeNS(ANDROID_NS, "android:hasCode", "true")
            if (application.hasAttribute("hasCode")) application.setAttribute("hasCode", "true")
            ctx.debug("injectAppLogProvider: android:hasCode=\"false\" set to true, the bridge's dex is packaged")
        }

        val manifest = doc.documentElement
        manifest.setAttribute("xmlns:android", "http://schemas.android.com/apk/res/android")
        val providers = childElements(application).filter {
            it.tagName == "provider" && it.androidAttr("name") == providerClass
        }
        if (providers.size > 1) return TaskResult.Failed("Reliability: duplicate SDK providers in the merged manifest.")
        val provider = providers.firstOrNull() ?: doc.createElement("provider").also { application.appendChild(it) }
        provider.setAttributeNS(ANDROID_NS, "android:name", providerClass)
        provider.setAttributeNS(ANDROID_NS, "android:authorities", authority)
        provider.setAttributeNS(ANDROID_NS, "android:exported", "false")
        provider.setAttributeNS(ANDROID_NS, "android:initOrder", if (configAsset != null) "100" else "2147483646")
        provider.removeAttributeNS(ANDROID_NS, "process") // SDK startup belongs to the application's main process.
        provider.removeAttributeNS(ANDROID_NS, "multiprocess")
        provider.removeAttributeNS(ANDROID_NS, "grantUriPermissions")

        if (configAsset != null) {
            val key = dev.ide.android.support.ReliabilityProjectOptions.CONFIG_METADATA
            childElements(application).filter { it.tagName == "meta-data" && it.androidAttr("name") == key }
                .forEach { application.removeChild(it) }
            val metadata = doc.createElement("meta-data")
            metadata.setAttributeNS(ANDROID_NS, "android:name", key)
            metadata.setAttributeNS(ANDROID_NS, "android:value", configAsset)
            application.appendChild(metadata)
        }
        // Reliability NEVER discovers an arbitrary action handler. Visibility names the explicit, paired
        // debug IDE package only. A production config has no recipient and therefore no generated query.
        val recipient = recipientPackageFile?.let {
            if (!Files.isRegularFile(it)) return TaskResult.Failed("Reliability: generated recipient is missing.")
            String(Files.readAllBytes(it), Charsets.UTF_8).trim()
        }.orEmpty()
        if (recipient.isNotEmpty()) {
            if (!recipient.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
                return TaskResult.Failed("Reliability: invalid explicit recipient package.")
            val queries = firstChildTag(manifest, "queries") ?: doc.createElement("queries").also { manifest.appendChild(it) }
            if (childElements(queries).none { it.tagName == "package" && it.androidAttr("name") == recipient }) {
                val pkg = doc.createElement("package")
                pkg.setAttributeNS(ANDROID_NS, "android:name", recipient)
                queries.appendChild(pkg)
            }
        }
        // Compatibility for explicit legacy callers only; this does not add reliability consent metadata.
        if (configAsset == null && sinkAction != null) {
            val queries = firstChildTag(manifest, "queries") ?: doc.createElement("queries").also { manifest.appendChild(it) }
            val hasSinkIntent = childElements(queries).any { q ->
                q.tagName == "intent" && childElements(q).any { it.tagName == "action" && it.androidAttr("name") == sinkAction }
            }
            if (!hasSinkIntent) {
                val intent = doc.createElement("intent")
                val action = doc.createElement("action")
                action.setAttributeNS(ANDROID_NS, "android:name", sinkAction)
                intent.appendChild(action)
                queries.appendChild(intent)
            }
        }

        outManifest.parent?.let { Files.createDirectories(it) }
        // Serialize by hand (NOT javax.xml.transform): the identity Transformer is an ART hazard — on device
        // it reaches for a SAX driver that isn't on the platform and fails/corrupts the manifest.
        Files.write(outManifest, XmlDomWriter.toXml(doc).toByteArray(Charsets.UTF_8))
        ctx.debug("injectAppLogProvider -> ${outManifest.fileName} (provider $providerClass, authority $authority)")
        return TaskResult.Success
    }

    private fun firstChildTag(parent: Element?, tag: String): Element? =
        parent?.let { childElements(it).firstOrNull { el -> el.tagName == tag } }

    private fun childElements(parent: Element): List<Element> {
        val out = ArrayList<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) (children.item(i) as? Element)?.let { out += it }
        return out
    }

    private fun Element.androidAttr(local: String): String? =
        getAttributeNS(ANDROID_NS, local).ifEmpty { getAttribute("android:$local") }
            .ifEmpty { getAttribute(local) }.ifEmpty { null }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
