package dev.ide.swiftpreview

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pureswift.bridge.BridgeExport
import com.pureswift.swiftandroid.AndroidBridgeHost
import com.pureswift.swiftui.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** End-to-end event-to-TreeStore timing on Android, not a frame-rate test.
 * Includes native evaluation, transport, decoding/materialization and splicing.
 * Both native programs use the same Swift release build and workload sources.
 */
@RunWith(AndroidJUnit4::class)
class BridgeBenchmark {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun nodes(root: ViewNode): List<ViewNode> = listOf(root) + root.children.flatMap(::nodes)

    @Test fun compareTransportsAndSubtrees() {
        val results = mutableListOf<JsonObject>()
        for (rows in listOf(10, 100, 1000)) {
            for (leaf in listOf(true, false)) {
                for (jni in listOf(false, true)) results += measure(rows, leaf, jni)
            }
        }
        val result = buildJsonObject {
            put("scope", "Android main-thread callback to TreeStore assignment; excludes Compose frames")
            put("swift", "release; same workload source for both paths")
            put("warmup", 15); put("samples", 50)
            put("results", JsonArray(results))
        }
        val file = File(instrumentation.targetContext.filesDir, "jni-benchmark.json")
        file.writeText(result.toString())
        Log.i("BuildingBenchmark", result.toString())
    }

    private fun measure(rows: Int, leaf: Boolean, jni: Boolean): JsonObject {
        val store = TreeStore()
        val runtime = SwiftRuntime()
        val sink = JextractCallbackSink()
        var baseline: JsonBaseline? = null
        var latch = CountDownLatch(1)
        var start = 0L
        var elapsed = 0L
        var kind = ""
        var payload: ViewNode? = null
        main {
            store.onMutation = { updateKind, node ->
                elapsed = System.nanoTime() - start
                kind = updateKind; payload = node; latch.countDown()
            }
            start = System.nanoTime()
            if (jni) {
                BridgeExport.bridgeSetHost(AndroidBridgeHost())
                BenchmarkRuntime().startBenchmark(store, rows, leaf)
            } else {
                baseline = JsonBaseline(rows, leaf) { store.update(it) }.also { it.start() }
            }
        }
        assertTrue("initial tree", latch.await(60, TimeUnit.SECONDS))
        val initialMs = elapsed / 1e6
        val fullNodes = nodes(store.root!!).size
        val times = mutableListOf<Double>()
        val sizes = mutableListOf<Int>()
        val kinds = mutableMapOf<String, Int>()
        var retained = 0
        try {
            for (iteration in 1..65) {
                latch = CountDownLatch(1)
                var before: ViewNode? = null
                main {
                    val buttons = nodes(store.root!!).filter { it.type == "Button" }
                    assertEquals(rows, buttons.size)
                    before = buttons.last()
                    val id = buttons[rows / 2].props.getValue("onTap").jsonPrimitive.long
                    start = System.nanoTime()
                    if (jni) sink.invokeVoid(id) else baseline!!.invoke(id)
                }
                assertTrue("callback $iteration/$rows/$leaf/$jni", latch.await(60, TimeUnit.SECONDS))
                val current = nodes(store.root!!)
                assertTrue("Swift state advanced", current.any { it.props["text"] == JsonPrimitive("row ${rows / 2}: $iteration") })
                if (iteration > 15) {
                    times += elapsed / 1e6
                    sizes += nodes(payload!!).size
                    kinds[kind] = (kinds[kind] ?: 0) + 1
                    if (current.last { it.type == "Button" } === before) retained++
                }
            }
            if (jni && leaf) {
                assertEquals("all updates are subtree patches", 50, kinds["patch"])
                assertEquals("untouched sibling identity", 50, retained)
                assertTrue("small transferred subtree", sizes.max() < fullNodes)
            }
        } finally {
            main { store.onMutation = null; if (jni) runtime.stop() else baseline!!.close() }
        }
        times.sort()
        return buildJsonObject {
            put("transport", if (jni) "typed-jni" else "json-process")
            put("state_owner", if (leaf) "leaf" else "root"); put("rows", rows)
            put("initial_tree_ms", initialMs); put("full_tree_nodes", fullNodes)
            put("p50_ms", times[24]); put("p95_ms", times[47]); put("max_ms", times.last())
            put("transported_nodes_min", sizes.min()); put("transported_nodes_max", sizes.max())
            put("updates", buildJsonObject { kinds.forEach { (k, v) -> put(k, v) } })
            put("unchanged_sibling_identity_preserved", retained)
        }.also { Log.i("BuildingBenchmark", it.toString()) }
    }

    @Test fun disposeCancelsPendingRenderAndAllowsReopen() {
        val runtime = SwiftRuntime()
        val store = TreeStore()
        var updates = 0
        main {
            BridgeExport.bridgeSetHost(AndroidBridgeHost())
            store.onMutation = { _, _ -> updates++ }
            BenchmarkRuntime().startBenchmark(store, 10, true)
            val id = nodes(store.root!!).first { it.type == "Button" }.props.getValue("onTap").jsonPrimitive.long
            JextractCallbackSink().invokeVoid(id) // queues a render on the main looper
            runtime.stop()
            assertFalse(runtime.isRunning())
        }
        instrumentation.waitForIdleSync()
        assertEquals("no queued render after dispose", 1, updates)
        main {
            BenchmarkRuntime().startBenchmark(store, 10, true)
            assertTrue(runtime.isRunning())
            assertTrue(nodes(store.root!!).any { it.props["text"] == JsonPrimitive("row 0: 0") })
            runtime.stop()
        }
    }

    // Previous JSON protocol, benchmark-only. No styling conversion is charged
    // to this baseline, so it is a conservative comparison with the old preview.
    private inner class JsonBaseline(val rows: Int, val leaf: Boolean, val onTree: (ViewNode) -> Unit) {
        private val handler = Handler(Looper.getMainLooper())
        private lateinit var process: Process
        @Volatile private var closed = false
        fun start() {
            val lib = instrumentation.targetContext.applicationInfo.nativeLibraryDir
            process = ProcessBuilder("$lib/libjson-baseline.so", rows.toString(), if (leaf) "leaf" else "root")
                .apply { environment()["LD_LIBRARY_PATH"] = lib }.start()
            Thread {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            val node = Json.decodeFromString<ViewNode>(line)
                            handler.post { if (!closed) onTree(node) }
                        }
                    }
                } catch (error: IOException) { if (!closed) throw error }
            }.start()
            Thread {
                try {
                    process.errorStream.bufferedReader().forEachLine { Log.e("JsonBaseline", it) }
                } catch (error: IOException) { if (!closed) throw error }
            }.start()
        }
        fun invoke(id: Long) {
            process.outputStream.apply { write("{\"id\":\"$id\",\"kind\":\"void\"}\n".toByteArray()); flush() }
        }
        fun close() { closed = true; process.destroy() }
    }
}
