public enum CupertinoSectionStyle: String, CaseIterable, Sendable { case sidebar = "Sidebar", insetGrouped = "InsetGrouped", grouped = "Grouped", inset = "Inset", plain = "Plain" }

public struct CupertinoSection: View {
    let component: CupertinoComponent
    public init(style: CupertinoSectionStyle = .insetGrouped, collapsed: Binding<Bool> = .constant(false),
                canCollapse: Bool = true, slots: [CupertinoSlot]) {
        component = CupertinoComponent("CupertinoSection", props: ["style": .string(style.rawValue), "collapsed": .bool(collapsed.wrappedValue), "canCollapse": .bool(canCollapse)],
            actions: ["onCollapsedChange": .bool { if canCollapse { collapsed.wrappedValue = $0 } }], slots: slots)
    }
    public var body: some View { component }
}
public struct CupertinoSectionStyleProvider: View {
    let component: CupertinoComponent
    public init<Content: View>(_ style: CupertinoSectionStyle, @ViewBuilder content: () -> Content) {
        component = CupertinoComponent("ProvideSectionStyle", props: ["style": .string(style.rawValue)], slots: [CupertinoSlot("content", content: content)])
    }
    public var body: some View { component }
}
public struct CupertinoLazyColumn: View {
    let component: CupertinoComponent
    public init<Content: View>(@ViewBuilder content: () -> Content) {
        component = CupertinoComponent("CupertinoLazyColumn", slots: [CupertinoSlot("content", content: content)])
    }
    public var body: some View { component }
}
public struct CupertinoLazySection: View {
    let component: CupertinoComponent
    public init(sticky: Bool = false, style: CupertinoSectionStyle = .insetGrouped,
                collapsed: Binding<Bool> = .constant(false), canCollapse: Bool = true, slots: [CupertinoSlot]) {
        component = CupertinoComponent(sticky ? "LazyListScope.stickySection" : "LazyListScope.section",
            props: ["style": .string(style.rawValue), "collapsed": .bool(collapsed.wrappedValue), "canCollapse": .bool(canCollapse)],
            actions: ["onCollapsedChange": .bool { if canCollapse { collapsed.wrappedValue = $0 } }], slots: slots)
    }
    public var body: some View { component }
}

/// Reuses SwiftUICore's real on-demand row provider. Keys and callbacks remain
/// stable across insertions; rendered children are not materialized eagerly.
public struct CupertinoLazyItems<Data: RandomAccessCollection, ID: Hashable, Content: View>: View, PrimitiveView {
    let rows: ForEach<Data, ID, Content>
    public init(_ data: Data, id: @escaping (Data.Element) -> ID, @ViewBuilder content: @escaping (Data.Element) -> Content) {
        rows = ForEach(data, id: id, content: content)
    }
    public typealias Body = Never
    public func _render(in context: ResolveContext) -> RenderNode {
        _lazyStackNode(type: "Composable", content: rows,
            props: ["name": .string("LazySectionScope.items"), "count": .int(rows.data.count)], context: context)
    }
}

public struct CupertinoSectionItem: View {
    let component: CupertinoComponent
    public init(slots: [CupertinoSlot]) { component = CupertinoComponent("SectionScope.SectionItem", slots: slots) }
    public init<Content: View>(@ViewBuilder content: () -> Content) {
        self.init(slots: [CupertinoSlot("title", content: content)])
    }
    public var body: some View { component }
}

public struct CupertinoLazyItem: View {
    let component: CupertinoComponent
    public init(slots: [CupertinoSlot]) { component = CupertinoComponent("LazySectionScope.item", slots: slots) }
    public init<Content: View>(@ViewBuilder content: () -> Content) {
        self.init(slots: [CupertinoSlot("content", content: content)])
    }
    public var body: some View { component }
}

public struct CupertinoSectionLink: View {
    let component: CupertinoComponent
    public init(enabled: Bool = true, onClick: @escaping () -> Void, slots: [CupertinoSlot]) {
        component = CupertinoComponent("SectionScope.SectionLink", props: ["enabled": .bool(enabled)],
            actions: ["onClick": .void { if enabled { onClick() } }], slots: slots)
    }
    public var body: some View { component }
}

