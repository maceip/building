package dev.ide.interp.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.SystemTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import dev.ide.interp.PreviewResourceResolver
import dev.ide.lang.kotlin.interp.PreviewWire
import dev.ide.lang.kotlin.interp.ResolvedClass
import dev.ide.lang.kotlin.interp.ResolvedFunction
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * A lowered `@Preview` rendered to pixels with nothing but this module, its interpreter and the project's
 * own Compose: decode, interpret into an offscreen `ImageComposeScene`, return the frame.
 *
 * This is how a host with no JVM previews. It runs the whole desktop pipeline (the tree interpreter, the
 * Compose bridge, the renderer) inside `:jvm-vm` next to the project's libraries, so the interpreter's
 * reflection sees the project's Compose exactly as it sees the bundled one on the desktop, and the only
 * thing that crosses from the host is [encode]'s blob.
 */
object VmPreviewEntry {

    /**
     * The wire form of a lowered preview: its entry point, every lowered function, and the source classes.
     * [PreviewWire] is common code, so a host encodes this itself; this copy is for JVM callers.
     */
    @JvmStatic
    fun encode(entry: ResolvedFunction, program: Map<String, ResolvedFunction>, classes: List<ResolvedClass>): ByteArray =
        PreviewWire.encode(entry, program, classes)

    /**
     * Executes a non-UI Kotlin entry point (such as `fun main()` or `fun main(args: Array<String>)`) decoded from
     * [blob] inside the tree interpreter. Standard output written via `println`/`print` flows through the VM's
     * `System.out` stream (`Vm.stdout`), and the returned array is `[returnValue, error]`.
     */
    @JvmStatic
    fun executeMain(blob: ByteArray): Array<Any?> {
        val (entry, program, classes) = PreviewWire.decode(blob)
        var returnValue: String? = null
        var errorText: String? = null
        synchronized(renderLock) {
            try {
                val interpreter = dev.ide.interp.Interpreter(
                    functions = program,
                    classes = classes,
                    tolerateGaps = false,
                )
                val args: List<Any?> = if (entry.params.isEmpty()) {
                    emptyList()
                } else {
                    listOf(emptyArray<String>())
                }
                val res = interpreter.call(entry, args)
                if (res != null && res != Unit) {
                    returnValue = res.toString()
                }
            } catch (t: Throwable) {
                errorText = describe(t)
            }
        }
        return arrayOf(returnValue, errorText)
    }

    /**
     * The preview in [blob] rendered at [width]x[height] pixels under the `@Preview` options a host passes,
     * as `[png, problems]`: the PNG bytes, and what went wrong as text (null when nothing did). A preview
     * that fails part way still renders what it could, so a frame comes back with its problems.
     *
     * [fontScale] scales text as the system setting would; [dark] is the night theme
     * (`isSystemInDarkTheme()`); [background] is an ARGB colour painted behind the preview, or 0 for none.
     */
    @JvmStatic
    fun renderFrame(blob: ByteArray, width: Int, height: Int, density: Float, fontScale: Float, dark: Boolean, background: Long): Array<Any?> {
        val frame = render(blob, width, height, density, fontScale, dark, background)
        val png = frame.image.encodeToData(EncodedImageFormat.PNG)!!.bytes
        return arrayOf(png, frame.problems.takeIf { it.isNotEmpty() }?.joinToString("\n"))
    }

    /** The preview in [blob] rendered at [width]x[height] pixels, as a PNG. */
    @JvmStatic
    fun renderPng(blob: ByteArray, width: Int, height: Int, density: Float): ByteArray =
        render(blob, width, height, density).image.encodeToData(EncodedImageFormat.PNG)!!.bytes

    /** A hash of every pixel of the rendered frame, to compare two renders by. */
    @JvmStatic
    fun renderSignature(blob: ByteArray, width: Int, height: Int, density: Float): Long =
        signature(render(blob, width, height, density).image)

