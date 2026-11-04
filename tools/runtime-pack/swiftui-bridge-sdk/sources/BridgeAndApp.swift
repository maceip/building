import Foundation
// import SwiftUICore
#if canImport(Android)
import Android
#else
import Darwin
#endif

// One process owns one view host. Evaluation and callbacks stay on this thread.
// stdout is reserved for newline-delimited JSON; app print() uses stderr.
public func runPreview(_ makeRoot: () -> any View) throws {
    let protocolOutput = FileHandle(fileDescriptor: dup(STDOUT_FILENO), closeOnDealloc: true)
    dup2(STDERR_FILENO, STDOUT_FILENO)
    let host = ViewHost(makeRoot())
    func emit() throws {
        let node = host.evaluate()
        let bytes = try JSONSerialization.data(withJSONObject: encode(node), options: [.sortedKeys])
        try protocolOutput.write(contentsOf: bytes + Data([10]))
    }
    try emit()
    while let line = readLine() {
        guard line.utf8.count <= 262_144,
              let event = try JSONSerialization.jsonObject(with: Data(line.utf8)) as? [String: Any],
              let idString = event["id"] as? String, let id = Int64(idString),
              let kind = event["kind"] as? String,
              let callback = host.callbacks.callback(for: id) else {
            throw BridgeError.invalidEvent
        }
        switch (kind, callback) {
        case ("void", .void(let action)): action()
        case ("bool", .bool(let action)):
            guard let value = event["value"] as? Bool else { throw BridgeError.invalidEvent }; action(value)
        case ("string", .string(let action)):
            guard let value = event["value"] as? String else { throw BridgeError.invalidEvent }; action(value)
        case ("double", .double(let action)):
            guard let value = event["value"] as? Double, value.isFinite else { throw BridgeError.invalidEvent }; action(value)
        case ("int", .int(let action)):
            guard let value = event["value"] as? Int else { throw BridgeError.invalidEvent }; action(value)
        default: throw BridgeError.invalidEvent
        }
        try emit()
    }
}

private enum BridgeError: Error { case invalidEvent, unsupportedLazyContainer }

private func encode(_ value: PropValue) -> Any {
    switch value {
    case .string(let v): return v
    case .double(let v): return v
    case .bool(let v): return v
    case .int(let v): return v
    case .array(let v): return v.map(encode)
    }
}

private func encode(_ node: RenderNode) throws -> [String: Any] {
    // Lazy providers require synchronous callbacks during composition. This
    // first transport deliberately rejects them instead of showing empty rows.
    guard node.count == nil else { throw BridgeError.unsupportedLazyContainer }
    return ["type": node.type, "id": node.id,
            "props": node.props.mapValues(encode),
            "modifiers": node.modifiers.map { ["kind": $0.kind, "args": $0.args.mapValues(encode)] as [String: Any] },
            "children": try node.children.map(encode)]
}
// A source compatibility module: app sources keep `import SwiftUI`.
// This exports the open-source evaluator, not Apple's framework.
// @_exported import SwiftUICore

public protocol PreviewProvider {
    associatedtype Previews: View
    @ViewBuilder static var previews: Previews { get }
}

public protocol Scene {}
public protocol _WindowGroupProtocol {
    func _eraseRoot() -> AnyView
}
public struct WindowGroup<Content: View>: Scene, _WindowGroupProtocol {
    public let content: Content
    public init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }
    public init(_ title: String, @ViewBuilder content: () -> Content) {
        self.content = content()
    }
    public func _eraseRoot() -> AnyView {
        AnyView(content)
    }
}
@resultBuilder
public struct SceneBuilder {
    public static func buildBlock<S: Scene>(_ scene: S) -> S { scene }
    public static func buildExpression<S: Scene>(_ scene: S) -> S { scene }
}

public protocol App {
    associatedtype Body: Scene
    init()
    @SceneBuilder var body: Body { get }
}
extension App {
    public static func main() throws {
        let app = Self()
        fputs("[SwiftUI Bridge] Cupertino ViewHost initialized — streaming live ViewNode tree\n", stderr)
        if let wg = app.body as? any _WindowGroupProtocol {
            try runPreview { wg._eraseRoot() }
        }
    }
}