public struct CupertinoLazyLink: View {
    let component: CupertinoComponent
    public init(enabled: Bool = true, onClick: @escaping () -> Void, slots: [CupertinoSlot]) {
        component = CupertinoComponent("LazySectionScope.link", props: ["enabled": .bool(enabled)],
            actions: ["onClick": .void { if enabled { onClick() } }], slots: slots)
    }
    public var body: some View { component }
}

/// The `menu` slot is ordinary composable content: put a CupertinoDropdownMenu
/// there and its ordered CupertinoMenu* entries inside that menu's content.
/// This mirrors upstream SectionDropdownMenu's padding-aware menu position.
public struct CupertinoSectionDropdownMenu: View {
    let component: CupertinoComponent
    public init(expanded: Binding<Bool>, enabled: Bool = true, slots: [CupertinoSlot]) {
        component = CupertinoComponent("SectionScope.SectionDropdownMenu", props: ["expanded": .bool(expanded.wrappedValue), "enabled": .bool(enabled)],
            actions: ["onClick": .void { if enabled { expanded.wrappedValue = true } },
                      "onExpandedChange": .bool { if enabled { expanded.wrappedValue = $0 } },
                      "onDismissRequest": .void { expanded.wrappedValue = false }], slots: slots)
    }
    public var body: some View { component }
}

/// The native lazy-section DSL creates the dropdown itself. Its `content` slot
/// takes ordered CupertinoMenu* entries directly, unlike the eager section API.
public struct CupertinoLazyDropdownMenu: View {
    let component: CupertinoComponent
    public init(expanded: Binding<Bool>, enabled: Bool = true, slots: [CupertinoSlot]) {
        component = CupertinoComponent("LazySectionScope.dropdownMenu", props: ["expanded": .bool(expanded.wrappedValue), "enabled": .bool(enabled)],
            actions: ["onClick": .void { if enabled { expanded.wrappedValue = true } },
                      "onExpandedChange": .bool { if enabled { expanded.wrappedValue = $0 } },
                      "onDismissRequest": .void { expanded.wrappedValue = false }], slots: slots)
    }
    public var body: some View { component }
}

public struct CupertinoSectionTextField: View {
    let component: CupertinoComponent
    public init(value: Binding<String>, options: CupertinoTextFieldOptions = .init(), slots: [CupertinoSlot] = []) {
        var props = options.props; props["value"] = .string(value.wrappedValue)
        component = CupertinoComponent("SectionScope.SectionTextField", props: props,
            actions: ["onValueChange": .string { if options.enabled && !options.readOnly { value.wrappedValue = $0 } }], slots: slots)
    }
    public var body: some View { component }
}

public struct CupertinoLazyTextField: View {
    let component: CupertinoComponent
    public init(value: Binding<String>, options: CupertinoTextFieldOptions = .init(), slots: [CupertinoSlot] = []) {
        var props = options.props; props["value"] = .string(value.wrappedValue)
        component = CupertinoComponent("LazySectionScope.textField", props: props,
            actions: ["onValueChange": .string { if options.enabled && !options.readOnly { value.wrappedValue = $0 } }], slots: slots)
    }
    public var body: some View { component }
}

public struct CupertinoSectionDatePicker: View {
    let component: CupertinoComponent
    public init(selection: Binding<CupertinoDateSelection>, expanded: Binding<Bool>, enabled: Bool = true, yearRange: ClosedRange<Int> = 1900...2100, slots: [CupertinoSlot]) {
        precondition(cupertinoDateIsInRange(selection.wrappedValue.selectedDateMillis, yearRange))
        component = CupertinoComponent("SectionScope.SectionDatePicker", props: ["selectedDateMillis": .int(Int(selection.wrappedValue.selectedDateMillis)), "expanded": .bool(expanded.wrappedValue), "enabled": .bool(enabled), "yearRange": .array([.int(yearRange.lowerBound), .int(yearRange.upperBound)])],
            actions: ["onExpandedChange": .bool { if enabled { expanded.wrappedValue = $0 } },
                      "onSelectionChange": .string { text in
                if enabled, let next = cupertinoDecode(CupertinoDateSelection.self, text), cupertinoDateIsInRange(next.selectedDateMillis, yearRange) { selection.wrappedValue = next }
            }], slots: slots)
    }
    public var body: some View { component }
}

