//
//  Evaluator.swift
//  SwiftUICore
//
//  Walks a view value tree down to primitives, producing a RenderNode tree.
//  Adapted from ClassicUICore's Resolver: same iterative body unwrapping,
//  structural identity paths, and state re-linking — but emitting a general
//  node tree rather than iPod menu rows.
//

/// State threaded through a resolve pass: the identity path and the state
/// storage the current scope's `@State` properties reconnect to.
public struct ResolveContext {

    public let storage: StateStorage
    public let callbacks: CallbackRegistry
    public var environment: EnvironmentStorage
    /// Collects the `navigationTitle` of the screen currently being resolved.
    public var titleSink: TitleSink?
    /// Carries a `refreshable` action to the List it wraps.
    public var refreshSink: RefreshSink?
    /// Collects preferences published by the subtree currently being resolved.
    public var preferences: PreferenceCollector?
    public var path: String
    public var depth: Int
    /// Re-entry points recorded during the pass; `nil` disables recording
    /// (tests driving the evaluator directly don't need anchors).
    public var anchors: AnchorStore?
    /// Monotonic evaluation counter, stamped on lazy containers so their rows
    /// re-fetch when (and only when) the container was re-evaluated.
    public var passVersion: Int = 0
    /// The wrapper chain being unwrapped at the current path; cleared when the
    /// path descends. See `ChainAnchor`.
    var chainAnchor: ChainAnchor?

    public init(
        storage: StateStorage,
        callbacks: CallbackRegistry,
        environment: EnvironmentStorage = EnvironmentStorage(),
        titleSink: TitleSink? = nil,
        refreshSink: RefreshSink? = nil,
        preferences: PreferenceCollector? = nil,
        path: String = "",
        depth: Int = 0
    ) {
        self.storage = storage
        self.callbacks = callbacks
        self.environment = environment
        self.titleSink = titleSink
        self.refreshSink = refreshSink
        self.preferences = preferences
        self.path = path
        self.depth = depth
    }

    /// Registers a callback for the view at this context's identity path.
    /// Ids are stable per (path, registration order), so a re-evaluated view's
    /// handlers keep the same ids — see `CallbackRegistry`.
    public func registerCallback(_ callback: CallbackRegistry.Callback) -> Int64 {
        callbacks.register(callback, path: path)
    }

    /// Starts a wrapper chain at this path if one isn't already open: the
    /// captured view + context are the re-entry point for subtree patching.
    mutating func beginChainIfNeeded(_ view: any View) {
        guard anchors != nil, chainAnchor == nil else { return }
        // the captured context drops the store reference (the store holds
        // the capture — a cycle otherwise); the host restores it on re-entry
        var captured = self
        captured.anchors = nil
        chainAnchor = ChainAnchor(view: view, context: captured)
    }

    /// Context for a child at a structurally stable position.
    public func descending(_ component: String) -> ResolveContext {
        var context = self
        context.path += "/" + component
        context.depth += 1
        context.chainAnchor = nil
        return context
    }
}

/// Type-erased access to the environment-writer wrapper.
internal protocol _AnyEnvironmentWriter {
    var _object: AnyObject { get }
    var _content: any View { get }
}

extension _EnvironmentWriterView: _AnyEnvironmentWriter {
    var _object: AnyObject { object }
    var _content: any View { content }
}

// MARK: - Primitive / dispatch protocols

/// A view that resolves directly to a node (leaf or container).
public protocol PrimitiveView {
    func _render(in context: ResolveContext) -> RenderNode
}

/// A modifier wrapper: contributes a modifier to the node its content resolves
/// to, without introducing structural identity (modifiers are identity-transparent).
public protocol _ModifierProvider {
    var _modifierNode: ModifierNode { get }
    var _modifiedContent: any View { get }
}

/// A modifier provider that needs the resolve context to emit its node — e.g.
/// to register a callback. The evaluator prefers this over the context-free
/// `_modifierNode` property when a provider conforms to it.
public protocol _ContextualModifierProvider {
    func _modifierNode(in context: ResolveContext) -> ModifierNode
}

/// A modifier whose emitted node embeds a callback registered against the
/// resolve context (e.g. `onTapGesture`, `onAppear`, `onChange`).
public protocol _CallbackModifier {
    func _callbackNode(in context: ResolveContext) -> ModifierNode
}

