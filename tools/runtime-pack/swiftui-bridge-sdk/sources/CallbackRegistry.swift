//
//  CallbackRegistry.swift
//  SwiftUICore
//
//  Event closures can't cross the JNI boundary; nodes carry integer ids into
//  this table instead, and the fixed Kotlin→Swift dispatcher looks them up.
//
//  Ids are STABLE: derived from the registering view's identity path plus the
//  ordinal of the registration within that path, so the same handler keeps the
//  same id across every evaluation. That's what lets a node be reused without
//  re-materialization (subtree patching) keep a valid callback — a
//  generation-counter id would be evicted out from under it. Re-registering at
//  a path overwrites the closure in place, so the freshest capture always wins.
//

public final class CallbackRegistry {

    public enum Callback {
        case void(() -> Void)
        case bool((Bool) -> Void)
        case double((Double) -> Void)
        case int((Int) -> Void)
        case string((String) -> Void)
        /// A lazy row provider: given an index, returns that row's subtree.
        /// Must be a pure read — it runs during Compose composition.
        case item((Int) -> RenderNode)
    }

    private var callbacks: [Int64: Callback] = [:]
    /// Per-path registration counter for the pass in progress; gives each
    /// callback at a path a stable ordinal (0, 1, 2, …) in registration order.
    private var ordinals: [String: Int] = [:]

    public init() {}

    /// Begins a fresh evaluation pass. Only the ordinal counters reset — the
    /// callback table persists, since ids are stable and a re-registration just
    /// overwrites its slot. (Lazy rows register outside the pass, during Compose
    /// composition, so entries are never evicted here.)
    public func beginPass() {
        ordinals.removeAll(keepingCapacity: true)
    }

    /// Registers a callback for the view at `path`, returning a stable id.
    public func register(_ callback: Callback, path: String) -> Int64 {
        let ordinal = ordinals[path, default: 0]
        ordinals[path] = ordinal + 1
        let id = Self.stableID(path: path, ordinal: ordinal)
        callbacks[id] = callback
        return id
    }

    /// Looks up a callback by id.
    public func callback(for id: Int64) -> Callback? {
        callbacks[id]
    }

    /// A stable 63-bit id for `path#ordinal` (FNV-1a). Collisions across a view
    /// tree are astronomically unlikely.
    private static func stableID(path: String, ordinal: Int) -> Int64 {
        var hash: UInt64 = 0xcbf2_9ce4_8422_2325
        func mix(_ byte: UInt8) { hash = (hash ^ UInt64(byte)) &* 0x0000_0100_0000_01b3 }
        for byte in path.utf8 { mix(byte) }
        mix(0x23) // '#'
        var bits = UInt(bitPattern: ordinal)
        repeat { mix(UInt8(bits & 0xff)); bits >>= 8 } while bits != 0
        return Int64(hash & 0x7fff_ffff_ffff_ffff)
    }

    // Typed dispatch entry points, matching the fixed Kotlin surface.

    public func invokeVoid(_ id: Int64) {
        if case .void(let action)? = callbacks[id] { action() }
    }

    public func invokeBool(_ id: Int64, _ value: Bool) {
        if case .bool(let action)? = callbacks[id] { action(value) }
    }

    public func invokeDouble(_ id: Int64, _ value: Double) {
        if case .double(let action)? = callbacks[id] { action(value) }
    }

    public func invokeInt(_ id: Int64, _ value: Int) {
        if case .int(let action)? = callbacks[id] { action(value) }
    }

    public func invokeString(_ id: Int64, _ value: String) {
        if case .string(let action)? = callbacks[id] { action(value) }
    }

    /// Resolves a lazy row on demand.
    public func item(_ id: Int64, _ index: Int) -> RenderNode? {
        if case .item(let provider)? = callbacks[id] { return provider(index) }
        return nil
    }
}