public struct CupertinoLazyDatePicker: View {
    let component: CupertinoComponent
    public init(selection: Binding<CupertinoDateSelection>, expanded: Binding<Bool>, enabled: Bool = true, yearRange: ClosedRange<Int> = 1900...2100, slots: [CupertinoSlot]) {
        precondition(cupertinoDateIsInRange(selection.wrappedValue.selectedDateMillis, yearRange))
        component = CupertinoComponent("LazySectionScope.datePicker", props: ["selectedDateMillis": .int(Int(selection.wrappedValue.selectedDateMillis)), "expanded": .bool(expanded.wrappedValue), "enabled": .bool(enabled), "yearRange": .array([.int(yearRange.lowerBound), .int(yearRange.upperBound)])],
            actions: ["onExpandedChange": .bool { if enabled { expanded.wrappedValue = $0 } },
                      "onSelectionChange": .string { text in
                if enabled, let next = cupertinoDecode(CupertinoDateSelection.self, text), cupertinoDateIsInRange(next.selectedDateMillis, yearRange) { selection.wrappedValue = next }
            }], slots: slots)
    }
    public var body: some View { component }
}

public struct CupertinoSectionTimePicker: View {
    let component: CupertinoComponent
    public init(selection: Binding<CupertinoTimeSelection>, expanded: Binding<Bool>, enabled: Bool = true, slots: [CupertinoSlot]) {
        component = CupertinoComponent("SectionScope.SectionTimePicker", props: ["hour": .int(selection.wrappedValue.hour), "minute": .int(selection.wrappedValue.minute), "expanded": .bool(expanded.wrappedValue), "enabled": .bool(enabled)],
            actions: ["onExpandedChange": .bool { if enabled { expanded.wrappedValue = $0 } },
                      "onSelectionChange": .string { text in
                if enabled, let next = cupertinoDecode(CupertinoTimeSelection.self, text), next.isValid { selection.wrappedValue = next }
            }], slots: slots)
    }
    public var body: some View { component }
}

public struct CupertinoLazyTimePicker: View {
    let component: CupertinoComponent
    public init(selection: Binding<CupertinoTimeSelection>, expanded: Binding<Bool>, enabled: Bool = true, slots: [CupertinoSlot]) {
        component = CupertinoComponent("LazySectionScope.timePicker", props: ["hour": .int(selection.wrappedValue.hour), "minute": .int(selection.wrappedValue.minute), "expanded": .bool(expanded.wrappedValue), "enabled": .bool(enabled)],
            actions: ["onExpandedChange": .bool { if enabled { expanded.wrappedValue = $0 } },
                      "onSelectionChange": .string { text in
                if enabled, let next = cupertinoDecode(CupertinoTimeSelection.self, text), next.isValid { selection.wrappedValue = next }
            }], slots: slots)
    }
    public var body: some View { component }
}

public struct CupertinoLazySwitch: View {
    let component: CupertinoComponent
    public init(checked: Binding<Bool>, enabled: Bool = true, slots: [CupertinoSlot]) {
        component = CupertinoComponent("LazySectionScope.switch", props: ["checked": .bool(checked.wrappedValue), "enabled": .bool(enabled)],
            actions: ["onCheckedChange": .bool { if enabled { checked.wrappedValue = $0 } }], slots: slots)
    }
    public var body: some View { component }
}

public extension String {
    /// Matches the upstream section-title rule; supply the same style as the
    /// surrounding section because Swift does not read Compose locals directly.
    func cupertinoSectionTitle(style: CupertinoSectionStyle = .insetGrouped) -> String {
        style == .insetGrouped || style == .grouped ? uppercased() : self
    }
}
