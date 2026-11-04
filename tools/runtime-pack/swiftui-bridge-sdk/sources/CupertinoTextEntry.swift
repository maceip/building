public struct CupertinoTextFieldOptions: Sendable {
    public var enabled: Bool
    public var readOnly: Bool
    public var isError: Bool
    public var singleLine: Bool
    public var minLines: Int
    public var maxLines: Int
    public var secure: Bool
    public init(enabled: Bool = true, readOnly: Bool = false, isError: Bool = false,
                singleLine: Bool = false, minLines: Int = 1, maxLines: Int = 100, secure: Bool = false) {
        precondition(minLines > 0 && maxLines >= minLines)
        self.enabled = enabled; self.readOnly = readOnly; self.isError = isError
        self.singleLine = singleLine; self.minLines = minLines; self.maxLines = maxLines; self.secure = secure
    }
    var props: [String: PropValue] {
        ["enabled": .bool(enabled), "readOnly": .bool(readOnly), "isError": .bool(isError),
         "singleLine": .bool(singleLine), "minLines": .int(minLines), "maxLines": .int(maxLines),
         "visualTransformation": .string(secure ? "Password" : "None")]
    }
}

public struct CupertinoTextField: View {
    let component: CupertinoComponent
    public init(value: Binding<String>, options: CupertinoTextFieldOptions = .init(),
                onSubmit: @escaping () -> Void = {}, slots: [CupertinoSlot] = []) {
        var props = options.props; props["value"] = .string(value.wrappedValue)
        component = CupertinoComponent("CupertinoTextField", props: props,
            actions: ["onValueChange": .string { if options.enabled && !options.readOnly { value.wrappedValue = $0 } },
                      "onSubmit": .void { if options.enabled { onSubmit() } }], slots: slots)
    }
    public init(value: Binding<CupertinoEditingValue>, options: CupertinoTextFieldOptions = .init(),
                onSubmit: @escaping () -> Void = {}, slots: [CupertinoSlot] = []) {
        precondition(value.wrappedValue.isValid)
        var props = options.props; props["valueJson"] = .string(cupertinoJSON(value.wrappedValue))
        component = CupertinoComponent("CupertinoTextField", props: props,
            actions: ["onValueChange": .string { text in
                if options.enabled && !options.readOnly,
                   let next = cupertinoDecode(CupertinoEditingValue.self, text), next.isValid { value.wrappedValue = next }
            }, "onSubmit": .void { if options.enabled { onSubmit() } }], slots: slots)
    }
    public var body: some View { component }
}

public struct CupertinoBorderedTextField: View {
    let component: CupertinoComponent
    public init(value: Binding<String>, options: CupertinoTextFieldOptions = .init(),
                onSubmit: @escaping () -> Void = {}, slots: [CupertinoSlot] = []) {
        var props = options.props; props["value"] = .string(value.wrappedValue)
        component = CupertinoComponent("CupertinoBorderedTextField", props: props,
            actions: ["onValueChange": .string { if options.enabled && !options.readOnly { value.wrappedValue = $0 } },
                      "onSubmit": .void { if options.enabled { onSubmit() } }], slots: slots)
    }
    public init(value: Binding<CupertinoEditingValue>, options: CupertinoTextFieldOptions = .init(),
                onSubmit: @escaping () -> Void = {}, slots: [CupertinoSlot] = []) {
        precondition(value.wrappedValue.isValid)
        var props = options.props; props["valueJson"] = .string(cupertinoJSON(value.wrappedValue))
        component = CupertinoComponent("CupertinoBorderedTextField", props: props,
            actions: ["onValueChange": .string { text in
                if options.enabled && !options.readOnly,
                   let next = cupertinoDecode(CupertinoEditingValue.self, text), next.isValid { value.wrappedValue = next }
            }, "onSubmit": .void { if options.enabled { onSubmit() } }], slots: slots)
    }
    public var body: some View { component }
}

public struct CupertinoSearchTextField: View {
    let component: CupertinoComponent
    public init(value: Binding<String>, options: CupertinoTextFieldOptions = .init(),
                onSubmit: @escaping () -> Void = {}, slots: [CupertinoSlot] = []) {
        var props = options.props; props["value"] = .string(value.wrappedValue)
        component = CupertinoComponent("CupertinoSearchTextField", props: props,
            actions: ["onValueChange": .string { if options.enabled && !options.readOnly { value.wrappedValue = $0 } },
                      "onSubmit": .void { if options.enabled { onSubmit() } }], slots: slots)
    }
    public var body: some View { component }
}
