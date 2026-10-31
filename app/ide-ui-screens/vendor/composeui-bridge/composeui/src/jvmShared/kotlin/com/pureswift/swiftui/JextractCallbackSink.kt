package com.pureswift.swiftui

import com.pureswift.bridge.BridgeExport

// Routes interpreter events into Swift through the jextract-generated
// `BridgeExport` bindings — plain Java→Swift static calls, no hand-matched JNI
// symbols. Only `itemNode` still crosses through a hand-written external
// (`SwiftCallbackSink`), because it returns a JavaKit-wrapped `ViewNode` that
// jextract cannot express.
class JextractCallbackSink(
    private val items: SwiftCallbackSink = SwiftCallbackSink(),
) : CallbackSink {

    override fun invokeVoid(id: Long) = BridgeExport.bridgeInvokeVoid(id)

    override fun invokeBool(id: Long, value: Boolean) = BridgeExport.bridgeInvokeBool(id, value)

    override fun invokeDouble(id: Long, value: Double) = BridgeExport.bridgeInvokeDouble(id, value)

    override fun invokeInt(id: Long, value: Int) = BridgeExport.bridgeInvokeInt(id, value)

    override fun invokeString(id: Long, value: String) = BridgeExport.bridgeInvokeString(id, value)

    override fun itemNode(id: Long, index: Int): ViewNode? = items.itemNode(id, index)
}
