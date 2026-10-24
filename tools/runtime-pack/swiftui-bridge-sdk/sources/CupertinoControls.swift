public struct CupertinoText: View {
    let component: CupertinoComponent
    public init(_ text: String, color: Color? = nil, maxLines: Int? = nil) {
        var props: [String: PropValue] = ["text": .string(text)]
        if let color { props["color"] = .color(color) }
        if let maxLines { precondition(maxLines > 0); props["maxLines"] = .int(maxLines) }
        component = CupertinoComponent("CupertinoText", props: props)
    }
    public var body: some View { component }
}

public struct CupertinoButton: View {
    let component: CupertinoComponent
    public init<Content: View>(enabled: Bool = true, colors: CupertinoButtonStyle = .filled,
                size: CupertinoControlSize = .regular, onClick: @escaping () -> Void,
                @ViewBuilder content: () -> Content) {
        component = CupertinoComponent("CupertinoButton",
            props: ["enabled": .bool(enabled), "colors": .string(colors.rawValue), "size": .string(size.rawValue)],
            actions: ["onClick": .void { if enabled { onClick() } }],
            slots: [CupertinoSlot("content", content: content)])
    }
    public init(_ title: String, enabled: Bool = true, colors: CupertinoButtonStyle = .filled,
                size: CupertinoControlSize = .regular, onClick: @escaping () -> Void) {
        self.init(enabled: enabled, colors: colors, size: size, onClick: onClick) { CupertinoText(title) }
    }
    public var body: some View { component }
}

public struct CupertinoIconButton: View {
    let component: CupertinoComponent
    public init<Content: View>(enabled: Bool = true, colors: CupertinoButtonStyle = .filled,
                size: CupertinoControlSize = .regular, onClick: @escaping () -> Void,
                @ViewBuilder content: () -> Content) {
        component = CupertinoComponent("CupertinoIconButton",
            props: ["enabled": .bool(enabled), "colors": .string(colors.rawValue), "size": .string(size.rawValue)],
            actions: ["onClick": .void { if enabled { onClick() } }],
            slots: [CupertinoSlot("content", content: content)])
    }
    public init(_ title: String, enabled: Bool = true, colors: CupertinoButtonStyle = .filled,
                size: CupertinoControlSize = .regular, onClick: @escaping () -> Void) {
        self.init(enabled: enabled, colors: colors, size: size, onClick: onClick) { CupertinoText(title) }
    }
    public var body: some View { component }
}

public struct CupertinoNavigateBackButton: View {
    let component: CupertinoComponent
    public init<Content: View>(enabled: Bool = true, colors: CupertinoButtonStyle = .filled,
                size: CupertinoControlSize = .regular, onClick: @escaping () -> Void,
                @ViewBuilder content: () -> Content) {
        component = CupertinoComponent("CupertinoNavigateBackButton",
            props: ["enabled": .bool(enabled), "colors": .string(colors.rawValue), "size": .string(size.rawValue)],
            actions: ["onClick": .void { if enabled { onClick() } }],
            slots: [CupertinoSlot("title", content: content)])
    }
    public init(_ title: String, enabled: Bool = true, colors: CupertinoButtonStyle = .filled,
                size: CupertinoControlSize = .regular, onClick: @escaping () -> Void) {
        self.init(enabled: enabled, colors: colors, size: size, onClick: onClick) { CupertinoText(title) }
    }
    public var body: some View { component }
}

public struct AdaptiveButton: View {
    let component: CupertinoComponent
    public init<Content: View>(enabled: Bool = true, colors: CupertinoButtonStyle = .filled,
                size: CupertinoControlSize = .regular, onClick: @escaping () -> Void,
                @ViewBuilder content: () -> Content) {
        component = CupertinoComponent("AdaptiveButton",
            props: ["enabled": .bool(enabled), "colors": .string(colors.rawValue), "size": .string(size.rawValue)],
            actions: ["onClick": .void { if enabled { onClick() } }],
            slots: [CupertinoSlot("content", content: content)])
    }
    public init(_ title: String, enabled: Bool = true, colors: CupertinoButtonStyle = .filled,
                size: CupertinoControlSize = .regular, onClick: @escaping () -> Void) {
        self.init(enabled: enabled, colors: colors, size: size, onClick: onClick) { CupertinoText(title) }
    }
    public var body: some View { component }
}

