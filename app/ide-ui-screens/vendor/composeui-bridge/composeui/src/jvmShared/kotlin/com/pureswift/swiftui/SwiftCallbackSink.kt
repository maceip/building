package com.pureswift.swiftui

// The one remaining hand-matched Kotlin→Swift external: a lazy-row query that
// returns a materialized `ViewNode` subtree. jextract can't express it (an
// exported Swift function can't return a JavaKit-wrapped type), so this stays a
// hand-written external whose JNI symbol matches the Swift @JavaImplementation
// in SwiftCallbackSink.swift. The five scalar event callbacks moved to the
// generated `BridgeExport` bindings — see JextractCallbackSink.
class SwiftCallbackSink {

    external fun itemNode(id: Long, index: Int): ViewNode?
}