extension _ModifierProvider {
    /// The emitted node, using the resolve context when the provider needs it.
    func resolvedModifierNode(in context: ResolveContext) -> ModifierNode {
        (self as? _ContextualModifierProvider)?._modifierNode(in: context) ?? _modifierNode
    }
}

/// A group that flattens into a sequence of sibling nodes (TupleView, ForEach,
/// conditionals, optionals).
public protocol _GroupView {
    func _flatten(into nodes: inout [RenderNode], context: ResolveContext)
}

/// A content wrapper with a side effect during resolution (registering a
/// navigation destination, recording a title, presenting a sheet). Returns the
/// content to continue resolving, and may mutate the context.
public protocol _ResolutionEffectView {
    func _applyEffect(_ context: inout ResolveContext) -> any View
}

/// Per-screen scratchpad collecting presentation attributes declared inside a
/// screen's body — its `navigationTitle` and a sheet's `presentationDetents`.
public final class TitleSink {
    public var title: String?
    public var detents: [PresentationDetent] = []
    /// A `searchable` declared inside the screen: current text, the string
    /// callback its field pushes to, and an optional placeholder prompt.
    public var searchText: String?
    public var searchCallbackID: Int64?
    public var searchPrompt: String?
    public init() {}

    /// Whether a subtree pass re-published the same values the last full pass
    /// left in `other` — the condition under which the pass can stay a patch
    /// (the enclosing screen's rendering of these values is already correct).
    func matches(_ other: TitleSink) -> Bool {
        title == other.title
            && detents == other.detents
            && searchText == other.searchText
            && searchCallbackID == other.searchCallbackID
            && searchPrompt == other.searchPrompt
    }
}

/// Carries a pending `refreshable` action to the enclosing List.
public final class RefreshSink {
    public var action: (@Sendable () async -> Void)?
    public init() {}
}

public enum Evaluator {

    /// Guards against a self-referential `body`.
    public static let maximumDepth = 1000

    /// Evaluates the `body` of a type-erased view.
    public static func body(of view: any View) -> any View {
        func open<V: View>(_ view: V) -> any View { view.body }
        return open(view)
    }

    /// Resolves a view to a single node, unwrapping modifiers, type erasure,
    /// and composite bodies (re-linking their `@State` on the way).
    public static func resolve(_ view: any View, _ context: ResolveContext) -> RenderNode {
        guard context.depth < maximumDepth else {
            assertionFailure("View \(type(of: view)) exceeded maximum resolve depth")
            return RenderNode(type: "EmptyView", id: context.path)
        }
        // First entry of a wrapper chain: remember the outermost view + context
        // so a dirty descendant can re-resolve from here — reproducing parent-
        // applied modifiers and effects, which sit on this same chain.
        var context = context
        context.beginChainIfNeeded(view)
        switch view {
        case is EmptyView:
            return RenderNode(type: "EmptyView", id: context.path)
        case let group as _GroupView:
            var nodes = [RenderNode]()
            group._flatten(into: &nodes, context: context)
            if nodes.isEmpty {
                return RenderNode(type: "EmptyView", id: context.path)
            } else if nodes.count == 1 {
                return nodes[0]
            } else {
                return RenderNode(type: "VStack", id: context.path, children: nodes)
            }
        case let modifier as _ModifierProvider:
            // identity-transparent: resolve content at the SAME path, then prepend
            var node = resolve(modifier._modifiedContent, context)
            node.modifiers.insert(modifier.resolvedModifierNode(in: context), at: 0)
            return node
        case let effect as _ResolutionEffectView:
            var context = context
            let content = effect._applyEffect(&context)
            return resolve(content, context)
        case let primitive as PrimitiveView:
            // primitives may hold container @State (navigation stack, tab
            // selection) and read @Environment, so wire both before rendering
            context.storage.install(in: view, path: context.path)
            EnvironmentInjector.inject(context.environment, into: view)
            let node = primitive._render(in: context)
            if let anchors = context.anchors, let chain = context.chainAnchor {
                anchors.record(path: context.path, chain: chain, nodeID: node.id)
            }
            return node
        case let anyView as AnyView:
            // identity-transparent wrappers stay on the chain: the anchor must
            // cover modifiers applied outside them
            var child = context.descending("any")
            child.chainAnchor = context.chainAnchor
            return resolve(anyView.storage, child)
        case let writer as _AnyEnvironmentWriter:
            var child = context.descending("env")
            child.chainAnchor = context.chainAnchor
            let persisted = context.storage.persistentObject(at: child.path) { writer._object }
            child.environment.set(persisted)
            return resolve(writer._content, child)
        default:
            let child = context.descending("\(type(of: view))")
            child.storage.install(in: view, path: child.path)
            EnvironmentInjector.inject(child.environment, into: view)
            let node = resolve(body(of: view), child)
            if let anchors = context.anchors, let chain = context.chainAnchor {
                anchors.record(path: child.path, chain: chain, nodeID: node.id)
            }
            return node
        }
    }

