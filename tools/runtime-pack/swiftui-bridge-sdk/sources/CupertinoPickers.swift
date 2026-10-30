import Foundation

func cupertinoDateIsInRange(_ millis: Int64, _ range: ClosedRange<Int>) -> Bool {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(secondsFromGMT: 0)!
    return range.contains(calendar.component(.year, from: Date(timeIntervalSince1970: Double(millis) / 1000)))
}

public struct CupertinoPickerItem: Identifiable, Sendable {
    public let id: String
    public let title: String
    public init(id: String, title: String) { precondition(!id.isEmpty); self.id = id; self.title = title }
}
public enum CupertinoWheelIndicatorStyle: String, CaseIterable, Sendable { case modern, legacy }

public struct CupertinoWheelPicker: View {
    let component: CupertinoComponent
    public init(items: [CupertinoPickerItem], selectedItem: Binding<Int>, enabled: Bool = true,
                height: Double = 220, infinite: Bool = false, withRotation: Bool = false,
                indicatorStyle: CupertinoWheelIndicatorStyle = .modern, slots: [CupertinoSlot] = []) {
        precondition(!items.isEmpty && Set(items.map(\.id)).count == items.count && items.indices.contains(selectedItem.wrappedValue))
        precondition(height.isFinite && height > 0)
        component = CupertinoComponent("CupertinoWheelPicker", props: [
            "items": .array(items.map { .string($0.title) }), "itemKeys": .array(items.map { .string($0.id) }),
            "selectedItem": .int(selectedItem.wrappedValue), "enabled": .bool(enabled),
            "height": .double(height), "infinite": .bool(infinite), "withRotation": .bool(withRotation), "indicatorStyle": .string(indicatorStyle.rawValue)],
            actions: ["onSelectionChange": .int { if enabled && items.indices.contains($0) { selectedItem.wrappedValue = $0 } }], slots: slots)
    }
    public var body: some View { component }
}

public struct CupertinoDatePicker: View {
    let component: CupertinoComponent
    public init(selection: Binding<CupertinoDateSelection>, style: CupertinoDatePickerStyle = .wheel,
                yearRange: ClosedRange<Int> = 1900...2100, slots: [CupertinoSlot] = []) {
        precondition(cupertinoDateIsInRange(selection.wrappedValue.selectedDateMillis, yearRange))
        component = CupertinoComponent("CupertinoDatePicker", props: [
            "selectedDateMillis": .int(Int(selection.wrappedValue.selectedDateMillis)), "style": .string(style.rawValue),
            "yearRange": .array([.int(yearRange.lowerBound), .int(yearRange.upperBound)])],
            actions: ["onSelectionChange": .string { text in
                if let next = cupertinoDecode(CupertinoDateSelection.self, text), cupertinoDateIsInRange(next.selectedDateMillis, yearRange) { selection.wrappedValue = next }
            }], slots: slots)
    }
    public var body: some View { component }
}

public struct AdaptiveDatePicker: View {
    let component: CupertinoComponent
    public init(selection: Binding<CupertinoDateSelection>, style: CupertinoDatePickerStyle = .wheel,
                yearRange: ClosedRange<Int> = 1900...2100, slots: [CupertinoSlot] = []) {
        precondition(cupertinoDateIsInRange(selection.wrappedValue.selectedDateMillis, yearRange))
        component = CupertinoComponent("AdaptiveDatePicker", props: [
            "selectedDateMillis": .int(Int(selection.wrappedValue.selectedDateMillis)), "style": .string(style.rawValue),
            "yearRange": .array([.int(yearRange.lowerBound), .int(yearRange.upperBound)])],
            actions: ["onSelectionChange": .string { text in
                if let next = cupertinoDecode(CupertinoDateSelection.self, text), cupertinoDateIsInRange(next.selectedDateMillis, yearRange) { selection.wrappedValue = next }
            }], slots: slots)
    }
    public var body: some View { component }
}

