public enum CupertinoStackAnimation: String, CaseIterable, Sendable { case predictive, stack, none }

/// Real Decompose child identity is the entry ID. The owning Swift state handles
/// onBack and supplies the resulting entry stack; rendering never mutates it.
public struct CupertinoNativeChildren: View {
    let component: CupertinoComponent
    public init<Content: View>(entries: [CupertinoNavigationEntry], animation: CupertinoStackAnimation = .predictive,
                animationDurationMillis: Int = 300, onBack: @escaping () -> Void,
                @ViewBuilder content: (CupertinoNavigationEntry) -> Content) {
        precondition(!entries.isEmpty && Set(entries.map(\.id)).count == entries.count)
        precondition(animationDurationMillis >= 0)
        component = CupertinoComponent("NativeChildren", props: ["entries": .string(cupertinoJSON(entries)),
            "animation": .string(animation.rawValue), "animationDurationMillis": .int(animationDurationMillis)],
            actions: ["onBack": .void { if entries.count > 1 { onBack() } }],
            slots: entries.map { entry in CupertinoSlot("entry_" + entry.id) { content(entry) } })
    }
    public var body: some View { component }
}