public struct AdaptiveTextButton: View {
    let component: CupertinoComponent
    public init<Content: View>(enabled: Bool = true, colors: CupertinoButtonStyle = .filled,
                size: CupertinoControlSize = .regular, onClick: @escaping () -> Void,
                @ViewBuilder content: () -> Content) {
        component = CupertinoComponent("AdaptiveTextButton",
            props: ["enabled": .bool(enabled), "colors": .string(colors.rawValue), "size": .string(size.rawValue)],
            actions: ["onClick": .void { if enabled { onClick() } }],
            slots: [CupertinoSlot("content", content: content)])
    }
    public init(_ title: String, enabled: Bool = true, colors: CupertinoButtonStyle = .filled,
                size: CupertinoControlSize = .regular, onClick: @escaping () -> Void) {
        self.init(enabled: enabled, colors: colors, size: size, onClick: onClick) { CupertinoText(title) }
    }
    public var body: some View { component }
}

public struct AdaptiveTonalButton: View {
    let component: CupertinoComponent
    public init<Content: View>(enabled: Bool = true, colors: CupertinoButtonStyle = .filled,
                size: CupertinoControlSize = .regular, onClick: @escaping () -> Void,
                @ViewBuilder content: () -> Content) {
        component = CupertinoComponent("AdaptiveTonalButton",
            props: ["enabled": .bool(enabled), "colors": .string(colors.rawValue), "size": .string(size.rawValue)],
            actions: ["onClick": .void { if enabled { onClick() } }],
            slots: [CupertinoSlot("content", content: content)])
    }
    public init(_ title: String, enabled: Bool = true, colors: CupertinoButtonStyle = .filled,
                size: CupertinoControlSize = .regular, onClick: @escaping () -> Void) {
        self.init(enabled: enabled, colors: colors, size: size, onClick: onClick) { CupertinoText(title) }
    }
    public var body: some View { component }
}

public struct AdaptiveIconButton: View {
    let component: CupertinoComponent
    public init<Content: View>(enabled: Bool = true, colors: CupertinoButtonStyle = .filled,
                size: CupertinoControlSize = .regular, onClick: @escaping () -> Void,
                @ViewBuilder content: () -> Content) {
        component = CupertinoComponent("AdaptiveIconButton",
            props: ["enabled": .bool(enabled), "colors": .string(colors.rawValue), "size": .string(size.rawValue)],
            actions: ["onClick": .void { if enabled { onClick() } }],
            slots: [CupertinoSlot("content", content: content)])
    }
    public init(_ title: String, enabled: Bool = true, colors: CupertinoButtonStyle = .filled,
                size: CupertinoControlSize = .regular, onClick: @escaping () -> Void) {
        self.init(enabled: enabled, colors: colors, size: size, onClick: onClick) { CupertinoText(title) }
    }
    public var body: some View { component }
}

public struct AdaptiveFilledIconButton: View {
    let component: CupertinoComponent
    public init<Content: View>(enabled: Bool = true, colors: CupertinoButtonStyle = .filled,
                size: CupertinoControlSize = .regular, onClick: @escaping () -> Void,
                @ViewBuilder content: () -> Content) {
        component = CupertinoComponent("AdaptiveFilledIconButton",
            props: ["enabled": .bool(enabled), "colors": .string(colors.rawValue), "size": .string(size.rawValue)],
            actions: ["onClick": .void { if enabled { onClick() } }],
            slots: [CupertinoSlot("content", content: content)])
    }
    public init(_ title: String, enabled: Bool = true, colors: CupertinoButtonStyle = .filled,
                size: CupertinoControlSize = .regular, onClick: @escaping () -> Void) {
        self.init(enabled: enabled, colors: colors, size: size, onClick: onClick) { CupertinoText(title) }
    }
    public var body: some View { component }
}

