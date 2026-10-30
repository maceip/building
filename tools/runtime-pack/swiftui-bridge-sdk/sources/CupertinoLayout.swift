public enum CupertinoColorRole: String, CaseIterable, Sendable {
    case accent
    case label
    case secondaryLabel
    case tertiaryLabel
    case quaternaryLabel
    case systemFill
    case secondarySystemFill
    case tertiarySystemFill
    case quaternarySystemFill
    case placeholderText
    case separator
    case opaqueSeparator
    case link
    case systemGroupedBackground
    case secondarySystemGroupedBackground
    case tertiarySystemGroupedBackground
    case systemBackground
    case secondarySystemBackground
    case tertiarySystemBackground
}
public enum CupertinoShapeSize: String, CaseIterable, Sendable { case extraSmall, small, medium, large, extraLarge }
public struct CupertinoThemeOptions: Sendable {
    public var target: CupertinoTarget
    public var isDark: Bool?
    public var colors: [CupertinoColorRole: Color]
    public var shapes: [CupertinoShapeSize: Double]
    public var fontFamily: String?
    public init(target: CupertinoTarget = .cupertino, isDark: Bool? = nil,
                colors: [CupertinoColorRole: Color] = [:], shapes: [CupertinoShapeSize: Double] = [:], fontFamily: String? = nil) {
        precondition(shapes.values.allSatisfy { $0.isFinite && $0 >= 0 })
        self.target = target; self.isDark = isDark; self.colors = colors; self.shapes = shapes; self.fontFamily = fontFamily
    }
    var props: [String: PropValue] {
        var p: [String: PropValue] = ["target": .string(target.rawValue)]
        if let isDark { p["isDark"] = .bool(isDark) }
        if let fontFamily { p["fontFamily"] = .string(fontFamily) }
        for (key, color) in colors { p[key.rawValue] = .color(color) }
        for (key, radius) in shapes { p[key.rawValue] = .double(radius) }
        return p
    }
}

public struct CupertinoTheme: View {
    let component: CupertinoComponent
    public init<Content: View>(_ options: CupertinoThemeOptions = .init(), @ViewBuilder content: () -> Content) {
        component = CupertinoComponent("CupertinoTheme", props: options.props, slots: [CupertinoSlot("content", content: content)])
    }
    public var body: some View { component }
}

public struct AdaptiveTheme: View {
    let component: CupertinoComponent
    public init<Content: View>(_ options: CupertinoThemeOptions = .init(), @ViewBuilder content: () -> Content) {
        component = CupertinoComponent("AdaptiveTheme", props: options.props, slots: [CupertinoSlot("content", content: content)])
    }
    public var body: some View { component }
}

public struct AdaptiveWidget: View {
    let component: CupertinoComponent
    public init<C: View, M: View>(@ViewBuilder cupertino: () -> C, @ViewBuilder material: () -> M) {
        component = CupertinoComponent("AdaptiveWidget", slots: [CupertinoSlot("cupertino", content: cupertino), CupertinoSlot("material", content: material)])
    }
    public var body: some View { component }
}

/// Common typed options shared by surfaces, bars and scaffolds; named slots carry
/// every upstream content position without flattening title/actions into a label.
public struct CupertinoContainerOptions: Sendable {
    public var enabled: Bool = true
    public var color: Color?
    public var contentColor: Color?
    public var containerColor: Color?
    public var shadowElevation: Double = 0
    public var isTransparent: Bool = false
    public var isTranslucent: Bool = false
    public var hasNavigationTitle: Bool = false
    public var applyContentPadding: Bool = true
    public init() {}
    var props: [String: PropValue] {
        precondition(shadowElevation.isFinite && shadowElevation >= 0)
        var p: [String: PropValue] = ["enabled": .bool(enabled), "shadowElevation": .double(shadowElevation),
            "isTransparent": .bool(isTransparent), "isTranslucent": .bool(isTranslucent), "hasNavigationTitle": .bool(hasNavigationTitle),
            "applyContentPadding": .bool(applyContentPadding)]
        if let color { p["color"] = .color(color) }
        if let contentColor { p["contentColor"] = .color(contentColor) }
        if let containerColor { p["containerColor"] = .color(containerColor) }
        return p
    }
}

public struct CupertinoSurface: View {
    let component: CupertinoComponent
    public init(options: CupertinoContainerOptions = .init(), onClick: (() -> Void)? = nil, slots: [CupertinoSlot]) {
        var actions: [String: ComposableAction] = [:]
        if let onClick { actions["onClick"] = .void { if options.enabled { onClick() } } }
        component = CupertinoComponent("CupertinoSurface", props: options.props, actions: actions, slots: slots)
    }
    public init<Content: View>(options: CupertinoContainerOptions = .init(), @ViewBuilder content: () -> Content) {
        self.init(options: options, slots: [CupertinoSlot("content", content: content)])
    }
    public var body: some View { component }
}

