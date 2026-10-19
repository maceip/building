import SwiftUI
import ComposeUI
import BridgeExport
import SwiftJava

// Same typed SwiftJava bootstrap used by AndroidSwiftUI's desktop host.
// All tree materialization, callbacks and subtree selection remain upstream.
@JavaClass("dev.ide.swiftpreview.SwiftRuntime")
open class SwiftRuntime: JavaObject {}

@JavaImplementation("dev.ide.swiftpreview.SwiftRuntime")
extension SwiftRuntime {
    @JavaMethod
    func start(_ store: TreeStore?) {
        guard let store else { return }
        BridgeRuntime.current?.stop()
        BridgeRuntime(root: makePreviewRoot(), store: store, scheduler: bridgeScheduleMain).start()
    }

    @JavaMethod
    func stop() { BridgeRuntime.current?.stop() }

    @JavaMethod
    func isRunning() -> Bool { BridgeRuntime.current != nil }
}

// The standalone preview drives lifecycle through start/stop, like the
// upstream desktop rig, rather than the SwiftKey Application subclass.
@_cdecl("swiftui_applicationCreated") func swiftui_applicationCreated() {}
@_cdecl("swiftui_applicationTerminated") func swiftui_applicationTerminated() {}
@_cdecl("swiftui_activityCreated") func swiftui_activityCreated() {}
@_cdecl("swiftui_activityResult") func swiftui_activityResult(_ request: Int32, _ result: Int32) {}
