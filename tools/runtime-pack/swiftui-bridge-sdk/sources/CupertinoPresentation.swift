/// A single-node presentation boundary avoids forcing a layout container around
/// a popup. The unmounted state is a real empty render node, not a visible alert.
private struct CupertinoConditionalAlert: View, PrimitiveView {
    let visible: Binding<Bool>
    let component: CupertinoComponent
    typealias Body = Never
    func _render(in context: ResolveContext) -> RenderNode {
        visible.wrappedValue ? Evaluator.resolve(component, context.descending("presented")) : RenderNode(type: "EmptyView", id: context.path)
    }
}

public enum CupertinoAlertActionStyle: String, CaseIterable, Sendable { case `default` = "Default", destructive = "Destructive", cancel = "Cancel" }
public enum CupertinoAlertActionKind: String, CaseIterable, Sendable { case action, `default`, destructive, cancel }
public struct CupertinoAlertAction: View {
    let component: CupertinoComponent
    public init(_ title: String, kind: CupertinoAlertActionKind = .action, style: CupertinoAlertActionStyle = .default,
                enabled: Bool = true, native: Bool = false, onClick: @escaping () -> Void) {
        component = CupertinoComponent((native ? "NativeAlertDialogActionsScope." : "AlertDialogActionsScope.") + kind.rawValue,
            props: ["title": .string(title), "style": .string(style.rawValue), "enabled": .bool(enabled)],
            actions: ["onClick": .void { if enabled { onClick() } }], slots: native ? [] : [CupertinoSlot("title") { CupertinoText(title) }])
    }
    public var body: some View { component }
}

public struct CupertinoAlertDialog: View {
    let component: CupertinoComponent
    private let visible: Binding<Bool>
    public init<Buttons: View>(visible: Binding<Bool>, title: String, message: String? = nil,
                onDismissRequest: @escaping () -> Void = {}, @ViewBuilder buttons: () -> Buttons) {
        self.visible = visible
        let props: [String: PropValue] = [:]
        var slots = [CupertinoSlot("buttons", content: buttons)]
        slots.append(CupertinoSlot("title") { CupertinoText(title) })
        if let message { slots.append(CupertinoSlot("message") { CupertinoText(message) }) }
        component = CupertinoComponent("CupertinoAlertDialog", props: props,
            actions: ["onDismissRequest": .void { visible.wrappedValue = false; onDismissRequest() }], slots: slots)
    }
    public init(visible: Binding<Bool>, onDismissRequest: @escaping () -> Void = {}, slots: [CupertinoSlot]) {
        self.visible = visible
        component = CupertinoComponent("CupertinoAlertDialog", props: [:],
            actions: ["onDismissRequest": .void { visible.wrappedValue = false; onDismissRequest() }], slots: slots)
    }
    public var body: some View { CupertinoConditionalAlert(visible: visible, component: component) }
}

public struct AdaptiveAlertDialog: View {
    let component: CupertinoComponent
    private let visible: Binding<Bool>
    public init<Buttons: View>(visible: Binding<Bool>, title: String, message: String? = nil,
                onDismissRequest: @escaping () -> Void = {}, @ViewBuilder buttons: () -> Buttons) {
        self.visible = visible
        let props: [String: PropValue] = [:]
        var slots = [CupertinoSlot("buttons", content: buttons)]
        slots.append(CupertinoSlot("title") { CupertinoText(title) })
        if let message { slots.append(CupertinoSlot("message") { CupertinoText(message) }) }
        component = CupertinoComponent("AdaptiveAlertDialog", props: props,
            actions: ["onDismissRequest": .void { visible.wrappedValue = false; onDismissRequest() }], slots: slots)
    }
    public init(visible: Binding<Bool>, onDismissRequest: @escaping () -> Void = {}, slots: [CupertinoSlot]) {
        self.visible = visible
        component = CupertinoComponent("AdaptiveAlertDialog", props: [:],
            actions: ["onDismissRequest": .void { visible.wrappedValue = false; onDismissRequest() }], slots: slots)
    }
    public var body: some View { CupertinoConditionalAlert(visible: visible, component: component) }
}

