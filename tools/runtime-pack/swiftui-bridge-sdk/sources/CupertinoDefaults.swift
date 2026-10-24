/// Shapes the JSON renderer can reconstruct; arbitrary Compose Shape instances
/// stay native. Rounded radii are measured in density-independent points.
public enum CupertinoShape: Sendable {
    case circle, capsule, rectangle, rounded(Double)
    var prop: PropValue {
        switch self {
        case .circle: return .string("circle")
        case .capsule: return .string("capsule")
        case .rectangle: return .string("rectangle")
        case .rounded(let radius):
            precondition(radius.isFinite && radius >= 0)
            return .double(radius)
        }
    }
}

/// Public default slots invoke their actual upstream implementation. Passive
/// decorations expose no invented click callbacks or unrelated state values.
public enum CupertinoBottomSheetDefaults {
    public struct DragHandle: View {
        let component: CupertinoComponent
        public init(width: Double = 38, height: Double = 5, shape: CupertinoShape = .circle, color: Color? = nil) {
            precondition(width.isFinite && width > 0 && height.isFinite && height > 0)
            var props: [String: PropValue] = ["width": .double(width), "height": .double(height), "shape": shape.prop]
            if let color { props["color"] = .color(color) }
            component = CupertinoComponent("CupertinoBottomSheetDefaults.DragHandle", props: props)
        }
        public var body: some View { component }
    }
}

public enum CupertinoDropdownMenuDefaults {
    public struct PickerLeadingIcon: View {
        let component = CupertinoComponent("CupertinoDropdownMenuDefaults.PickerLeadingIcon")
        public init() {}
        public var body: some View { component }
    }
}
public enum CupertinoNavigationBarDefaults {
    public struct Divider: View {
        let component = CupertinoComponent("CupertinoNavigationBarDefaults.divider")
        public init() {}
        public var body: some View { component }
    }
}
public enum CupertinoTopAppBarDefaults {
    public struct Divider: View {
        let component = CupertinoComponent("CupertinoTopAppBarDefaults.divider")
        public init() {}
        public var body: some View { component }
    }
}

public enum CupertinoSearchTextFieldDefaults {
    public struct LeadingIcon: View {
        let component: CupertinoComponent
        public init(symbol: CupertinoSymbol? = nil, rotateWithLayoutDirection: Bool = true) {
            var props: [String: PropValue] = ["rotateWithLayoutDirection": .bool(rotateWithLayoutDirection)]
            if let symbol { props["imageVector"] = .string(symbol.rawValue) }
            component = CupertinoComponent("CupertinoSearchTextFieldDefaults.leadingIcon", props: props)
        }
        public var body: some View { component }
    }
    /// The actual native cancel control emits onValueChange("") and reuses the
    /// parent search field's interaction source. It does not emit onClick.
    public struct CancelButton: View {
        let component: CupertinoComponent
        public init<Content: View>(value: Binding<String>, enabled: Bool = true, @ViewBuilder content: () -> Content) {
            component = CupertinoComponent("CupertinoSearchTextFieldDefaults.cancelButton", props: ["enabled": .bool(enabled)],
                actions: ["onValueChange": .string { if enabled { value.wrappedValue = $0 } }],
                slots: [CupertinoSlot("content", content: content)])
        }
        public init(value: Binding<String>, enabled: Bool = true) {
            self.init(value: value, enabled: enabled) { CupertinoText("Cancel") }
        }
        public var body: some View { component }
    }
}

public enum CupertinoSectionDefaults {
    /// The upstream picker label is passive; its owner supplies the clickable
    /// modifier and expansion action. The title remains a composable slot.
    public struct PickerButton: View {
        let component: CupertinoComponent
        public init<Title: View>(expanded: Bool, shape: CupertinoShape? = nil, containerColor: Color? = nil,
                activeContentColor: Color? = nil, contentColor: Color? = nil, @ViewBuilder title: () -> Title) {
            var props: [String: PropValue] = ["expanded": .bool(expanded)]
            if let shape { props["shape"] = shape.prop }
            if let containerColor { props["containerColor"] = .color(containerColor) }
            if let activeContentColor { props["activeContentColor"] = .color(activeContentColor) }
            if let contentColor { props["contentColor"] = .color(contentColor) }
            component = CupertinoComponent("CupertinoSectionDefaults.PickerButton", props: props, slots: [CupertinoSlot("title", content: title)])
        }
        public var body: some View { component }
    }
    public struct LabelChevron: View {
        let component = CupertinoComponent("CupertinoSectionDefaults.LabelChevron")
        public init() {}
        public var body: some View { component }
    }
    public struct TextFieldClearButton: View {
        let component: CupertinoComponent
        public init(visible: Bool, enabled: Bool = true, onClick: @escaping () -> Void) {
            component = CupertinoComponent("CupertinoSectionDefaults.TextFieldClearButton", props: ["visible": .bool(visible), "enabled": .bool(enabled)],
                actions: ["onClick": .void { if enabled && visible { onClick() } }])
        }
        public var body: some View { component }
    }
}

public enum CupertinoSliderDefaults {
    /// Must be placed in a slider thumb slot. Colors, interaction and measured
    /// positions come from that real parent slider; no standalone color is used.
    public struct Thumb: View {
        let component: CupertinoComponent
        public init(enabled: Bool = true, width: Double = 28, height: Double = 28) {
            precondition(width.isFinite && width > 0 && height.isFinite && height > 0)
            component = CupertinoComponent("CupertinoSliderDefaults.Thumb", props: ["enabled": .bool(enabled),
                "thumbWidth": .double(width), "thumbHeight": .double(height)])
        }
        public var body: some View { component }
    }
    /// Must be placed in a slider track slot. The parent owns colors and current
    /// SliderPositions; this decoration does not produce an independent event.
    public struct Track: View {
        let component: CupertinoComponent
        public init(enabled: Bool = true) {
            component = CupertinoComponent("CupertinoSliderDefaults.Track", props: ["enabled": .bool(enabled)])
        }
        public var body: some View { component }
    }
}

