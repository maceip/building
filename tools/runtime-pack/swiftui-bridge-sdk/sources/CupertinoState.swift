import Foundation

public enum CupertinoTarget: String, CaseIterable, Sendable { case cupertino = "Cupertino", material = "Material3" }
public enum CupertinoToggleState: String, CaseIterable, Sendable { case off = "Off", on = "On", indeterminate = "Indeterminate" }
public enum CupertinoButtonStyle: String, CaseIterable, Sendable { case plain, tinted, gray, filled }
public enum CupertinoControlSize: String, CaseIterable, Sendable { case regular, small, large, extraLarge }
public enum CupertinoDatePickerStyle: String, CaseIterable, Sendable { case wheel = "Wheel", pager = "Pager" }

public struct CupertinoRangeValue: Codable, Equatable, Sendable {
    public var start: Double
    public var end: Double
    public init(start: Double, end: Double) {
        precondition(start.isFinite && end.isFinite && start <= end)
        self.start = start; self.end = end
    }
    public var isValid: Bool { start.isFinite && end.isFinite && start <= end }
}

/// Selection and composition offsets are UTF-16 indices, matching Compose.
public struct CupertinoEditingValue: Codable, Equatable, Sendable {
    public var text: String
    public var selectionStart: Int
    public var selectionEnd: Int
    public var compositionStart: Int?
    public var compositionEnd: Int?
    public init(text: String, selectionStart: Int? = nil, selectionEnd: Int? = nil,
                compositionStart: Int? = nil, compositionEnd: Int? = nil) {
        self.text = text
        self.selectionStart = selectionStart ?? text.utf16.count
        self.selectionEnd = selectionEnd ?? text.utf16.count
        self.compositionStart = compositionStart; self.compositionEnd = compositionEnd
        precondition(isValid)
    }
    public var isValid: Bool {
        let bounds = 0...text.utf16.count
        guard bounds.contains(selectionStart), bounds.contains(selectionEnd) else { return false }
        switch (compositionStart, compositionEnd) {
        case (nil, nil): return true
        case let (.some(a), .some(b)): return bounds.contains(a) && bounds.contains(b)
        default: return false
        }
    }
}

public struct CupertinoDateSelection: Codable, Equatable, Sendable {
    public var selectedDateMillis: Int64
    public init(selectedDateMillis: Int64) { self.selectedDateMillis = selectedDateMillis }
}
public struct CupertinoTimeSelection: Codable, Equatable, Sendable {
    public var hour: Int
    public var minute: Int
    public init(hour: Int, minute: Int) { self.hour = hour; self.minute = minute; precondition(isValid) }
    public var isValid: Bool { (0...23).contains(hour) && (0...59).contains(minute) }
}
public struct CupertinoDateTimeSelection: Codable, Equatable, Sendable {
    public var selectedDateMillis: Int64
    public var hour: Int
    public var minute: Int
    public init(selectedDateMillis: Int64, hour: Int, minute: Int) {
        self.selectedDateMillis = selectedDateMillis; self.hour = hour; self.minute = minute
        precondition(isValid)
    }
    public var isValid: Bool { (0...23).contains(hour) && (0...59).contains(minute) }
}

public enum CupertinoSheetValue: String, Codable, CaseIterable, Sendable {
    case hidden = "Hidden", expanded = "Expanded", partiallyExpanded = "PartiallyExpanded"
}
public enum CupertinoSheetCommand: String, Sendable { case show, hide, expand, partialExpand }
public struct CupertinoPresentationDetent: Codable, Equatable, Sendable {
    public enum Kind: String, Codable, Sendable { case fraction, height }
    public let kind: Kind
    public let value: Double
    private init(kind: Kind, value: Double) { self.kind = kind; self.value = value }
    public static func fraction(_ value: Double) -> Self {
        precondition(value.isFinite && value > 0 && value <= 1)
        return Self(kind: .fraction, value: value)
    }
    public static func height(_ value: Double) -> Self {
        precondition(value.isFinite && value > 0)
        return Self(kind: .height, value: value)
    }
}
public struct CupertinoSheetChange: Codable, Equatable, Sendable {
    public let value: CupertinoSheetValue
}

/// Commands are explicit and monotonic: a renderer consumes each identifier once.
public struct CupertinoCommand<Value: RawRepresentable>: Sendable where Value: Sendable, Value.RawValue == String {
    public let id: Int
    public let value: Value
    public init(id: Int, value: Value) { precondition(id >= 0); self.id = id; self.value = value }
}

public enum CupertinoSwipeValue: String, Codable, CaseIterable, Sendable {
    case collapsed = "Collapsed", dismissedToEnd = "DismissedToEnd", dismissedToStart = "DismissedToStart"
    case expandedToEnd = "ExpandedToEnd", expandedToStart = "ExpandedToStart"
}
public enum CupertinoSwipeCommand: String, Sendable { case reset, snapTo, animateTo }
public enum CupertinoSwipeBehavior: String, CaseIterable, Sendable {
    case disabled = "Disabled", expandable = "Expandable", dismissible = "Dismissible"
}

public struct CupertinoNavigationEntry: Codable, Equatable, Sendable {
    public let id: String
    public let title: String
    public init(id: String, title: String) {
        precondition(!id.isEmpty && !id.contains("/"))
        self.id = id; self.title = title
    }
}