public struct CupertinoAlertDialogNative: View {
    let component: CupertinoComponent
    private let visible: Binding<Bool>
    public init<Buttons: View>(visible: Binding<Bool>, title: String, message: String? = nil,
                onDismissRequest: @escaping () -> Void = {}, @ViewBuilder buttons: () -> Buttons) {
        self.visible = visible
        var props: [String: PropValue] = [:]
        let slots = [CupertinoSlot("buttons", content: buttons)]
        props["title"] = .string(title)
        if let message { props["message"] = .string(message) }
        component = CupertinoComponent("CupertinoAlertDialogNative", props: props,
            actions: ["onDismissRequest": .void { visible.wrappedValue = false; onDismissRequest() }], slots: slots)
    }
    public init(visible: Binding<Bool>, onDismissRequest: @escaping () -> Void = {}, slots: [CupertinoSlot]) {
        self.visible = visible
        component = CupertinoComponent("CupertinoAlertDialogNative", props: [:],
            actions: ["onDismissRequest": .void { visible.wrappedValue = false; onDismissRequest() }], slots: slots)
    }
    public var body: some View { CupertinoConditionalAlert(visible: visible, component: component) }
}

public struct AdaptiveAlertDialogNative: View {
    let component: CupertinoComponent
    private let visible: Binding<Bool>
    public init<Buttons: View>(visible: Binding<Bool>, title: String, message: String? = nil,
                onDismissRequest: @escaping () -> Void = {}, @ViewBuilder buttons: () -> Buttons) {
        self.visible = visible
        var props: [String: PropValue] = [:]
        let slots = [CupertinoSlot("buttons", content: buttons)]
        props["title"] = .string(title)
        if let message { props["message"] = .string(message) }
        component = CupertinoComponent("AdaptiveAlertDialogNative", props: props,
            actions: ["onDismissRequest": .void { visible.wrappedValue = false; onDismissRequest() }], slots: slots)
    }
    public init(visible: Binding<Bool>, onDismissRequest: @escaping () -> Void = {}, slots: [CupertinoSlot]) {
        self.visible = visible
        component = CupertinoComponent("AdaptiveAlertDialogNative", props: [:],
            actions: ["onDismissRequest": .void { visible.wrappedValue = false; onDismissRequest() }], slots: slots)
    }
    public var body: some View { CupertinoConditionalAlert(visible: visible, component: component) }
}

public struct CupertinoActionSheet: View {
    let component: CupertinoComponent
    public init<Buttons: View>(visible: Binding<Bool>, title: String, message: String? = nil,
                onDismissRequest: @escaping () -> Void = {}, @ViewBuilder buttons: () -> Buttons) {
        let props: [String: PropValue] = ["visible": .bool(visible.wrappedValue)]
        var slots = [CupertinoSlot("buttons", content: buttons)]
        slots.append(CupertinoSlot("title") { CupertinoText(title) })
        if let message { slots.append(CupertinoSlot("message") { CupertinoText(message) }) }
        component = CupertinoComponent("CupertinoActionSheet", props: props,
            actions: ["onDismissRequest": .void { visible.wrappedValue = false; onDismissRequest() }], slots: slots)
    }
    public init(visible: Binding<Bool>, onDismissRequest: @escaping () -> Void = {}, slots: [CupertinoSlot]) {
        component = CupertinoComponent("CupertinoActionSheet", props: ["visible": .bool(visible.wrappedValue)],
            actions: ["onDismissRequest": .void { visible.wrappedValue = false; onDismissRequest() }], slots: slots)
    }
    public var body: some View { component }
}