public struct CupertinoSwitch: View {
    let component: CupertinoComponent
    public init(checked: Binding<Bool>, enabled: Bool = true, slots: [CupertinoSlot] = []) {
        component = CupertinoComponent("CupertinoSwitch", props: ["checked": .bool(checked.wrappedValue), "enabled": .bool(enabled)],
            actions: ["onCheckedChange": .bool { if enabled { checked.wrappedValue = $0 } }], slots: slots)
    }
    public var body: some View { component }
}

public struct CupertinoCheckBox: View {
    let component: CupertinoComponent
    public init(checked: Binding<Bool>, enabled: Bool = true, slots: [CupertinoSlot] = []) {
        component = CupertinoComponent("CupertinoCheckBox", props: ["checked": .bool(checked.wrappedValue), "enabled": .bool(enabled)],
            actions: ["onCheckedChange": .bool { if enabled { checked.wrappedValue = $0 } }], slots: slots)
    }
    public var body: some View { component }
}

public struct AdaptiveSwitch: View {
    let component: CupertinoComponent
    public init(checked: Binding<Bool>, enabled: Bool = true, slots: [CupertinoSlot] = []) {
        component = CupertinoComponent("AdaptiveSwitch", props: ["checked": .bool(checked.wrappedValue), "enabled": .bool(enabled)],
            actions: ["onCheckedChange": .bool { if enabled { checked.wrappedValue = $0 } }], slots: slots)
    }
    public var body: some View { component }
}

public struct AdaptiveCheckbox: View {
    let component: CupertinoComponent
    public init(checked: Binding<Bool>, enabled: Bool = true, slots: [CupertinoSlot] = []) {
        component = CupertinoComponent("AdaptiveCheckbox", props: ["checked": .bool(checked.wrappedValue), "enabled": .bool(enabled)],
            actions: ["onCheckedChange": .bool { if enabled { checked.wrappedValue = $0 } }], slots: slots)
    }
    public var body: some View { component }
}

public struct CupertinoTriStateCheckBox: View {
    let component: CupertinoComponent
    public init(state: CupertinoToggleState, enabled: Bool = true, onClick: @escaping () -> Void) {
        component = CupertinoComponent("CupertinoTriStateCheckBox", props: ["state": .string(state.rawValue), "enabled": .bool(enabled)],
            actions: ["onClick": .void { if enabled { onClick() } }])
    }
    public var body: some View { component }
}

public struct AdaptiveTriStateCheckbox: View {
    let component: CupertinoComponent
    public init(state: CupertinoToggleState, enabled: Bool = true, onClick: @escaping () -> Void) {
        component = CupertinoComponent("AdaptiveTriStateCheckbox", props: ["state": .string(state.rawValue), "enabled": .bool(enabled)],
            actions: ["onClick": .void { if enabled { onClick() } }])
    }
    public var body: some View { component }
}

public struct CupertinoSlider: View {
    let component: CupertinoComponent
    public init(value: Binding<Double>, valueRange: ClosedRange<Double> = 0...1, steps: Int = 0,
                enabled: Bool = true, onValueChangeFinished: @escaping () -> Void = {}, slots: [CupertinoSlot] = []) {
        precondition(valueRange.lowerBound.isFinite && valueRange.upperBound.isFinite && valueRange.lowerBound < valueRange.upperBound && steps >= 0 && value.wrappedValue.isFinite && valueRange.contains(value.wrappedValue))
        component = CupertinoComponent("CupertinoSlider", props: ["value": .double(value.wrappedValue),
            "valueRange": .array([.double(valueRange.lowerBound), .double(valueRange.upperBound)]), "steps": .int(steps), "enabled": .bool(enabled)],
            actions: ["onValueChange": .double { if enabled && $0.isFinite && valueRange.contains($0) { value.wrappedValue = $0 } },
                      "onValueChangeFinished": .void { if enabled { onValueChangeFinished() } }], slots: slots)
    }
    public var body: some View { component }
}