    /** `WxH inked=N`, then any problems: the frame's size and how many pixels differ from its top-left one. */
    @JvmStatic
    fun renderSummary(blob: ByteArray, width: Int, height: Int, density: Float): String {
        val frame = render(blob, width, height, density)
        val image = frame.image
        val pixels = Bitmap.makeFromImage(image).readPixels() ?: return "${image.width}x${image.height} no pixels"
        var inked = 0
        for (k in pixels.indices step 4) {
            if (pixels[k] != pixels[0] || pixels[k + 1] != pixels[1] || pixels[k + 2] != pixels[2]) inked++
        }
        val problems = if (frame.problems.isEmpty()) "" else " problems=" + frame.problems.joinToString(" | ")
        return "${image.width}x${image.height} inked=$inked$problems"
    }

    /** [renderSignature] with the problems the render reported, for a check that wants both. */
    @JvmStatic
    fun renderChecked(blob: ByteArray, width: Int, height: Int, density: Float): String {
        val frame = render(blob, width, height, density)
        return signature(frame.image).toString() + frame.problems.joinToString("") { "\n$it" }
    }

    @JvmStatic
    fun signature(image: Image): Long {
        val pixels = Bitmap.makeFromImage(image).readPixels() ?: return 0L
        var h = 1125899906842597L
        for (b in pixels) h = 31 * h + b
        return h
    }

    // ---- live sessions: a preview that stays composed, takes input and animates ----------------------------

    open class DynamicPreviewResourceResolver : PreviewResourceResolver {
        private val fieldToId = ConcurrentHashMap<String, Int>()
        private val idToName = ConcurrentHashMap<Int, String>()
        private val strings = ConcurrentHashMap<Int, String>()
        private val stringArrays = ConcurrentHashMap<Int, List<String>>()
        private val plurals = ConcurrentHashMap<Int, (Int) -> String>()
        private val colors = ConcurrentHashMap<Int, Any>()
        private val dimensions = ConcurrentHashMap<Int, Any>()
        private val painters = ConcurrentHashMap<Int, Any>()
        private val nextId = AtomicInteger(0x7f040001)

        override fun rClassField(ownerFqn: String, fieldName: String): Int? {
            val key = "$ownerFqn.$fieldName"
            return fieldToId.computeIfAbsent(key) {
                val id = nextId.getAndIncrement()
                idToName[id] = fieldName
                id
            }
        }

        override fun string(id: Int): String? {
            return strings[id] ?: idToName[id]?.replace('_', ' ')?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } ?: "Resource $id"
        }

        override fun stringArray(id: Int): List<String>? {
            return stringArrays[id] ?: listOf("Item 1", "Item 2", "Item 3")
        }

        override fun plural(id: Int, quantity: Int): String? {
            val custom = plurals[id]?.invoke(quantity)
            if (custom != null) return custom
            val base = idToName[id]?.replace('_', ' ') ?: "item"
            return "$quantity $base${if (quantity == 1) "" else "s"}"
        }

        override fun color(id: Int): Any? {
            return colors[id] ?: Color(0xFF6200EE)
        }

        override fun dimension(id: Int): Any? {
            return dimensions[id] ?: Dp(16f)
        }

