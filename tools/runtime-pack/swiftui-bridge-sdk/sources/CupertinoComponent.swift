import Foundation

/// A named upstream content parameter. Names, rather than positions, preserve
/// child identity when optional leading/trailing/header slots appear or vanish.
public struct CupertinoSlot: View, PrimitiveView {
    public let name: String
    let content: AnyView
    public init<Content: View>(_ name: String, @ViewBuilder content: () -> Content) {
        precondition(!name.isEmpty && !name.contains("/"), "A slot needs a stable parameter name")
        self.name = name; self.content = AnyView(content())
    }
    public typealias Body = Never
    public func _render(in context: ResolveContext) -> RenderNode {
        RenderNode(type: "CupertinoSlot", id: context.path, props: ["name": .string(name)],
                   children: Evaluator.resolveChildren(content, context.descending("content")))
    }
}

/// Escape hatch for upstream options and custom slots. The typed wrappers below
/// use this same contract; unknown names remain visible renderer diagnostics.
public struct CupertinoComponent: View, PrimitiveView {
    public let name: String
    public var props: [String: PropValue]
    public var actions: [String: ComposableAction]
    public var slots: [CupertinoSlot]
    public init(_ name: String, props: [String: PropValue] = [:],
                actions: [String: ComposableAction] = [:], slots: [CupertinoSlot] = []) {
        precondition(!name.isEmpty)
        precondition(Set(slots.map(\.name)).count == slots.count, "Duplicate Cupertino slot name")
        precondition(!actions.keys.contains { $0.isEmpty || $0.contains("/") })
        self.name = name; self.props = props; self.actions = actions; self.slots = slots
    }
    public typealias Body = Never
    public func _render(in context: ResolveContext) -> RenderNode {
        var values = props
        values["name"] = .string(name)
        for key in actions.keys.sorted() {
            values[key] = .int(Int(actions[key]!.register(in: context.callbacks,
                                                        path: context.path + "/action/" + key)))
        }
        return RenderNode(type: "Composable", id: context.path, props: values,
                          children: slots.map { $0._render(in: context.descending("slot/" + $0.name)) })
    }
}

public enum CupertinoPlatformCapability: Equatable, Sendable {
    case shared
    case unsupported(String)
}

/// Non-iOS hosts must not pretend these are backed by UIKit.
public enum CupertinoUIKitSurface: String, CaseIterable, Sendable {
    case colorPicker = "CupertinoColorPickerNative"
    case picker = "CupertinoPickerNative"
    case children = "UIKitChildren"
    case systemSymbol = "CupertinoIcons.named"
    public var capability: CupertinoPlatformCapability {
        .unsupported("This upstream surface is available only through UIKit; the Android and desktop bridge has no UIKit implementation.")
    }
}

func cupertinoJSON<Value: Encodable>(_ value: Value) -> String {
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.sortedKeys]
    // All public event encoders validate their finite numeric values on construction.
    guard let bytes = try? encoder.encode(value), let text = String(data: bytes, encoding: .utf8) else {
        preconditionFailure("Invalid Cupertino JSON value")
    }
    return text
}

func cupertinoDecode<Value: Decodable>(_ type: Value.Type, _ text: String) -> Value? {
    guard text.utf8.count <= 262_144 else { return nil }
    return try? JSONDecoder().decode(type, from: Data(text.utf8))
}
