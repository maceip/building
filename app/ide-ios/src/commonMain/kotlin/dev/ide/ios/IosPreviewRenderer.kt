package dev.ide.ios

import dev.ide.vm.ClassPath
import dev.ide.vm.Vm
import dev.ide.vm.skiko.SkikoNativeBindings

/**
 * Renders a lowered `@Preview` on iOS.
 *
 * There is no JVM here and no way to load the project's libraries as code, so the desktop preview pipeline
 * (the tree interpreter, its Compose bridge, the renderer) runs inside `:jvm-vm` together with the
 * project's own Compose, read from [classPath]: the interpreter's JVM jars and the project's resolved
 * desktop artifacts. Only skia is native. One VM serves every render for a class path, so classes are
 * decoded once and later renders are warm.
 */
internal data class IosMainExecutionResult(
    val output: String,
    val returnValue: String?,
    val error: String?,
)

internal class IosPreviewRenderer(private val classPath: List<String>) {
    private var stdoutListener: ((String) -> Unit)? = null

    private val vm: Vm by lazy {
        Vm(
            ClassPath(classPath),
            stdout = { chunk ->
                val listener = stdoutListener
                if (listener != null) listener(chunk) else print(chunk)
            },
            bindings = SkikoNativeBindings(),
            // skiko's JVM build reads the OS to pick its defaults; a Mac is the closest to what draws here.
            systemProperties = mapOf("os.name" to "Mac OS X", "os.arch" to "aarch64"),
        )
    }

    /**
     * Executes a lowered `fun main` entry point in [blob] inside `:jvm-vm`, streaming `println`/`print`
     * output to [onStdout] and returning [IosMainExecutionResult].
     */
    fun executeMain(blob: ByteArray, onStdout: (String) -> Unit = {}): IosMainExecutionResult {
        val captured = StringBuilder()
        val prev = stdoutListener
        stdoutListener = { chunk ->
            captured.append(chunk)
            onStdout(chunk)
        }
        return try {
            val result = vm.invokeStatic(ENTRY, "executeMain", "([B)[Ljava/lang/Object;", blob) as dev.ide.vm.VmRefArray
            vm.runPendingTasks()
            IosMainExecutionResult(
                output = captured.toString(),
                returnValue = result.data[0] as String?,
                error = result.data[1] as String?,
            )
        } finally {
            stdoutListener = prev
        }
    }

    /** The preview in [blob] (a `PreviewWire` encoding) rendered at [width]x[height] pixels, as a PNG. */
    fun renderPng(blob: ByteArray, width: Int, height: Int, density: Float): ByteArray {
        val png = vm.invokeStatic(ENTRY, "renderPng", "([BIIF)[B", blob, width, height, density) as ByteArray
        vm.runPendingTasks()
        return png
    }

    /**
     * The preview in [blob] rendered under the `@Preview` options: the PNG, and the problems the render
     * reported (a preview that fails part way still draws what it could).
     */
    fun renderFrame(blob: ByteArray, width: Int, height: Int, density: Float, fontScale: Float, dark: Boolean, background: Long): IosPreviewFrame {
        val result = vm.invokeStatic(
            ENTRY, "renderFrame", "([BIIFFZJ)[Ljava/lang/Object;", blob, width, height, density, fontScale, dark, background,
        ) as dev.ide.vm.VmRefArray
        vm.runPendingTasks()
        val problems = (result.data[1] as String?)?.lines()?.filter { it.isNotBlank() } ?: emptyList()
        return IosPreviewFrame(result.data[0] as ByteArray, problems)
    }

    /** Opens a live session (see `VmPreviewEntry.open`) and returns its id. */
    fun open(blob: ByteArray, width: Int, height: Int, density: Float, fontScale: Float, dark: Boolean, background: Long): Int =
        vm.invokeStatic(ENTRY, "open", "([BIIFFZJ)I", blob, width, height, density, fontScale, dark, background) as Int

    /** The session's current frame; queued work (effects, timers) runs first, as an event loop would. */
    fun frame(session: Int): IosPreviewFrame {
        vm.runPendingTasks()
        val result = vm.invokeStatic(ENTRY, "frame", "(I)[Ljava/lang/Object;", session) as dev.ide.vm.VmRefArray
        val problems = (result.data[1] as String?)?.lines()?.filter { it.isNotBlank() } ?: emptyList()
        return IosPreviewFrame(result.data[0] as ByteArray?, problems, result.data[2] as Boolean || vm.nextTimerDelayMillis != null)
    }

    /** A touch on the session's scene: [kind] 0 press, 1 move, 2 release, at pixel ([x], [y]). */
    fun pointer(session: Int, kind: Int, x: Float, y: Float) {
        vm.invokeStatic(ENTRY, "pointer", "(IIFF)V", session, kind, x, y)
    }

    /** Resizes the session's scene to [width]x[height] pixels. */
    fun resize(session: Int, width: Int, height: Int) {
        vm.invokeStatic(ENTRY, "resize", "(III)V", session, width, height)
    }

    fun close(session: Int) {
        vm.invokeStatic(ENTRY, "close", "(I)V", session)
    }

    /** `WxH inked=N`: what a render drew, for a check that does not depend on exact pixels. */
    fun renderSummary(blob: ByteArray, width: Int, height: Int, density: Float): String =
        vm.invokeStatic(ENTRY, "renderSummary", "([BIIF)Ljava/lang/String;", blob, width, height, density) as String

    /** Interpreted instructions so far, and classes linked. */
    val steps: Long get() = vm.steps
    val loadedClasses: Int get() = vm.loadedClassCount

    private companion object {
        const val ENTRY = "dev/ide/interp/compose/VmPreviewEntry"
    }
}