public struct AdaptiveSurface: View {
    let component: CupertinoComponent
    public init(options: CupertinoContainerOptions = .init(), onClick: (() -> Void)? = nil, slots: [CupertinoSlot]) {
        var actions: [String: ComposableAction] = [:]
        if let onClick { actions["onClick"] = .void { if options.enabled { onClick() } } }
        component = CupertinoComponent("AdaptiveSurface", props: options.props, actions: actions, slots: slots)
    }
    public init<Content: View>(options: CupertinoContainerOptions = .init(), @ViewBuilder content: () -> Content) {
        self.init(options: options, slots: [CupertinoSlot("content", content: content)])
    }
    public var body: some View { component }
}

public struct CupertinoScaffold: View {
    let component: CupertinoComponent
    public init(options: CupertinoContainerOptions = .init(), onClick: (() -> Void)? = nil, slots: [CupertinoSlot]) {
        var actions: [String: ComposableAction] = [:]
        if let onClick { actions["onClick"] = .void { if options.enabled { onClick() } } }
        component = CupertinoComponent("CupertinoScaffold", props: options.props, actions: actions, slots: slots)
    }
    public init<Content: View>(options: CupertinoContainerOptions = .init(), @ViewBuilder content: () -> Content) {
        self.init(options: options, slots: [CupertinoSlot("content", content: content)])
    }
    public var body: some View { component }
}

public struct AdaptiveScaffold: View {
    let component: CupertinoComponent
    public init(options: CupertinoContainerOptions = .init(), onClick: (() -> Void)? = nil, slots: [CupertinoSlot]) {
        var actions: [String: ComposableAction] = [:]
        if let onClick { actions["onClick"] = .void { if options.enabled { onClick() } } }
        component = CupertinoComponent("AdaptiveScaffold", props: options.props, actions: actions, slots: slots)
    }
    public init<Content: View>(options: CupertinoContainerOptions = .init(), @ViewBuilder content: () -> Content) {
        self.init(options: options, slots: [CupertinoSlot("content", content: content)])
    }
    public var body: some View { component }
}

public struct CupertinoTopAppBar: View {
    let component: CupertinoComponent
    public init(options: CupertinoContainerOptions = .init(), onClick: (() -> Void)? = nil, slots: [CupertinoSlot]) {
        var actions: [String: ComposableAction] = [:]
        if let onClick { actions["onClick"] = .void { if options.enabled { onClick() } } }
        component = CupertinoComponent("CupertinoTopAppBar", props: options.props, actions: actions, slots: slots)
    }
    public init<Content: View>(options: CupertinoContainerOptions = .init(), @ViewBuilder content: () -> Content) {
        self.init(options: options, slots: [CupertinoSlot("content", content: content)])
    }
    public var body: some View { component }
}

public struct AdaptiveTopAppBar: View {
    let component: CupertinoComponent
    public init(options: CupertinoContainerOptions = .init(), onClick: (() -> Void)? = nil, slots: [CupertinoSlot]) {
        var actions: [String: ComposableAction] = [:]
        if let onClick { actions["onClick"] = .void { if options.enabled { onClick() } } }
        component = CupertinoComponent("AdaptiveTopAppBar", props: options.props, actions: actions, slots: slots)
    }
    public init<Content: View>(options: CupertinoContainerOptions = .init(), @ViewBuilder content: () -> Content) {
        self.init(options: options, slots: [CupertinoSlot("content", content: content)])
    }
    public var body: some View { component }
}

public struct CupertinoBottomAppBar: View {
    let component: CupertinoComponent
    public init(options: CupertinoContainerOptions = .init(), onClick: (() -> Void)? = nil, slots: [CupertinoSlot]) {
        var actions: [String: ComposableAction] = [:]
        if let onClick { actions["onClick"] = .void { if options.enabled { onClick() } } }
        component = CupertinoComponent("CupertinoBottomAppBar", props: options.props, actions: actions, slots: slots)
    }
    public init<Content: View>(options: CupertinoContainerOptions = .init(), @ViewBuilder content: () -> Content) {
        self.init(options: options, slots: [CupertinoSlot("content", content: content)])
    }
    public var body: some View { component }
}

public struct CupertinoNavigationBar: View {
    let component: CupertinoComponent
    public init(options: CupertinoContainerOptions = .init(), onClick: (() -> Void)? = nil, slots: [CupertinoSlot]) {
        var actions: [String: ComposableAction] = [:]
        if let onClick { actions["onClick"] = .void { if options.enabled { onClick() } } }
        component = CupertinoComponent("CupertinoNavigationBar", props: options.props, actions: actions, slots: slots)
    }
    public init<Content: View>(options: CupertinoContainerOptions = .init(), @ViewBuilder content: () -> Content) {
        self.init(options: options, slots: [CupertinoSlot("content", content: content)])
    }
    public var body: some View { component }
}