public struct CupertinoActionSheetNative: View {
    let component: CupertinoComponent
    public init<Buttons: View>(visible: Binding<Bool>, title: String, message: String? = nil,
                onDismissRequest: @escaping () -> Void = {}, @ViewBuilder buttons: () -> Buttons) {
        var props: [String: PropValue] = ["visible": .bool(visible.wrappedValue)]
        let slots = [CupertinoSlot("buttons", content: buttons)]
        props["title"] = .string(title)
        if let message { props["message"] = .string(message) }
        component = CupertinoComponent("CupertinoActionSheetNative", props: props,
            actions: ["onDismissRequest": .void { visible.wrappedValue = false; onDismissRequest() }], slots: slots)
    }
    public init(visible: Binding<Bool>, onDismissRequest: @escaping () -> Void = {}, slots: [CupertinoSlot]) {
        component = CupertinoComponent("CupertinoActionSheetNative", props: ["visible": .bool(visible.wrappedValue)],
            actions: ["onDismissRequest": .void { visible.wrappedValue = false; onDismissRequest() }], slots: slots)
    }
    public var body: some View { component }
}

public struct CupertinoDropdownMenu: View {
    let component: CupertinoComponent
    public init<Content: View>(expanded: Binding<Bool>, onDismissRequest: @escaping () -> Void = {}, @ViewBuilder content: () -> Content) {
        component = CupertinoComponent("CupertinoDropdownMenu", props: ["expanded": .bool(expanded.wrappedValue)],
            actions: ["onDismissRequest": .void { expanded.wrappedValue = false; onDismissRequest() }], slots: [CupertinoSlot("content", content: content)])
    }
    public var body: some View { component }
}
public struct CupertinoMenuItem: View {
    let component: CupertinoComponent
    public init<Content: View>(slots: [CupertinoSlot] = [], @ViewBuilder content: () -> Content) {
        component = CupertinoComponent("CupertinoMenuScope.MenuItem", slots: [CupertinoSlot("content", content: content)] + slots)
    }
    public var body: some View { component }
}
public struct CupertinoMenuSection: View {
    let component: CupertinoComponent
    public init<Content: View>(slots: [CupertinoSlot] = [], @ViewBuilder content: () -> Content) {
        component = CupertinoComponent("CupertinoMenuScope.MenuSection", slots: [CupertinoSlot("content", content: content)] + slots)
    }
    public var body: some View { component }
}
public struct CupertinoMenuTitle: View {
    let component: CupertinoComponent
    public init<Content: View>(slots: [CupertinoSlot] = [], @ViewBuilder content: () -> Content) {
        component = CupertinoComponent("CupertinoMenuScope.MenuTitle", slots: [CupertinoSlot("title", content: content)] + slots)
    }
    public var body: some View { component }
}
public struct CupertinoMenuAction: View {
    let component: CupertinoComponent
    public init(isSelected: Bool = false, enabled: Bool = true, onClick: @escaping () -> Void, slots: [CupertinoSlot]) {
        component = CupertinoComponent("CupertinoMenuScope.MenuAction", props: ["isSelected": .bool(isSelected), "enabled": .bool(enabled)],
            actions: ["onClick": .void { if enabled { onClick() } }], slots: slots)
    }
    public var body: some View { component }
}
public struct CupertinoMenuPickerAction: View {
    let component: CupertinoComponent
    public init(isSelected: Bool = false, enabled: Bool = true, onClick: @escaping () -> Void, slots: [CupertinoSlot]) {
        component = CupertinoComponent("CupertinoMenuScope.MenuPickerAction", props: ["isSelected": .bool(isSelected), "enabled": .bool(enabled)],
            actions: ["onClick": .void { if enabled { onClick() } }], slots: slots)
    }
    public var body: some View { component }
}
public struct CupertinoMenuDivider: View {
    let component: CupertinoComponent
    public init(color: Color? = nil, height: Double = 1) {
        precondition(height.isFinite && height >= 0)
        var props: [String: PropValue] = ["height": .double(height)]
        if let color { props["color"] = .color(color) }
        component = CupertinoComponent("CupertinoMenuScope.MenuDivider", props: props)
    }
    public var body: some View { component }
}

