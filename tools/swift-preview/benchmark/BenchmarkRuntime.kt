package dev.ide.swiftpreview

import com.pureswift.swiftui.TreeStore

// Included in the APK only by preview.py --benchmark, alongside its native
// entry point. Keeping the class with the app also keeps JNI in one classloader.
class BenchmarkRuntime {
    external fun startBenchmark(store: TreeStore, rows: Int, leaf: Boolean)
}
