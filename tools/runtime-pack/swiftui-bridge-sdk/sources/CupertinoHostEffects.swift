/// System-bar icon appearance is applied while this view is composed and restored
/// when it leaves. Desktop has no system bars, so content remains unchanged.
public struct CupertinoSystemBarAppearance: View {
    let component: CupertinoComponent
    public init<Content: View>(dark: Bool, @ViewBuilder content: () -> Content) {
        component = CupertinoComponent("SystemBarAppearance", props: ["dark": .bool(dark)],
            slots: [CupertinoSlot("content", content: content)])
    }
    public var body: some View { component }
}

/// These are the two feedback types available in upstream's non-UIKit provider.
/// UIKit-specific feedback names are not silently substituted on Android.
public enum CupertinoHapticType: String, CaseIterable, Sendable {
    case longPress = "LongPress", textHandleMove = "TextHandleMove"
}

/// A changed nonempty commandID requests feedback once. Constructing the provider
/// without a command does not perform feedback. onPerformed is a host dispatch
/// acknowledgement, not proof that hardware produced a sensation.
public struct CupertinoHapticFeedback: View {
    let component: CupertinoComponent
    public init<Content: View>(type: CupertinoHapticType? = nil, commandID: String? = nil,
                enabled: Bool = true, onPerformed: @escaping () -> Void = {}, @ViewBuilder content: () -> Content) {
        precondition((type == nil) == (commandID == nil))
        precondition(commandID == nil || !commandID!.isEmpty)
        var props: [String: PropValue] = ["enabled": .bool(enabled)]
        if let type, let commandID { props["type"] = .string(type.rawValue); props["commandID"] = .string(commandID) }
        component = CupertinoComponent("rememberCupertinoHapticFeedback", props: props,
            actions: ["onPerformed": .void { if enabled { onPerformed() } }],
            slots: [CupertinoSlot("content", content: content)])
    }
    public var body: some View { component }
}