public struct CupertinoBottomSheetScaffold: View {
    let component: CupertinoComponent
    public init(value: Binding<CupertinoSheetValue>, detents: [CupertinoPresentationDetent] = [.fraction(0.5), .fraction(1)],
                partialDetentIndex: Int = 0, allowedValues: [CupertinoSheetValue] = CupertinoSheetValue.allCases,
                command: CupertinoCommand<CupertinoSheetCommand>? = nil, sheetSwipeEnabled: Bool = true, slots: [CupertinoSlot]) {
        precondition(!detents.isEmpty && detents.indices.contains(partialDetentIndex) && allowedValues.contains(value.wrappedValue))
        var props: [String: PropValue] = ["value": .string(value.wrappedValue.rawValue), "detents": .string(cupertinoJSON(detents)),
            "partialDetentIndex": .int(partialDetentIndex), "allowedValues": .array(allowedValues.map { .string($0.rawValue) }), "sheetSwipeEnabled": .bool(sheetSwipeEnabled)]
        if let command { props["command"] = .string(command.value.rawValue); props["commandID"] = .int(command.id) }
        component = CupertinoComponent("CupertinoBottomSheetScaffold", props: props,
            actions: ["onStateChange": .string { text in
                if let next = cupertinoDecode(CupertinoSheetChange.self, text), allowedValues.contains(next.value) { value.wrappedValue = next.value }
            }], slots: slots)
    }
    public var body: some View { component }
}
public struct CupertinoSwipeChange: Codable, Equatable, Sendable { public let value: CupertinoSwipeValue }
public struct CupertinoSwipeBox: View {
    let component: CupertinoComponent
    public init(value: Binding<CupertinoSwipeValue>, startToEndBehavior: CupertinoSwipeBehavior = .dismissible,
                endToStartBehavior: CupertinoSwipeBehavior = .dismissible,
                allowedValues: [CupertinoSwipeValue] = CupertinoSwipeValue.allCases,
                command: CupertinoCommand<CupertinoSwipeCommand>? = nil, commandValue: CupertinoSwipeValue? = nil,
                slots: [CupertinoSlot]) {
        precondition(allowedValues.contains(value.wrappedValue))
        var props: [String: PropValue] = ["value": .string(value.wrappedValue.rawValue),
            "startToEndBehavior": .string(startToEndBehavior.rawValue), "endToStartBehavior": .string(endToStartBehavior.rawValue),
            "allowedValues": .array(allowedValues.map { .string($0.rawValue) })]
        if let command { props["command"] = .string(command.value.rawValue); props["commandID"] = .int(command.id) }
        if let commandValue { props["commandValue"] = .string(commandValue.rawValue) }
        component = CupertinoComponent("CupertinoSwipeBox", props: props,
            actions: ["onStateChange": .string { text in
                if let next = cupertinoDecode(CupertinoSwipeChange.self, text), allowedValues.contains(next.value) { value.wrappedValue = next.value }
            }], slots: slots)
    }
    public var body: some View { component }
}
public struct CupertinoSwipeBoxItem: View {
    let component: CupertinoComponent
    public init(color: Color, enabled: Bool = true, onClick: @escaping () -> Void, slots: [CupertinoSlot]) {
        component = CupertinoComponent("CupertinoSwipeBoxItem", props: ["color": .color(color), "enabled": .bool(enabled)],
            actions: ["onClick": .void { if enabled { onClick() } }], slots: slots)
    }
    public var body: some View { component }
}
public struct CupertinoSharedSwipeBoxController: View {
    let component: CupertinoComponent
    public init<Content: View>(collapseCommandID: Int? = nil, @ViewBuilder content: () -> Content) {
        var props: [String: PropValue] = [:]
        if let collapseCommandID { precondition(collapseCommandID >= 0); props["command"] = .string("collapse"); props["commandID"] = .int(collapseCommandID) }
        component = CupertinoComponent("SharedSwipeBoxController", props: props, slots: [CupertinoSlot("content", content: content)])
    }
    public var body: some View { component }
}
