//
//  Environment.swift
//  SwiftUICore
//
//  Environment values flow DOWN evaluation (they shape body evaluation) and
//  never cross the bridge. Two forms: object environment
//  (`@Environment(Model.self)`, injected with `.environment(model)`) and
//  keyPath environment (`@Environment(\.dismiss)`, read from EnvironmentValues).
//

/// Keyed environment values a subtree reads by keyPath.
public struct EnvironmentValues: Sendable {

    /// Dismisses the nearest presentation (a pushed nav entry or a sheet).
    public var dismiss = DismissAction { }

    public init() {}
}

/// Closes the nearest presentation context. `dismiss()` calls it.
public struct DismissAction: Sendable {

    private let action: @Sendable () -> Void

    public init(_ action: @escaping @Sendable () -> Void) {
        self.action = action
    }

    public func callAsFunction() {
        action()
    }
}

/// Per-subtree environment: keyed values plus injected objects.
public struct EnvironmentStorage {

    public var values = EnvironmentValues()
    var objects: [ObjectIdentifier: AnyObject] = [:]

    public init() {}

    mutating func set(_ object: AnyObject) {
        objects[ObjectIdentifier(type(of: object))] = object
    }

    func object<T: AnyObject>(of type: T.Type) -> T? {
        objects[ObjectIdentifier(type)] as? T
    }
}

/// Reads a value from the environment — an injected object or a keyPath value.
@propertyWrapper
public struct Environment<Value> {

    internal enum Source {
        case object
        case keyPath(KeyPath<EnvironmentValues, Value>)
    }

    internal let source: Source
    internal let box = EnvironmentBox<Value>()

    public init(_ type: Value.Type) where Value: AnyObject {
        self.source = .object
    }

    public init(_ keyPath: KeyPath<EnvironmentValues, Value>) {
        self.source = .keyPath(keyPath)
    }

    public var wrappedValue: Value {
        guard let value = box.value else {
            fatalError("@Environment read before injection — missing .environment(_:) or presentation context?")
        }
        return value
    }
}

internal final class EnvironmentBox<Value> {
    var value: Value?
}

/// Type-erased injection hook, discovered via the reflection seam.
public protocol _EnvironmentProperty {
    func _inject(from storage: EnvironmentStorage)
}

extension Environment: _EnvironmentProperty {
    public func _inject(from storage: EnvironmentStorage) {
        switch source {
        case .object:
            if let object = storage.objects[objectKey] as? Value {
                box.value = object
            }
        case .keyPath(let keyPath):
            box.value = storage.values[keyPath: keyPath]
        }
    }

    private var objectKey: ObjectIdentifier {
        ObjectIdentifier(Value.self)
    }
}

/// The `.environment(_:)` wrapper: sets an object for the subtree.
public struct _EnvironmentWriterView<Content: View>: View {

    internal let object: AnyObject
    internal let content: Content

    public typealias Body = Never
}

public extension View {
    func environment<T: AnyObject>(_ object: T) -> _EnvironmentWriterView<Self> {
        _EnvironmentWriterView(object: object, content: self)
    }

    func environmentObject<T: AnyObject>(_ object: T) -> _EnvironmentWriterView<Self> {
        _EnvironmentWriterView(object: object, content: self)
    }
}

public protocol ObservableObject: AnyObject {}

@propertyWrapper
public struct Published<Value> {
    public var wrappedValue: Value

    public init(wrappedValue value: Value) {
        self.wrappedValue = value
    }

    public init(initialValue value: Value) {
        self.wrappedValue = value
    }

    public var projectedValue: Published<Value> { self }
}

@propertyWrapper
public struct EnvironmentObject<ObjectType: AnyObject>: DynamicProperty, _EnvironmentProperty {
    internal let box = EnvironmentBox<ObjectType>()

    public init() {}

    public var wrappedValue: ObjectType {
        guard let value = box.value else {
            fatalError("@EnvironmentObject read before injection — missing .environmentObject(_:)?")
        }
        return value
    }

    public func _inject(from storage: EnvironmentStorage) {
        if let object = storage.objects[ObjectIdentifier(ObjectType.self)] as? ObjectType {
            box.value = object
        }
    }
}

@propertyWrapper
public struct StateObject<ObjectType: AnyObject>: DynamicProperty, _StatePropertyReflectable {
    internal let box: StateBox<ObjectType>

    public init(wrappedValue thunk: @autoclosure @escaping () -> ObjectType) {
        self.box = StateBox(thunk())
    }

    public var wrappedValue: ObjectType {
        box.value
    }

    public var _box: AnyObject { box }
}

@propertyWrapper
public struct ObservedObject<ObjectType: AnyObject>: DynamicProperty {
    public var wrappedValue: ObjectType

    public init(wrappedValue: ObjectType) {
        self.wrappedValue = wrappedValue
    }

    public init(initialValue: ObjectType) {
        self.wrappedValue = initialValue
    }
}

/// Injects environment values into a view's `@Environment` properties.
public enum EnvironmentInjector {

    public static func inject(_ storage: EnvironmentStorage, into view: any View) {
        for child in Mirror(reflecting: view).children {
            if let property = child.value as? _EnvironmentProperty {
                property._inject(from: storage)
            }
        }
    }
}