        override fun painter(id: Int): Any? {
            return painters[id] ?: ColorPainter(Color.LightGray)
        }
    }

    private class Session(
        var scene: ImageComposeScene,
        val problems: MutableList<String>,
        var width: Int,
        var height: Int,
        val density: Float,
        val fontScale: Float,
        val dark: Boolean,
        val background: Long,
        val renderer: ComposePreviewRenderer,
        val entry: ResolvedFunction,
        val program: Map<String, ResolvedFunction>,
        val classes: List<ResolvedClass>,
    ) {
        val opened = System.nanoTime()
        var reported = 0
    }

    private val renderLock = Any()
    private val sessions = ConcurrentHashMap<Int, Session>()
    private val nextSession = AtomicInteger(1)

    /**
     * Composes the preview in [blob] into a scene that stays open, for a host that shows a live preview: it
     * can send input ([pointer]) and render again ([frame]) as state and animations change. Returns the
     * session's id; [close] releases it.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @JvmStatic
    fun open(blob: ByteArray, width: Int, height: Int, density: Float, fontScale: Float, dark: Boolean, background: Long): Int {
        val clampedWidth = width.coerceIn(1, 4096)
        val clampedHeight = height.coerceIn(1, 4096)
        val (entry, program, classes) = PreviewWire.decode(blob)
        val resolver = DynamicPreviewResourceResolver()
        val renderer = ComposePreviewRenderer(resources = resolver)
        val problems = ArrayList<String>()
        val scene = synchronized(renderLock) {
            ImageComposeScene(clampedWidth, clampedHeight, Density(density, fontScale)) {
                Themed(dark) {
                    val content = @Composable {
                        renderer.Render(
                            entry, program, classes,
                            onError = { problems.add(describe(it)) },
                            onPartialError = { if (it != null) problems.add(describe(it)) },
                        )
                    }
                    if (background != 0L) Box(Modifier.background(Color(background.toInt()))) { content() } else content()
                }
            }
        }
        val id = nextSession.getAndIncrement()
        sessions[id] = Session(
            scene = scene,
            problems = problems,
            width = clampedWidth,
            height = clampedHeight,
            density = density,
            fontScale = fontScale,
            dark = dark,
            background = background,
            renderer = renderer,
            entry = entry,
            program = program,
            classes = classes,
        )
        return id
    }

    /**
     * Resizes the active [id] session to new [width]x[height] pixels, recreating the [ImageComposeScene]
     * at current display constraints.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @JvmStatic
    fun resize(id: Int, width: Int, height: Int) {
        val session = sessions[id] ?: return
        val clampedWidth = width.coerceIn(1, 4096)
        val clampedHeight = height.coerceIn(1, 4096)
        synchronized(session) {
            if (session.width == clampedWidth && session.height == clampedHeight) return
            session.width = clampedWidth
            session.height = clampedHeight
            synchronized(renderLock) {
                try { session.scene.close() } catch (_: Throwable) {}
                session.scene = ImageComposeScene(clampedWidth, clampedHeight, Density(session.density, session.fontScale)) {
                    Themed(session.dark) {
                        val content = @Composable {
                            session.renderer.Render(
                                session.entry, session.program, session.classes,
                                onError = { session.problems.add(describe(it)) },
                                onPartialError = { if (it != null) session.problems.add(describe(it)) },
                            )
                        }
                        if (session.background != 0L) Box(Modifier.background(Color(session.background.toInt()))) { content() } else content()
                    }
                }
            }
        }
    }

    /**
     * The session's current frame, as `[png, problems, animating]`: the PNG, the problems reported since the
     * last frame (null when none), and whether the scene still has work pending (an animation, a state change
     * an effect made), in which case the host should ask for another frame soon.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @JvmStatic
    fun frame(id: Int): Array<Any?> {
        val session = sessions[id] ?: return arrayOf(null, "no preview session $id", false)
        synchronized(session) {
            val image = synchronized(renderLock) {
                try {
                    session.scene.render()
                } catch (t: Throwable) {
                    try {
                        session.scene.render()
                    } catch (t2: Throwable) {
                        session.problems.add(describe(t2))
                        return arrayOf(null, t2.message ?: "render failed", false)
                    }
                }
            }
            val png = image.encodeToData(EncodedImageFormat.PNG)?.bytes
                ?: return arrayOf(null, "failed to encode PNG", false)
            val fresh = session.problems.drop(session.reported).distinct()
            session.reported = session.problems.size
            return arrayOf(png, fresh.takeIf { it.isNotEmpty() }?.joinToString("\n"), session.scene.hasInvalidations())
        }
    }

    /**
     * Input to the session's scene: [kind] is 0 for a press, 1 for a move, 2 for a release, at ([x], [y]) in
     * the scene's pixels. Out-of-bounds / negative coordinates are clamped to session bounds.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @JvmStatic
    fun pointer(id: Int, kind: Int, x: Float, y: Float) {
        val session = sessions[id] ?: return
        if (x.isNaN() || y.isNaN() || x.isInfinite() || y.isInfinite()) return
        val clampedX = x.coerceIn(0f, session.width.toFloat().coerceAtLeast(0f))
        val clampedY = y.coerceIn(0f, session.height.toFloat().coerceAtLeast(0f))
        val type = when (kind) {
            0 -> PointerEventType.Press
            2 -> PointerEventType.Release
            else -> PointerEventType.Move
        }
        synchronized(session) {
            try {
                session.scene.sendPointerEvent(type, Offset(clampedX, clampedY), timeMillis = (System.nanoTime() - session.opened) / 1_000_000)
            } catch (_: Throwable) {}
        }
    }

    @JvmStatic
    fun close(id: Int) {
        val session = sessions.remove(id) ?: return
        synchronized(session) {
            synchronized(renderLock) {
                try { session.scene.close() } catch (_: Throwable) {}
            }
        }
    }

    private class Frame(val image: Image, val problems: List<String>)

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(
        blob: ByteArray,
        width: Int,
        height: Int,
        density: Float,
        fontScale: Float = 1f,
        dark: Boolean = false,
        background: Long = 0L,
    ): Frame {
        val clampedWidth = width.coerceIn(1, 4096)
        val clampedHeight = height.coerceIn(1, 4096)
        val (entry, program, classes) = PreviewWire.decode(blob)
        val renderer = ComposePreviewRenderer()
        val problems = ArrayList<String>()
        synchronized(renderLock) {
            val scene = ImageComposeScene(clampedWidth, clampedHeight, Density(density, fontScale)) {
                Themed(dark) {
                    val content = @Composable {
                        renderer.Render(
                            entry, program, classes,
                            onError = { problems.add(describe(it)) },
                            onPartialError = { if (it != null) problems.add(describe(it)) },
                        )
                    }
                    if (background != 0L) Box(Modifier.background(Color(background.toInt()))) { content() } else content()
                }
            }
            try {
                return Frame(scene.render(), problems.distinct())
            } finally {
                try { scene.close() } catch (_: Throwable) {}
            }
        }
    }

    @OptIn(InternalComposeUiApi::class)
    @Composable
    private fun Themed(dark: Boolean, content: @Composable () -> Unit) {
        val providers = ArrayList<ProvidedValue<*>>()
        providers.add(LocalSystemTheme provides if (dark) SystemTheme.Dark else SystemTheme.Light)
        provideLocalContextIfPresent(providers)
        CompositionLocalProvider(*providers.toTypedArray()) { content() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun provideLocalContextIfPresent(providers: ArrayList<ProvidedValue<*>>) {
        try {
            val localContextClass = Class.forName("androidx.compose.ui.platform.AndroidCompositionLocals_androidKt")
            val getLocalContextMethod = localContextClass.getMethod("getLocalContext")
            val localContext = getLocalContextMethod.invoke(null) as? ProvidableCompositionLocal<Any?> ?: return

            val contextClass = Class.forName("android.content.Context")
            val stubContext = Proxy.newProxyInstance(
                contextClass.classLoader ?: VmPreviewEntry::class.java.classLoader,
                arrayOf(contextClass),
            ) { proxy, method, args ->
                when (method.name) {
                    "getApplicationContext" -> proxy
                    "getPackageName" -> "dev.ide.guest"
                    "toString" -> "PreviewContext"
                    "hashCode" -> 42
                    "equals" -> args?.getOrNull(0) === proxy
                    else -> when (method.returnType) {
                        java.lang.Boolean.TYPE -> false
                        java.lang.Integer.TYPE -> 0
                        java.lang.Long.TYPE -> 0L
                        java.lang.Float.TYPE -> 0f
                        java.lang.Double.TYPE -> 0.0
                        String::class.java -> ""
                        else -> null
                    }
                }
            }
            providers.add(localContext.provides(stubContext))
        } catch (_: Throwable) {
            // AndroidCompositionLocals or android.content.Context not linked; safe fallback
        }
    }

    private fun describe(t: Throwable): String = buildString {
        append(t::class.simpleName ?: "Error")
        t.message?.let { append(": ").append(it) }
        var cause = t.cause
        while (cause != null && cause !== t) {
            append("\ncaused by ").append(cause::class.simpleName)
            cause.message?.let { append(": ").append(it) }
            cause = cause.cause
        }
    }
}