    /// Flattens a view into a sequence of sibling nodes. Groups (TupleView,
    /// ForEach, conditionals, optionals) expand; anything else is a single node.
    public static func resolveChildren(_ view: any View, _ context: ResolveContext) -> [RenderNode] {
        var nodes = [RenderNode]()
        flatten(view, into: &nodes, context: context)
        return nodes
    }

    static func flatten(_ view: any View, into nodes: inout [RenderNode], context: ResolveContext) {
        guard context.depth < maximumDepth else {
            assertionFailure("View \(type(of: view)) exceeded maximum flatten depth")
            return
        }
        var context = context
        context.beginChainIfNeeded(view)
        switch view {
        case is EmptyView:
            return
        case let group as _GroupView:
            group._flatten(into: &nodes, context: context)
        case let modifier as _ModifierProvider:
            let before = nodes.count
            flatten(modifier._modifiedContent, into: &nodes, context: context)
            // apply the modifier to each node the content produced
            let modifierNode = modifier.resolvedModifierNode(in: context)
            for index in before ..< nodes.count {
                nodes[index].modifiers.insert(modifierNode, at: 0)
            }
            // a modifier spread over a group's nodes can't be reproduced by
            // re-resolving any one of them — drop their re-entry anchors
            if nodes.count - before > 1 {
                context.anchors?.invalidate(nodeIDs: nodes[before...].map(\.id))
            }
        case let anyView as AnyView:
            // identity-transparent wrappers stay on the chain: the anchor must
            // cover modifiers applied outside them
            var child = context.descending("any")
            child.chainAnchor = context.chainAnchor
            flatten(anyView.storage, into: &nodes, context: child)
        case let writer as _AnyEnvironmentWriter:
            var child = context.descending("env")
            child.chainAnchor = context.chainAnchor
            let persisted = context.storage.persistentObject(at: child.path) { writer._object }
            child.environment.set(persisted)
            flatten(writer._content, into: &nodes, context: child)
        case let effect as _ResolutionEffectView:
            var context = context
            let content = effect._applyEffect(&context)
            flatten(content, into: &nodes, context: context)
        default:
            nodes.append(resolve(view, context))
        }
    }
}

// MARK: - Group conformances

extension TupleView: _GroupView {

    public func _flatten(into nodes: inout [RenderNode], context: ResolveContext) {
        let mirror = Mirror(reflecting: value)
        // A single-element TupleView isn't a tuple; Mirror reports the element.
        guard mirror.displayStyle == .tuple else {
            if let view = value as? any View {
                Evaluator.flatten(view, into: &nodes, context: context.descending("0"))
            }
            return
        }
        for (offset, child) in mirror.children.enumerated() {
            guard let view = child.value as? any View else { continue }
            Evaluator.flatten(view, into: &nodes, context: context.descending("\(offset)"))
        }
    }
}

extension _ConditionalContent: _GroupView {

    public func _flatten(into nodes: inout [RenderNode], context: ResolveContext) {
        // distinct branch components so flipping the condition resets state
        switch storage {
        case .trueContent(let view):
            Evaluator.flatten(view, into: &nodes, context: context.descending("true"))
        case .falseContent(let view):
            Evaluator.flatten(view, into: &nodes, context: context.descending("false"))
        }
    }
}

extension Optional: _GroupView where Wrapped: View {

    public func _flatten(into nodes: inout [RenderNode], context: ResolveContext) {
        guard let view = self else { return }
        Evaluator.flatten(view, into: &nodes, context: context.descending("some"))
    }
}