public enum CupertinoTextFieldContentAlignment: String, Sendable { case top, center }
public enum CupertinoTextFieldColorRole: String, CaseIterable, Sendable {
    case focusedTextColor, unfocusedTextColor, disabledTextColor, errorTextColor
    case focusedContainerColor, unfocusedContainerColor, disabledContainerColor, errorContainerColor
    case cursorColor, errorCursorColor, selectionHandleColor, selectionBackgroundColor
    case focusedBorderColor, unfocusedBorderColor, disabledBorderColor, errorBorderColor
    case focusedLeadingIconColor, unfocusedLeadingIconColor, disabledLeadingIconColor, errorLeadingIconColor
    case focusedTrailingIconColor, unfocusedTrailingIconColor, disabledTrailingIconColor, errorTrailingIconColor
    case focusedPlaceholderColor, unfocusedPlaceholderColor, disabledPlaceholderColor, errorPlaceholderColor
}
public enum CupertinoTextFieldDefaults {
    /// Supply innerTextField and optional placeholder/leadingIcon/trailingIcon
    /// slots. The parent native interaction source is reused; TextLayoutResult
    /// is native-only and is not represented by a fabricated Swift callback.
    public struct DecorationBox: View {
        let component: CupertinoComponent
        public init(valueIsEmpty: Bool, enabled: Bool = true, isError: Bool = false,
                contentAlignment: CupertinoTextFieldContentAlignment = .center,
                colors: [CupertinoTextFieldColorRole: Color] = [:], slots: [CupertinoSlot]) {
            precondition(slots.contains { $0.name == "innerTextField" })
            var props: [String: PropValue] = ["valueIsEmpty": .bool(valueIsEmpty), "enabled": .bool(enabled),
                "isError": .bool(isError), "contentAlignment": .string(contentAlignment.rawValue)]
            for (key, value) in colors { props[key.rawValue] = .color(value) }
            component = CupertinoComponent("CupertinoTextFieldDefaults.DecorationBox", props: props, slots: slots)
        }
        public var body: some View { component }
    }
}

public struct CupertinoTextStyleProvider: View {
    let content: AnyView
    public init<Content: View>(font: Font, @ViewBuilder content: () -> Content) {
        self.content = AnyView(CupertinoComponent("ProvideTextStyle", slots: [CupertinoSlot("content", content: content)]).font(font))
    }
    public var body: some View { content }
}
public struct CupertinoScaffoldPadding: View {
    let component: CupertinoComponent
    public init<Content: View>(@ViewBuilder content: () -> Content) {
        component = CupertinoComponent("CupertinoScaffoldPadding", slots: [CupertinoSlot("content", content: content)])
    }
    public var body: some View { component }
}

public struct CupertinoHazeArea: Codable, Equatable, Sendable {
    public let left: Double
    public let top: Double
    public let right: Double
    public let bottom: Double
    public init(left: Double, top: Double, right: Double, bottom: Double) {
        precondition([left, top, right, bottom].allSatisfy(\.isFinite) && right >= left && bottom >= top)
        self.left = left; self.top = top; self.right = right; self.bottom = bottom
    }
}
public extension View {
    func cupertinoHaze(areas: [CupertinoHazeArea], backgroundColor: Color, tint: Color? = nil, blurRadius: Double = 20) -> CupertinoComponent {
        precondition(blurRadius.isFinite && blurRadius >= 0)
        var props: [String: PropValue] = ["areaJson": .string(cupertinoJSON(areas)), "backgroundColor": .color(backgroundColor), "blurRadius": .double(blurRadius)]
        if let tint { props["tint"] = .color(tint) }
        return CupertinoComponent("Modifier.haze", props: props, slots: [CupertinoSlot("content") { self }])
    }
    func cupertinoPickerIndicator(selectedItem: Int = 0) -> CupertinoComponent {
        precondition(selectedItem >= 0)
        return CupertinoComponent("Modifier.cupertinoPickerIndicator", props: ["selectedItem": .int(selectedItem)], slots: [CupertinoSlot("content") { self }])
    }
    func cupertinoTabIndicatorOffset(selectedTabIndex: Int) -> CupertinoComponent {
        precondition(selectedTabIndex >= 0)
        return CupertinoComponent("TabRowDefaults.Modifier.tabIndicatorOffset", props: ["selectedTabIndex": .int(selectedTabIndex)], slots: [CupertinoSlot("content") { self }])
    }
    func cupertinoSectionContainerBackground(_ style: CupertinoSectionStyle = .insetGrouped) -> CupertinoComponent {
        CupertinoComponent("Modifier.sectionContainerBackground", props: ["style": .string(style.rawValue)], slots: [CupertinoSlot("content") { self }])
    }
    func cupertinoPredictiveEnter(progress: Double) -> CupertinoComponent {
        precondition(progress.isFinite && (0...1).contains(progress))
        return CupertinoComponent("Modifier.cupertinoPredictiveEnter", props: ["progress": .double(progress)], slots: [CupertinoSlot("content") { self }])
    }
    func cupertinoPredictiveExit(progress: Double) -> CupertinoComponent {
        precondition(progress.isFinite && (0...1).contains(progress))
        return CupertinoComponent("Modifier.cupertinoPredictiveExit", props: ["progress": .double(progress)], slots: [CupertinoSlot("content") { self }])
    }
}
