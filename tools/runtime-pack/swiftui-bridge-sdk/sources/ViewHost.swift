//
//  ViewHost.swift
//  SwiftUICore
//
//  Owns a root view's state storage and callback registry, produces the
//  current RenderNode tree, and re-evaluates when state changes. Platform-
//  agnostic: the Android bridge and the desktop test rig both drive it the
//  same way; the tests drive it directly.
//
//  State writes carry the identity path of the owning view, and evaluation
//  records a re-entry anchor at every such path — so a coalesced update whose
//  dirt is confined to one path re-resolves just that subtree and hands the
//  bridge a patch instead of a full root.
//

#if canImport(Observation)
import Observation
#endif

/// What one update pass produced: a whole tree, or a subtree replacing the
/// node whose id is `target`.
public enum TreeUpdate {
    case full(RenderNode)
    case patch(target: String, node: RenderNode)
}

// Main-thread confined by contract (evaluation, callbacks, and state writes
// all happen on the platform main thread); @unchecked so the observation
// change handler — which is @Sendable — can reach onStateChange.
public final class ViewHost: @unchecked Sendable {

    private let root: any View
    private let storage: StateStorage
    public let callbacks = CallbackRegistry()
    private let anchors = AnchorStore()

    /// Called after a state write triggers a re-evaluation. The platform layer
    /// wires this to schedule an `evaluate()` on the main looper and push the
    /// result across the bridge; tests read `evaluate()` directly instead.
    public var onStateChange: (() -> Void)?

    /// The animation captured from the transaction at write time, carried to
    /// the (coalesced) evaluation the write scheduled.
    private var pendingAnimation: Animation?

    /// Identity paths dirtied by state writes since the last pass.
    private var dirtyPaths: Set<String> = []
    /// Set when only a full pass can be trusted: first render, an @Observable
    /// mutation (no owning path), or a failed patch attempt.
    private var needsFullPass = true
    /// Bumped per pass; lazy containers stamp it so rows re-fetch when (and
    /// only when) their container was re-evaluated.
    private var passVersion = 0

    public init(_ root: any View, reflector: StateReflector = MirrorStateReflector()) {
        self.root = root
        self.storage = StateStorage(reflector: reflector)
        self.storage.onChange = { [weak self] path in
            if let animation = Transaction._current {
                self?.pendingAnimation = animation
            }
            self?.dirtyPaths.insert(path)
            self?.onStateChange?()
        }
    }

    /// Resolves the current view tree to a full node tree. The bridge uses
    /// `evaluateUpdate()` instead to get patches; tests and the fallback path
    /// use this directly.
    public func evaluate() -> RenderNode {
        dirtyPaths.removeAll()
        needsFullPass = false
        callbacks.beginPass()
        anchors.reset()
        passVersion += 1
        var context = ResolveContext(storage: storage, callbacks: callbacks, path: "root")
        context.anchors = anchors
        context.passVersion = passVersion
        var node = tracked { Evaluator.resolve(root, context) }
        stampAnimation(on: &node)
        return node
    }

    /// Produces the smallest update the dirt allows: when every write since
    /// the last pass landed at one anchored path (and not inside a lazy
    /// container's on-demand rows), re-resolves that subtree alone.
    public func evaluateUpdate() -> TreeUpdate {
        let dirty = dirtyPaths
        dirtyPaths.removeAll()
        if !needsFullPass,
           dirty.count == 1,
           let path = dirty.first,
           !anchors.isUnderLazyBoundary(path),
           let anchor = anchors.anchor(at: path),
           let node = resolveSubtree(anchor) {
            var node = node
            stampAnimation(on: &node)
            // the subtree's node id can change (a branch switch); splice by
            // the id recorded when the tree last contained it
            let target = anchor.nodeID
            anchors.record(
                path: path,
                chain: ChainAnchor(view: anchor.view, context: anchor.context),
                nodeID: node.id
            )
            return .patch(target: target, node: node)
        }
        return .full(evaluate())
    }

    /// Re-resolves one anchored subtree. Returns `nil` when the subtree
    /// published data an ancestor renders (a changed title, search state, or
    /// any preference) — those need a full pass to propagate.
    private func resolveSubtree(_ anchor: AnchorStore.Anchor) -> RenderNode? {
        callbacks.beginPass()
        passVersion += 1
        var context = anchor.context
        context.anchors = anchors
        context.passVersion = passVersion
        context.chainAnchor = nil
        // scope ancestor-visible sinks so changes are detected, not lost
        let previousSink = context.titleSink
        let scopedSink = previousSink.map { _ in TitleSink() }
        context.titleSink = scopedSink
        let scopedPreferences = context.preferences != nil ? PreferenceCollector() : nil
        context.preferences = scopedPreferences
        let node = tracked { Evaluator.resolve(anchor.view, context) }
        if let scopedSink, let previousSink, !scopedSink.matches(previousSink) {
            needsFullPass = true
            return nil
        }
        if let scopedPreferences, scopedPreferences.hasRecords {
            needsFullPass = true
            return nil
        }
        return node
    }

    private func tracked(_ body: () -> RenderNode) -> RenderNode {
        #if canImport(Observation)
        // Track @Observable reads during evaluation: a later mutation of any
        // observed property schedules a re-evaluation, exactly like a @State
        // write — but with no owning path, so only a full pass is safe.
        // Re-arms itself because the change handler triggers evaluation.
        withObservationTracking(body) { [weak self] in
            if let animation = Transaction._current {
                self?.pendingAnimation = animation
            }
            self?.needsFullPass = true
            self?.onStateChange?()
        }
        #else
        body()
        #endif
    }

    /// A tree produced by a withAnimation write carries the animation on its
    /// root (or patch root); the interpreter eases changed modifier values.
    private func stampAnimation(on node: inout RenderNode) {
        guard let animation = pendingAnimation else { return }
        pendingAnimation = nil
        node.props["animationCurve"] = .string(animation.curve)
        node.props["animationDurationMs"] = .double(animation.duration * 1000)
    }
}