public struct AdaptiveNavigationBar: View {
    let component: CupertinoComponent
    public init(options: CupertinoContainerOptions = .init(), onClick: (() -> Void)? = nil, slots: [CupertinoSlot]) {
        var actions: [String: ComposableAction] = [:]
        if let onClick { actions["onClick"] = .void { if options.enabled { onClick() } } }
        component = CupertinoComponent("AdaptiveNavigationBar", props: options.props, actions: actions, slots: slots)
    }
    public init<Content: View>(options: CupertinoContainerOptions = .init(), @ViewBuilder content: () -> Content) {
        self.init(options: options, slots: [CupertinoSlot("content", content: content)])
    }
    public var body: some View { component }
}

public struct CupertinoNavigationTitle: View {
    let component: CupertinoComponent
    public init(options: CupertinoContainerOptions = .init(), onClick: (() -> Void)? = nil, slots: [CupertinoSlot]) {
        var actions: [String: ComposableAction] = [:]
        if let onClick { actions["onClick"] = .void { if options.enabled { onClick() } } }
        component = CupertinoComponent("CupertinoNavigationTitle", props: options.props, actions: actions, slots: slots)
    }
    public init<Content: View>(options: CupertinoContainerOptions = .init(), @ViewBuilder content: () -> Content) {
        self.init(options: options, slots: [CupertinoSlot("content", content: content)])
    }
    public var body: some View { component }
}

public struct CupertinoBottomSheetContent: View {
    let component: CupertinoComponent
    public init(options: CupertinoContainerOptions = .init(), onClick: (() -> Void)? = nil, slots: [CupertinoSlot]) {
        var actions: [String: ComposableAction] = [:]
        if let onClick { actions["onClick"] = .void { if options.enabled { onClick() } } }
        component = CupertinoComponent("CupertinoBottomSheetContent", props: options.props, actions: actions, slots: slots)
    }
    public init<Content: View>(options: CupertinoContainerOptions = .init(), @ViewBuilder content: () -> Content) {
        self.init(options: options, slots: [CupertinoSlot("content", content: content)])
    }
    public var body: some View { component }
}

public struct CupertinoNavigationBarItem: View {
    let component: CupertinoComponent
    public init(selected: Bool, enabled: Bool = true, onClick: @escaping () -> Void, slots: [CupertinoSlot]) {
        component = CupertinoComponent("RowScope.CupertinoNavigationBarItem", props: ["selected": .bool(selected), "enabled": .bool(enabled)],
            actions: ["onClick": .void { if enabled { onClick() } }], slots: slots)
    }
    public var body: some View { component }
}

public struct AdaptiveNavigationBarItem: View {
    let component: CupertinoComponent
    public init(selected: Bool, enabled: Bool = true, onClick: @escaping () -> Void, slots: [CupertinoSlot]) {
        component = CupertinoComponent("RowScope.AdaptiveNavigationBarItem", props: ["selected": .bool(selected), "enabled": .bool(enabled)],
            actions: ["onClick": .void { if enabled { onClick() } }], slots: slots)
    }
    public var body: some View { component }
}

public struct CupertinoSegmentedControl: View {
    let component: CupertinoComponent
    public init<Tabs: View>(selectedTabIndex: Int, slots: [CupertinoSlot] = [], @ViewBuilder tabs: () -> Tabs) {
        precondition(selectedTabIndex >= 0)
        component = CupertinoComponent("CupertinoSegmentedControl", props: ["selectedTabIndex": .int(selectedTabIndex)],
            slots: [CupertinoSlot("tabs", content: tabs)] + slots)
    }
    public var body: some View { component }
}
public struct CupertinoSegmentedControlTab: View {
    let component: CupertinoComponent
    public init<Content: View>(isSelected: Bool, onClick: @escaping () -> Void, @ViewBuilder content: () -> Content) {
        component = CupertinoComponent("CupertinoSegmentedControlTab", props: ["isSelected": .bool(isSelected)],
            actions: ["onClick": .void(onClick)], slots: [CupertinoSlot("content", content: content)])
    }
    public var body: some View { component }
}
public struct CupertinoSegmentedControlIndicator: View {
    let component: CupertinoComponent
    public init(selectedTabIndex: Int, color: Color? = nil) {
        precondition(selectedTabIndex >= 0)
        var p: [String: PropValue] = ["selectedTabIndex": .int(selectedTabIndex)]
        if let color { p["color"] = .color(color) }
        component = CupertinoComponent("CupertinoSegmentedControlIndicator", props: p)
    }
    public var body: some View { component }
}