public struct AdaptiveSlider: View {
    let component: CupertinoComponent
    public init(value: Binding<Double>, valueRange: ClosedRange<Double> = 0...1, steps: Int = 0,
                enabled: Bool = true, onValueChangeFinished: @escaping () -> Void = {}, slots: [CupertinoSlot] = []) {
        precondition(valueRange.lowerBound.isFinite && valueRange.upperBound.isFinite && valueRange.lowerBound < valueRange.upperBound && steps >= 0 && value.wrappedValue.isFinite && valueRange.contains(value.wrappedValue))
        component = CupertinoComponent("AdaptiveSlider", props: ["value": .double(value.wrappedValue),
            "valueRange": .array([.double(valueRange.lowerBound), .double(valueRange.upperBound)]), "steps": .int(steps), "enabled": .bool(enabled)],
            actions: ["onValueChange": .double { if enabled && $0.isFinite && valueRange.contains($0) { value.wrappedValue = $0 } },
                      "onValueChangeFinished": .void { if enabled { onValueChangeFinished() } }], slots: slots)
    }
    public var body: some View { component }
}

public struct CupertinoRangeSlider: View {
    let component: CupertinoComponent
    public init(value: Binding<CupertinoRangeValue>, valueRange: ClosedRange<Double> = 0...1,
                steps: Int = 0, enabled: Bool = true, onValueChangeFinished: @escaping () -> Void = {}, slots: [CupertinoSlot] = []) {
        precondition(valueRange.lowerBound.isFinite && valueRange.upperBound.isFinite && valueRange.lowerBound < valueRange.upperBound && steps >= 0 && value.wrappedValue.isValid && valueRange.contains(value.wrappedValue.start) && valueRange.contains(value.wrappedValue.end))
        component = CupertinoComponent("CupertinoRangeSlider", props: [
            "value": .array([.double(value.wrappedValue.start), .double(value.wrappedValue.end)]),
            "valueRange": .array([.double(valueRange.lowerBound), .double(valueRange.upperBound)]), "steps": .int(steps), "enabled": .bool(enabled)],
            actions: ["onValueChange": .string { text in
                if enabled, let next = cupertinoDecode(CupertinoRangeValue.self, text), next.isValid,
                   valueRange.contains(next.start), valueRange.contains(next.end) { value.wrappedValue = next }
            }, "onValueChangeFinished": .void { if enabled { onValueChangeFinished() } }], slots: slots)
    }
    public var body: some View { component }
}

public struct AdaptiveRangeSlider: View {
    let component: CupertinoComponent
    public init(value: Binding<CupertinoRangeValue>, valueRange: ClosedRange<Double> = 0...1,
                steps: Int = 0, enabled: Bool = true, onValueChangeFinished: @escaping () -> Void = {}, slots: [CupertinoSlot] = []) {
        precondition(valueRange.lowerBound.isFinite && valueRange.upperBound.isFinite && valueRange.lowerBound < valueRange.upperBound && steps >= 0 && value.wrappedValue.isValid && valueRange.contains(value.wrappedValue.start) && valueRange.contains(value.wrappedValue.end))
        component = CupertinoComponent("AdaptiveRangeSlider", props: [
            "value": .array([.double(value.wrappedValue.start), .double(value.wrappedValue.end)]),
            "valueRange": .array([.double(valueRange.lowerBound), .double(valueRange.upperBound)]), "steps": .int(steps), "enabled": .bool(enabled)],
            actions: ["onValueChange": .string { text in
                if enabled, let next = cupertinoDecode(CupertinoRangeValue.self, text), next.isValid,
                   valueRange.contains(next.start), valueRange.contains(next.end) { value.wrappedValue = next }
            }, "onValueChangeFinished": .void { if enabled { onValueChangeFinished() } }], slots: slots)
    }
    public var body: some View { component }
}

