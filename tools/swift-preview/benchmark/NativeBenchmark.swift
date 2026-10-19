import SwiftUI
import ComposeUI
import BridgeExport
import SwiftJava

@JavaClass("dev.ide.swiftpreview.BenchmarkRuntime")
open class BenchmarkRuntime: JavaObject {}

@JavaImplementation("dev.ide.swiftpreview.BenchmarkRuntime")
extension BenchmarkRuntime {
    @JavaMethod
    func startBenchmark(_ store: TreeStore?, _ rows: Int32, _ leaf: Bool) {
        guard let store else { return }
        BridgeRuntime.current?.stop()
        BridgeRuntime(root: benchmarkRoot(Int(rows), leaf), store: store,
                      scheduler: bridgeScheduleMain).start()
    }
}