public struct CupertinoDatePickerNative: View {
    let component: CupertinoComponent
    public init(selection: Binding<CupertinoDateSelection>, style: CupertinoDatePickerStyle = .wheel,
                yearRange: ClosedRange<Int> = 1900...2100, slots: [CupertinoSlot] = []) {
        precondition(cupertinoDateIsInRange(selection.wrappedValue.selectedDateMillis, yearRange))
        component = CupertinoComponent("CupertinoDatePickerNative", props: [
            "selectedDateMillis": .int(Int(selection.wrappedValue.selectedDateMillis)), "style": .string(style.rawValue),
            "yearRange": .array([.int(yearRange.lowerBound), .int(yearRange.upperBound)])],
            actions: ["onSelectionChange": .string { text in
                if let next = cupertinoDecode(CupertinoDateSelection.self, text), cupertinoDateIsInRange(next.selectedDateMillis, yearRange) { selection.wrappedValue = next }
            }], slots: slots)
    }
    public var body: some View { component }
}

public struct CupertinoTimePicker: View {
    let component: CupertinoComponent
    public init(selection: Binding<CupertinoTimeSelection>, is24Hour: Bool = true, slots: [CupertinoSlot] = []) {
        precondition(selection.wrappedValue.isValid)
        component = CupertinoComponent("CupertinoTimePicker", props: ["hour": .int(selection.wrappedValue.hour),
            "minute": .int(selection.wrappedValue.minute), "is24Hour": .bool(is24Hour)],
            actions: ["onSelectionChange": .string { text in
                if let next = cupertinoDecode(CupertinoTimeSelection.self, text), next.isValid { selection.wrappedValue = next }
            }], slots: slots)
    }
    public var body: some View { component }
}

public struct CupertinoTimePickerNative: View {
    let component: CupertinoComponent
    public init(selection: Binding<CupertinoTimeSelection>, is24Hour: Bool = true, slots: [CupertinoSlot] = []) {
        precondition(selection.wrappedValue.isValid)
        component = CupertinoComponent("CupertinoTimePickerNative", props: ["hour": .int(selection.wrappedValue.hour),
            "minute": .int(selection.wrappedValue.minute), "is24Hour": .bool(is24Hour)],
            actions: ["onSelectionChange": .string { text in
                if let next = cupertinoDecode(CupertinoTimeSelection.self, text), next.isValid { selection.wrappedValue = next }
            }], slots: slots)
    }
    public var body: some View { component }
}

public struct CupertinoDateTimePicker: View {
    let component: CupertinoComponent
    public init(selection: Binding<CupertinoDateTimeSelection>, is24Hour: Bool = true,
                yearRange: ClosedRange<Int> = 1900...2100, slots: [CupertinoSlot] = []) {
        precondition(selection.wrappedValue.isValid && cupertinoDateIsInRange(selection.wrappedValue.selectedDateMillis, yearRange))
        component = CupertinoComponent("CupertinoDateTimePicker", props: ["selectedDateMillis": .int(Int(selection.wrappedValue.selectedDateMillis)),
            "hour": .int(selection.wrappedValue.hour), "minute": .int(selection.wrappedValue.minute), "is24Hour": .bool(is24Hour),
            "yearRange": .array([.int(yearRange.lowerBound), .int(yearRange.upperBound)])],
            actions: ["onSelectionChange": .string { text in
                if let next = cupertinoDecode(CupertinoDateTimeSelection.self, text), next.isValid,
                   cupertinoDateIsInRange(next.selectedDateMillis, yearRange) { selection.wrappedValue = next }
            }], slots: slots)
    }
    public var body: some View { component }
}

public struct CupertinoDateTimePickerNative: View {
    let component: CupertinoComponent
    public init(selection: Binding<CupertinoDateTimeSelection>, is24Hour: Bool = true,
                yearRange: ClosedRange<Int> = 1900...2100, slots: [CupertinoSlot] = []) {
        precondition(selection.wrappedValue.isValid && cupertinoDateIsInRange(selection.wrappedValue.selectedDateMillis, yearRange))
        component = CupertinoComponent("CupertinoDateTimePickerNative", props: ["selectedDateMillis": .int(Int(selection.wrappedValue.selectedDateMillis)),
            "hour": .int(selection.wrappedValue.hour), "minute": .int(selection.wrappedValue.minute), "is24Hour": .bool(is24Hour),
            "yearRange": .array([.int(yearRange.lowerBound), .int(yearRange.upperBound)])],
            actions: ["onSelectionChange": .string { text in
                if let next = cupertinoDecode(CupertinoDateTimeSelection.self, text), next.isValid,
                   cupertinoDateIsInRange(next.selectedDateMillis, yearRange) { selection.wrappedValue = next }
            }], slots: slots)
    }
    public var body: some View { component }
}
