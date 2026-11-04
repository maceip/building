//
//  SubtreeAnchors.swift
//  SwiftUICore
//
//  Re-entry points for subtree re-evaluation. During a pass the evaluator
//  records, at every path where state installs, the outermost view value of
//  that path's wrapper chain together with the context it was resolved with.
//  When a state write later dirties that path, the host re-resolves just the
//  recorded view — reproducing the node (including parent-applied modifiers,
//  which live on the same wrapper chain) without walking the rest of the tree.
//

/// The outermost view value + context of the wrapper chain currently being
/// unwrapped at one identity path. Captured at the first `resolve` entry of
/// the chain so that a re-resolution replays parent-applied modifiers and
/// resolution effects, not just the innermost view.
final class ChainAnchor {
    let view: any View
    let context: ResolveContext

    init(view: any View, context: ResolveContext) {
        self.view = view
        self.context = context
    }
}

/// Anchors recorded during evaluation, keyed by state-install path.
public final class AnchorStore {

    struct Anchor {
        /// Outermost view of the wrapper chain producing this path's node.
        var view: any View
        /// The context that chain was entered with.
        var context: ResolveContext
        /// The id of the node the chain produced — the splice target.
        var nodeID: String
    }

    private(set) var anchors: [String: Anchor] = [:]
    /// Paths of lazy containers. State under one belongs to a row that lives
    /// outside the main tree (fetched on demand), so it can't be patched in.
    private(set) var lazyBoundaries: Set<String> = []

    public init() {}

    func record(path: String, chain: ChainAnchor, nodeID: String) {
        anchors[path] = Anchor(view: chain.view, context: chain.context, nodeID: nodeID)
    }

    func anchor(at path: String) -> Anchor? {
        anchors[path]
    }

    /// Drops the anchors that produced these nodes — used when a group-spread
    /// modifier lands on them after the fact, which a re-resolution from the
    /// anchor couldn't reproduce. Their deeper descendants stay patchable.
    func invalidate<IDs: Collection<String>>(nodeIDs: IDs) {
        let ids = Set(nodeIDs)
        for (path, anchor) in anchors where ids.contains(anchor.nodeID) {
            anchors.removeValue(forKey: path)
        }
    }

    /// Marks `path` as a lazy container boundary.
    public func markLazyBoundary(_ path: String) {
        lazyBoundaries.insert(path)
    }

    /// Whether `path` is inside a lazy container's on-demand content.
    func isUnderLazyBoundary(_ path: String) -> Bool {
        lazyBoundaries.contains { path.hasPrefix($0 + "/") || path.hasPrefix($0 + ".") }
    }

    /// Clears everything ahead of a full pass; a subtree pass instead
    /// overwrites just the anchors it revisits.
    func reset() {
        anchors.removeAll(keepingCapacity: true)
        lazyBoundaries.removeAll(keepingCapacity: true)
    }
}