public struct CupertinoActivityIndicator: View {
    let component: CupertinoComponent
    public init(progress: Double? = nil, color: Color? = nil, size: Double? = nil) {
        var props: [String: PropValue] = [:]
        if let progress { precondition(progress.isFinite && (0...1).contains(progress)); props["progress"] = .double(progress) }
        if let size { precondition(size.isFinite && size > 0); props["size"] = .double(size) }
        if let color { props["color"] = .color(color) }
        component = CupertinoComponent("CupertinoActivityIndicator", props: props)
    }
    public var body: some View { component }
}

public struct AdaptiveCircularProgressIndicator: View {
    let component: CupertinoComponent
    public init(progress: Double? = nil, color: Color? = nil, size: Double? = nil) {
        var props: [String: PropValue] = [:]
        if let progress { precondition(progress.isFinite && (0...1).contains(progress)); props["progress"] = .double(progress) }
        if let size { precondition(size.isFinite && size > 0); props["size"] = .double(size) }
        if let color { props["color"] = .color(color) }
        component = CupertinoComponent("AdaptiveCircularProgressIndicator", props: props)
    }
    public var body: some View { component }
}

public struct CupertinoDivider: View {
    let component: CupertinoComponent
    public init(thickness: Double = 1, color: Color? = nil) {
        precondition(thickness.isFinite && thickness >= 0)
        var props: [String: PropValue] = ["thickness": .double(thickness)]
        if let color { props["color"] = .color(color) }
        component = CupertinoComponent("CupertinoDivider", props: props)
    }
    public var body: some View { component }
}

public struct CupertinoHorizontalDivider: View {
    let component: CupertinoComponent
    public init(thickness: Double = 1, color: Color? = nil) {
        precondition(thickness.isFinite && thickness >= 0)
        var props: [String: PropValue] = ["thickness": .double(thickness)]
        if let color { props["color"] = .color(color) }
        component = CupertinoComponent("CupertinoHorizontalDivider", props: props)
    }
    public var body: some View { component }
}

public struct CupertinoVerticalDivider: View {
    let component: CupertinoComponent
    public init(thickness: Double = 1, color: Color? = nil) {
        precondition(thickness.isFinite && thickness >= 0)
        var props: [String: PropValue] = ["thickness": .double(thickness)]
        if let color { props["color"] = .color(color) }
        component = CupertinoComponent("CupertinoVerticalDivider", props: props)
    }
    public var body: some View { component }
}

public struct AdaptiveDivider: View {
    let component: CupertinoComponent
    public init(thickness: Double = 1, color: Color? = nil) {
        precondition(thickness.isFinite && thickness >= 0)
        var props: [String: PropValue] = ["thickness": .double(thickness)]
        if let color { props["color"] = .color(color) }
        component = CupertinoComponent("AdaptiveDivider", props: props)
    }
    public var body: some View { component }
}

public struct AdaptiveHorizontalDivider: View {
    let component: CupertinoComponent
    public init(thickness: Double = 1, color: Color? = nil) {
        precondition(thickness.isFinite && thickness >= 0)
        var props: [String: PropValue] = ["thickness": .double(thickness)]
        if let color { props["color"] = .color(color) }
        component = CupertinoComponent("AdaptiveHorizontalDivider", props: props)
    }
    public var body: some View { component }
}

public struct AdaptiveVerticalDivider: View {
    let component: CupertinoComponent
    public init(thickness: Double = 1, color: Color? = nil) {
        precondition(thickness.isFinite && thickness >= 0)
        var props: [String: PropValue] = ["thickness": .double(thickness)]
        if let color { props["color"] = .color(color) }
        component = CupertinoComponent("AdaptiveVerticalDivider", props: props)
    }
    public var body: some View { component }
}
