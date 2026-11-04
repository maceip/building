package dev.ide.core.runtime

internal val CUPERTINO_BRIDGE_PRELUDE_SOURCE = """
@preconcurrency import SwiftUI
import Foundation
#if canImport(Darwin)
import Darwin
#elseif canImport(Android)
import Android
#elseif canImport(Glibc)
import Glibc
#endif

public typealias CGFloat = Double
public typealias ObservableObject = SwiftUI.ObservableObject
public typealias Published<Value> = SwiftUI.Published<Value>

public struct Group<Content: View>: View {
    public let content: Content
    public init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }
    public var body: some View { content }
}

public typealias LocalizedStringKey = String
public protocol ShapeStyle {}
extension Color: ShapeStyle {}
extension LinearGradient: ShapeStyle {}

public struct HierarchicalShapeStyle: ShapeStyle, Sendable {
    public let level: Int
    public init(level: Int = 0) { self.level = level }
}
public extension ShapeStyle where Self == HierarchicalShapeStyle {
    static var primary: HierarchicalShapeStyle { HierarchicalShapeStyle(level: 0) }
    static var secondary: HierarchicalShapeStyle { HierarchicalShapeStyle(level: 1) }
    static var tertiary: HierarchicalShapeStyle { HierarchicalShapeStyle(level: 2) }
    static var quaternary: HierarchicalShapeStyle { HierarchicalShapeStyle(level: 3) }
    static var quinary: HierarchicalShapeStyle { HierarchicalShapeStyle(level: 4) }
    static var placeholder: HierarchicalShapeStyle { HierarchicalShapeStyle(level: 2) }
}
public struct TintShapeStyle: ShapeStyle, Sendable {
    public init() {}
}
public extension ShapeStyle where Self == TintShapeStyle {
    static var tint: TintShapeStyle { TintShapeStyle() }
}

public struct RadialGradient: View, ShapeStyle, Sendable {
    public let colors: [Color]
    public init(colors: [Color], center: UnitPoint = .center, startRadius: Double = 0, endRadius: Double = 100) {
        self.colors = colors
    }
    public init(gradient: Gradient, center: UnitPoint = .center, startRadius: Double = 0, endRadius: Double = 100) {
        self.colors = gradient.colors
    }
    public var body: some View {
        LinearGradient(colors: colors.isEmpty ? [.clear] : colors, startPoint: .topLeading, endPoint: .bottomTrailing)
    }
}

public struct AngularGradient: View, ShapeStyle, Sendable {
    public let colors: [Color]
    public init(colors: [Color], center: UnitPoint = .center, startAngle: Angle = .zero, endAngle: Angle = .degrees(360)) {
        self.colors = colors
    }
    public init(gradient: Gradient, center: UnitPoint = .center, angle: Angle = .zero) {
        self.colors = gradient.colors
    }
    public init(gradient: Gradient, center: UnitPoint = .center, startAngle: Angle = .zero, endAngle: Angle = .degrees(360)) {
        self.colors = gradient.colors
    }
    public var body: some View {
        LinearGradient(colors: colors.isEmpty ? [.clear] : colors, startPoint: .leading, endPoint: .trailing)
    }
}

public extension Gradient {
    struct Stop: Equatable, Sendable {
        public var color: Color
        public var location: Double
        public init(color: Color, location: Double) {
            self.color = color
            self.location = location
        }
    }
    init(stops: [Stop]) {
        self.init(colors: stops.map(\.color))
    }
}

public extension Bundle {
    static var module: Bundle { .main }
}

public extension Color {
    init(_ name: String, bundle: Bundle?) {
        self.init(name)
    }
    static let teal = Color(red: 0.19, green: 0.69, blue: 0.78)
    static let cyan = Color(red: 0.0, green: 0.8, blue: 1.0)
    static let indigo = Color(red: 0.35, green: 0.34, blue: 0.84)
    static let mint = Color(red: 0.0, green: 0.78, blue: 0.75)
    static let brown = Color(red: 0.64, green: 0.52, blue: 0.37)
    static let accentColor = Color.blue
    var gradient: LinearGradient {
        LinearGradient(colors: [self, self.opacity(0.75)], startPoint: .top, endPoint: .bottom)
    }
}
public extension ShapeStyle where Self == Color {
    static var red: Color { .red }
    static var orange: Color { .orange }
    static var yellow: Color { .yellow }
    static var green: Color { .green }
    static var mint: Color { .mint }
    static var teal: Color { .teal }
    static var cyan: Color { .cyan }
    static var blue: Color { .blue }
    static var indigo: Color { .indigo }
    static var purple: Color { .purple }
    static var pink: Color { .pink }
    static var brown: Color { .brown }
    static var white: Color { .white }
    static var gray: Color { .gray }
    static var black: Color { .black }
    static var clear: Color { .clear }
    static var accentColor: Color { .accentColor }
}

public struct Locale: Equatable, Sendable {
    public var identifier: String
    public static let current = Locale(identifier: "en_US")
    public init(identifier: String = "en_US") { self.identifier = identifier }
    public func localizedString(forIdentifier id: String) -> String? { id }
}

public struct _BridgeDateFormatStyle: Sendable {
    public struct DateStyle: Sendable, Equatable {
        public let kind: String
        public init(_ kind: String) { self.kind = kind }
        public static let omitted = DateStyle("omitted")
        public static let numeric = DateStyle("numeric")
        public static let abbreviated = DateStyle("abbreviated")
        public static let long = DateStyle("long")
        public static let complete = DateStyle("complete")
    }
    public struct TimeStyle: Sendable, Equatable {
        public let kind: String
        public init(_ kind: String) { self.kind = kind }
        public static let omitted = TimeStyle("omitted")
        public static let shortened = TimeStyle("shortened")
        public static let standard = TimeStyle("standard")
        public static let complete = TimeStyle("complete")
    }
}

#if !canImport(Darwin)
public extension String {
    struct LocalizationValue: ExpressibleByStringLiteral, ExpressibleByStringInterpolation, Equatable, Hashable, Sendable {
        public let key: String
        public init(_ key: String) { self.key = key }
        public init(stringLiteral value: String) { self.key = value }
    }
    init(
        localized key: LocalizationValue,
        defaultValue: String? = nil,
        table: String? = nil,
        bundle: Bundle? = nil,
        locale: Locale = .current,
        comment: String? = nil
    ) {
        self = defaultValue ?? key.key
    }
}
#endif

public enum RoundedCornerStyle: Sendable { case circular, continuous }
public extension RoundedRectangle {
    init(cornerRadius: Double, style: RoundedCornerStyle) {
        self.init(cornerRadius: cornerRadius)
    }
    init(cornerSize: CGSize, style: RoundedCornerStyle = .continuous) {
        self.init(cornerRadius: cornerSize.width)
    }
    func trim(from: Double, to: Double) -> Self { self }
    func stroke(_ content: Color, lineWidth: Double = 1) -> Self { self }
    func stroke<S>(_ content: S, style: StrokeStyle) -> Self { self }
}
public extension Circle {
    func stroke(_ content: Color, lineWidth: Double = 1) -> Self { self }
    func trim(from: Double, to: Double) -> Self { self }
    func stroke<S>(_ content: S, style: StrokeStyle) -> Self { self }
}
public extension Capsule {
    init(style: RoundedCornerStyle) { self.init() }
    func stroke(_ content: Color, lineWidth: Double = 1) -> Self { self }
    func stroke<S>(_ content: S, style: StrokeStyle) -> Self { self }
}
public extension Rectangle {
    func stroke(_ content: Color, lineWidth: Double = 1) -> Self { self }
    func stroke<S>(_ content: S, style: StrokeStyle) -> Self { self }
}
public struct UnevenRoundedRectangle: Shape, Sendable {
    public var topLeadingRadius: Double
    public var bottomLeadingRadius: Double
    public var bottomTrailingRadius: Double
    public var topTrailingRadius: Double
    public init(
        topLeadingRadius: Double = 0,
        bottomLeadingRadius: Double = 0,
        bottomTrailingRadius: Double = 0,
        topTrailingRadius: Double = 0,
        style: RoundedCornerStyle = .continuous
    ) {
        self.topLeadingRadius = topLeadingRadius
        self.bottomLeadingRadius = bottomLeadingRadius
        self.bottomTrailingRadius = bottomTrailingRadius
        self.topTrailingRadius = topTrailingRadius
    }
    public var _shapeKind: String { "roundedRectangle" }
    public var _cornerRadius: Double? { max(topLeadingRadius, max(bottomLeadingRadius, max(bottomTrailingRadius, topTrailingRadius))) }
}
public struct Ellipse: Shape, Sendable {
    public init() {}
    public var _shapeKind: String { "circle" }
    public var _cornerRadius: Double? { nil }
}
public struct ContainerRelativeShape: Shape, Sendable {
    public init() {}
    public var _shapeKind: String { "roundedRectangle" }
    public var _cornerRadius: Double? { 12 }
}

public extension Animation {
    func repeatForever(autoreverses: Bool = true) -> Animation { self }
    func repeatCount(_ repeatCount: Int, autoreverses: Bool = true) -> Animation { self }
    func delay(_ delay: Double) -> Animation { self }
    func speed(_ speed: Double) -> Animation { self }
    static func interactiveSpring(response: Double = 0.15, dampingFraction: Double = 0.86, blendDuration: Double = 0.25) -> Animation { .spring() }
    static func interpolatingSpring(stiffness: Double, damping: Double) -> Animation { .spring() }
    static func bouncy(duration: Double = 0.5, extraBounce: Double = 0.0) -> Animation { .spring() }
    static func smooth(duration: Double = 0.5, extraBounce: Double = 0.0) -> Animation { .easeInOut(duration: duration) }
    static func snappy(duration: Double = 0.5, extraBounce: Double = 0.0) -> Animation { .spring() }
}

public struct Material: ShapeStyle, Sendable {
    public static let regularMaterial = Material()
    public static let thinMaterial = Material()
    public static let ultraThinMaterial = Material()
    public static let thickMaterial = Material()
    public static let ultraThickMaterial = Material()
    public static let bar = Material()
}
public extension ShapeStyle where Self == Material {
    static var regularMaterial: Material { .regularMaterial }
    static var thinMaterial: Material { .thinMaterial }
    static var ultraThinMaterial: Material { .ultraThinMaterial }
    static var thickMaterial: Material { .thickMaterial }
    static var ultraThickMaterial: Material { .ultraThickMaterial }
    static var bar: Material { .bar }
}

public enum ControlSize: Sendable, CaseIterable { case mini, small, regular, large, extraLarge }
public extension _ControlStyle {
    static let glassProminent = _ControlStyle.borderedProminent
    static let glass = _ControlStyle.bordered
    static let wheel = _ControlStyle.automatic
    static let navigationLink = _ControlStyle.automatic
    static let palette = _ControlStyle.segmented
}
public protocol TextFieldStyle {
    typealias _Label = Text
}
public struct RoundedBorderTextFieldStyle: TextFieldStyle, Sendable { public init() {} }
public struct PlainTextFieldStyle: TextFieldStyle, Sendable { public init() {} }
public struct DefaultTextFieldStyle: TextFieldStyle, Sendable { public init() {} }
public struct LinearProgressViewStyle: Sendable { public init() {} }
public struct CircularProgressViewStyle: Sendable { public init() {} }
public struct DefaultProgressViewStyle: Sendable { public init() {} }

public struct GaugeStyle: Sendable {
    public init() {}
    public static let automatic = GaugeStyle()
    public static let linearCapacity = GaugeStyle()
    public static let accessoryLinear = GaugeStyle()
    public static let accessoryLinearCapacity = GaugeStyle()
    public static let accessoryCircular = GaugeStyle()
    public static let accessoryCircularCapacity = GaugeStyle()
}
public struct PlainButtonStyle: ButtonStyle, Sendable {
    public init() {}
    public func makeBody(configuration: Configuration) -> some View { configuration.label }
}
public struct BorderlessButtonStyle: ButtonStyle, Sendable {
    public init() {}
    public func makeBody(configuration: Configuration) -> some View { configuration.label }
}
public struct BorderedButtonStyle: ButtonStyle, Sendable {
    public init() {}
    public func makeBody(configuration: Configuration) -> some View { configuration.label }
}
public struct BorderedProminentButtonStyle: ButtonStyle, Sendable {
    public init() {}
    public func makeBody(configuration: Configuration) -> some View { configuration.label }
}

public enum NavigationBarTitleDisplayMode: Sendable { case automatic, inline, large }
public enum TextInputAutocapitalization: Sendable { case never, words, sentences, characters }
public enum UITextContentType: String, Sendable {
    case username, password, newPassword, oneTimeCode, emailAddress, telephoneNumber, name, givenName, familyName, streetAddressLine1, streetAddressLine2, addressCity, addressState, postalCode, countryName, creditCardNumber, URL
}
public enum UITextAutocapitalizationType: Int, Sendable {
    case none = 0, words, sentences, allCharacters
}
public enum ContentTransition: Sendable {
    case identity, opacity, interpolate
    public static func numericText(countsDown: Bool = false) -> ContentTransition { .identity }
    public static func symbolEffect(_ effect: Any? = nil) -> ContentTransition { .identity }
}
public enum SymbolRenderingMode: Sendable {
    case monochrome, multicolor, hierarchical, palette
}
public enum ImageInterpolation: Sendable {
    case none, low, medium, high
}

// ============================================================================
// EnvironmentValues & @Environment / @SceneStorage Expansion
// ============================================================================

public enum ColorScheme: String, CaseIterable, Sendable {
    case light, dark
}
public enum ScenePhase: String, CaseIterable, Sendable {
    case active, inactive, background
}
public enum UserInterfaceSizeClass: String, CaseIterable, Sendable {
    case compact, regular
}
public enum LayoutDirection: String, CaseIterable, Sendable {
    case leftToRight, rightToLeft
}
public enum EditMode: String, CaseIterable, Sendable {
    case inactive, active, transient
    public var isEditing: Bool { self != .inactive }
}
public enum LegibilityWeight: String, CaseIterable, Sendable {
    case regular, bold
}
public struct PresentationMode: Sendable {
    private let dismissAction: @Sendable () -> Void
    public var isPresented: Bool
    public init(isPresented: Bool = true, dismissAction: @escaping @Sendable () -> Void = {}) {
        self.isPresented = isPresented
        self.dismissAction = dismissAction
    }
    public mutating func dismiss() {
        isPresented = false
        dismissAction()
    }
}
public struct OpenURLAction: Sendable {
    public enum Result: Sendable {
        case handled, discarded, systemAction
    }
    public init(handler: @escaping @Sendable (URL) -> Result = { _ in .handled }) {}
    public func callAsFunction(_ url: URL) {}
    public func callAsFunction(_ url: URL, completion: @escaping (Bool) -> Void) {
        completion(true)
    }
}
public struct DismissSearchAction: Sendable {
    public init() {}
    public func callAsFunction() {}
}

public protocol EnvironmentKey {
    associatedtype Value
    static var defaultValue: Self.Value { get }
}

private nonisolated(unsafe) var _customEnvironmentStorage: [ObjectIdentifier: Any] = [:]

public extension EnvironmentValues {
    subscript<K: EnvironmentKey>(key: K.Type) -> K.Value {
        get { (_customEnvironmentStorage[ObjectIdentifier(key)] as? K.Value) ?? K.defaultValue }
        set { _customEnvironmentStorage[ObjectIdentifier(key)] = newValue }
    }
    var colorScheme: ColorScheme {
        get { .light }
        set {}
    }
    var presentationMode: Binding<PresentationMode> {
        get {
            let d = self.dismiss
            return .constant(PresentationMode(isPresented: true, dismissAction: { d() }))
        }
        set {}
    }
    var openURL: OpenURLAction {
        get { OpenURLAction() }
        set {}
    }
    var scenePhase: ScenePhase {
        get { .active }
        set {}
    }
    var horizontalSizeClass: UserInterfaceSizeClass? {
        get { .compact }
        set {}
    }
    var verticalSizeClass: UserInterfaceSizeClass? {
        get { .regular }
        set {}
    }
    var isSearching: Bool {
        get { false }
        set {}
    }
    var dismissSearch: DismissSearchAction {
        get { DismissSearchAction() }
        set {}
    }
    var editMode: Binding<EditMode>? {
        get { .constant(.inactive) }
        set {}
    }
    var locale: Locale {
        get { .current }
        set {}
    }
    var calendar: Calendar {
        get { .current }
        set {}
    }
    var timeZone: TimeZone {
        get { .current }
        set {}
    }
    var layoutDirection: LayoutDirection {
        get { .leftToRight }
        set {}
    }
    var displayScale: Double {
        get { 3.0 }
        set {}
    }
    var pixelLength: Double {
        get { 1.0 / 3.0 }
        set {}
    }
    var isEnabled: Bool {
        get { true }
        set {}
    }
    var legibilityWeight: LegibilityWeight? {
        get { .regular }
        set {}
    }
    var accessibilityReduceMotion: Bool {
        get { false }
        set {}
    }
    var accessibilityReduceTransparency: Bool {
        get { false }
        set {}
    }
    var accessibilityDifferentiateWithoutColor: Bool {
        get { false }
        set {}
    }
}

public extension View {
    func environment<V>(_ keyPath: WritableKeyPath<EnvironmentValues, V>, _ value: V) -> Self { self }
    func preferredColorScheme(_ colorScheme: ColorScheme?) -> Self { self }
}

public typealias SceneStorage<Value> = AppStorage<Value>

public extension AppStorage where Value: RawRepresentable, Value.RawValue == String {
    init(wrappedValue: Value, _ key: String, store: UserDefaults? = nil) {
        let saved = UserDefaults.standard.string(forKey: key).flatMap { Value(rawValue: ${'$'}0) } ?? wrappedValue
        self = unsafeBitCast(AppStorage<String>(wrappedValue: saved.rawValue, key), to: AppStorage<Value>.self)
    }
}

// ============================================================================
// Core Container Shadowing: List, LazyVStack, LazyHStack, TabView, Picker, AsyncImage
// ============================================================================

public struct PinnedScrollableViews: OptionSet, Sendable {
    public let rawValue: Int
    public init(rawValue: Int = 0) { self.rawValue = rawValue }
    public static let sectionHeaders = PinnedScrollableViews(rawValue: 1)
    public static let sectionFooters = PinnedScrollableViews(rawValue: 2)
}

public struct LazyVStack<Content: View>: View {
    public let alignment: HorizontalAlignment
    public let spacing: Double?
    public let content: Content
    public init(
        alignment: HorizontalAlignment = .center,
        spacing: Double? = nil,
        pinnedViews: PinnedScrollableViews = .init(),
        @ViewBuilder content: () -> Content
    ) {
        self.alignment = alignment
        self.spacing = spacing
        self.content = content()
    }
    public var body: some View {
        VStack(alignment: alignment, spacing: spacing) { content }
    }
}

public struct LazyHStack<Content: View>: View {
    public let alignment: VerticalAlignment
    public let spacing: Double?
    public let content: Content
    public init(
        alignment: VerticalAlignment = .center,
        spacing: Double? = nil,
        pinnedViews: PinnedScrollableViews = .init(),
        @ViewBuilder content: () -> Content
    ) {
        self.alignment = alignment
        self.spacing = spacing
        self.content = content()
    }
    public var body: some View {
        HStack(alignment: alignment, spacing: spacing) { content }
    }
}

public struct List<Content: View>: View {
    public let content: Content
    public init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }
    public init<SelectionValue: Hashable>(selection: Binding<SelectionValue?>?, @ViewBuilder content: () -> Content) {
        self.content = content()
    }
    public init<SelectionValue: Hashable>(selection: Binding<Set<SelectionValue>>?, @ViewBuilder content: () -> Content) {
        self.content = content()
    }
    public var body: some View {
        Form { content }
    }
}

public extension List {
    init<Data: RandomAccessCollection, ID: Hashable, RowContent: View>(
        _ data: Data,
        id: KeyPath<Data.Element, ID>,
        @ViewBuilder rowContent: @escaping (Data.Element) -> RowContent
    ) where Content == ForEach<Data, ID, RowContent> {
        self.init {
            ForEach(data, id: { ${'$'}0[keyPath: id] }, content: rowContent)
        }
    }
    init<Data: RandomAccessCollection, ID: Hashable, RowContent: View>(
        _ data: Data,
        id: @escaping (Data.Element) -> ID,
        @ViewBuilder rowContent: @escaping (Data.Element) -> RowContent
    ) where Content == ForEach<Data, ID, RowContent> {
        self.init {
            ForEach(data, id: id, content: rowContent)
        }
    }
    init<Data: RandomAccessCollection, RowContent: View>(
        _ data: Data,
        @ViewBuilder rowContent: @escaping (Data.Element) -> RowContent
    ) where Data.Element: Identifiable, Content == ForEach<Data, Data.Element.ID, RowContent> {
        self.init {
            ForEach(data, content: rowContent)
        }
    }
    init<RowContent: View>(
        _ data: Range<Int>,
        @ViewBuilder rowContent: @escaping (Int) -> RowContent
    ) where Content == ForEach<Range<Int>, Int, RowContent> {
        self.init {
            ForEach(data, content: rowContent)
        }
    }
}

public struct ListStyle: Sendable {
    public init() {}
    public static let automatic = ListStyle()
    public static let plain = ListStyle()
    public static let grouped = ListStyle()
    public static let inset = ListStyle()
    public static let insetGrouped = ListStyle()
    public static let sidebar = ListStyle()
}
public typealias PlainListStyle = ListStyle
public typealias GroupedListStyle = ListStyle
public typealias InsetListStyle = ListStyle
public typealias InsetGroupedListStyle = ListStyle
public typealias SidebarListStyle = ListStyle
public typealias DefaultListStyle = ListStyle

public enum HorizontalEdge: Sendable, CaseIterable {
    case leading, trailing
    public struct Set: OptionSet, Sendable {
        public let rawValue: Int
        public init(rawValue: Int) { self.rawValue = rawValue }
        public static let leading = Set(rawValue: 1)
        public static let trailing = Set(rawValue: 2)
        public static let all: Set = [.leading, .trailing]
    }
}

public extension ForEach {
    init(_ data: Data, id: KeyPath<Data.Element, ID>, @ViewBuilder content: @escaping (Data.Element) -> Content) {
        self.init(data, id: { ${'$'}0[keyPath: id] }, content: content)
    }
    func onDelete(perform action: ((IndexSet) -> Void)?) -> Self { self }
    func onMove(perform action: ((IndexSet, Int) -> Void)?) -> Self { self }
}

public struct TabView<Content: View>: View {
    @State private var internalSelection: Int = 0
    private let externalIntSelection: Binding<Int>?
    private let content: Content

    public init(@ViewBuilder content: () -> Content) {
        self.externalIntSelection = nil
        self.content = content()
    }

    public init(selection: Binding<Int>, @ViewBuilder content: () -> Content) {
        self.externalIntSelection = selection
        self.content = content()
    }

    public init<SelectionValue: Hashable>(selection: Binding<SelectionValue>, @ViewBuilder content: () -> Content) {
        if let intBinding = selection as? Binding<Int> {
            self.externalIntSelection = intBinding
        } else {
            self.externalIntSelection = nil
        }
        self.content = content()
    }

    public var body: some View {
        SwiftUI.TabView(selection: externalIntSelection ?? _internalSelection.projectedValue) {
            content
        }
    }
}

public struct TabViewStyle: Sendable {
    public enum IndexDisplayMode: Sendable { case automatic, always, never }
    public init() {}
    public static let automatic = TabViewStyle()
    public static let page = TabViewStyle()
    public static func page(indexDisplayMode: IndexDisplayMode) -> TabViewStyle { TabViewStyle() }
}
public typealias PageTabViewStyle = TabViewStyle
public typealias DefaultTabViewStyle = TabViewStyle

public struct Picker<SelectionValue: Hashable, Content: View>: View {
    private let title: String
    private let selection: Binding<SelectionValue>
    private let content: Content

    public init<S: StringProtocol>(
        _ title: S,
        selection: Binding<SelectionValue>,
        @ViewBuilder content: () -> Content
    ) {
        self.title = String(title)
        self.selection = selection
        self.content = content()
    }

    public init<L: View>(
        selection: Binding<SelectionValue>,
        label: L,
        @ViewBuilder content: () -> Content
    ) {
        self.title = ""
        self.selection = selection
        self.content = content()
    }

    public init<L: View>(
        selection: Binding<SelectionValue>,
        @ViewBuilder content: () -> Content,
        @ViewBuilder label: () -> L
    ) {
        self.title = ""
        self.selection = selection
        self.content = content()
    }

    public var body: some View {
        let stringBinding = Binding<String>(
            get: { String(describing: selection.wrappedValue) },
            set: { raw in
                if let strVal = raw as? SelectionValue {
                    selection.wrappedValue = strVal
                } else if let intVal = Int(raw) as? SelectionValue {
                    selection.wrappedValue = intVal
                }
            }
        )
        SwiftUI.Picker(title, selection: stringBinding) {
            content
        }
    }
}

public enum AsyncImagePhase: @unchecked Sendable {
    case empty
    case success(Image)
    case failure(any Error)
    public var image: Image? {
        if case .success(let img) = self { return img }
        return nil
    }
    public var error: (any Error)? {
        if case .failure(let err) = self { return err }
        return nil
    }
}

public struct _BridgeTransaction: Sendable {
    public var animation: Animation?
    public init(animation: Animation? = nil) { self.animation = animation }
}

public struct AsyncImage<Content: View>: View {
    private let url: URL?
    private let customContent: Content?

    public init(url: URL?, scale: Double = 1.0) where Content == EmptyView {
        self.url = url
        self.customContent = nil
    }

    public init<I: View, P: View>(
        url: URL?,
        scale: Double = 1.0,
        @ViewBuilder content: @escaping (Image) -> I,
        @ViewBuilder placeholder: @escaping () -> P
    ) where Content == _ConditionalContent<I, P> {
        self.url = url
        if url != nil {
            self.customContent = ViewBuilder.buildEither(first: content(Image(systemName: "photo")))
        } else {
            self.customContent = ViewBuilder.buildEither(second: placeholder())
        }
    }

    public init(
        url: URL?,
        scale: Double = 1.0,
        transaction: _BridgeTransaction = _BridgeTransaction(),
        @ViewBuilder content: @escaping (AsyncImagePhase) -> Content
    ) {
        self.url = url
        if url != nil {
            self.customContent = content(.success(Image(systemName: "photo")))
        } else {
            self.customContent = content(.empty)
        }
    }

    public var body: some View {
        if let url {
            SwiftUI.AsyncImage(url: url)
        } else if let customContent {
            customContent
        } else {
            SwiftUI.AsyncImage(url: nil)
        }
    }
}

// ============================================================================
// NavigationView, NavigationStack(path:), NavigationPath, NavigationSplitView
// ============================================================================

public typealias NavigationView = NavigationStack

public struct NavigationPath: Equatable, @unchecked Sendable {
    private var items: [AnyHashable] = []
    public var count: Int { items.count }
    public var isEmpty: Bool { items.isEmpty }
    public init() {}
    public init<S: Sequence>(_ elements: S) where S.Element: Hashable {
        self.items = elements.map { AnyHashable(${'$'}0) }
    }
    public mutating func append<V: Hashable>(_ value: V) {
        items.append(AnyHashable(value))
    }
    public mutating func removeLast(_ k: Int = 1) {
        guard k > 0 else { return }
        if k >= items.count { items.removeAll() } else { items.removeLast(k) }
    }
}

public extension NavigationStack {
    init(path: Binding<NavigationPath>, @ViewBuilder root: () -> Root) {
        self.init(root: root)
    }
    init<Data: MutableCollection & RandomAccessCollection & RangeReplaceableCollection>(
        path: Binding<Data>,
        @ViewBuilder root: () -> Root
    ) {
        self.init(root: root)
    }
}

public struct NavigationSplitViewVisibility: Equatable, Sendable {
    public static let automatic = NavigationSplitViewVisibility()
    public static let all = NavigationSplitViewVisibility()
    public static let doubleColumn = NavigationSplitViewVisibility()
    public static let detailOnly = NavigationSplitViewVisibility()
    public init() {}
}

public struct NavigationSplitView<Sidebar: View, Content: View, Detail: View>: View {
    private let sidebar: Sidebar
    private let content: Content
    private let detail: Detail

    public init(
        columnVisibility: Binding<NavigationSplitViewVisibility> = .constant(.automatic),
        @ViewBuilder sidebar: () -> Sidebar,
        @ViewBuilder content: () -> Content,
        @ViewBuilder detail: () -> Detail
    ) {
        self.sidebar = sidebar()
        self.content = content()
        self.detail = detail()
    }

    public var body: some View {
        NavigationStack {
            sidebar
        }
    }
}

public extension NavigationSplitView where Content == EmptyView {
    init(
        columnVisibility: Binding<NavigationSplitViewVisibility> = .constant(.automatic),
        @ViewBuilder sidebar: () -> Sidebar,
        @ViewBuilder detail: () -> Detail
    ) {
        self.init(columnVisibility: columnVisibility, sidebar: sidebar, content: { EmptyView() }, detail: detail)
    }
}

public extension NavigationLink {
    init<V: View>(@ViewBuilder destination: () -> V, @ViewBuilder label: () -> Label) {
        self.init(destination: destination(), label: label)
    }
    init<V: View>(destination: V, isActive: Binding<Bool>, @ViewBuilder label: () -> Label) {
        self.init(destination: destination, label: label)
    }
    init<V: View, Tag: Hashable>(destination: V, tag: Tag, selection: Binding<Tag?>, @ViewBuilder label: () -> Label) {
        self.init(destination: destination, label: label)
    }
}
public extension NavigationLink where Label == Text {
    init<S: StringProtocol, V: View>(_ title: S, @ViewBuilder destination: () -> V) {
        self.init(destination: destination()) { Text(title) }
    }
    init<S: StringProtocol, V: View>(_ title: S, destination: V, isActive: Binding<Bool>) {
        self.init(destination: destination) { Text(title) }
    }
    init<S: StringProtocol, V: View, Tag: Hashable>(_ title: S, destination: V, tag: Tag, selection: Binding<Tag?>) {
        self.init(destination: destination) { Text(title) }
    }
}

public struct NavigationViewStyle: Sendable {
    public init() {}
    public static let automatic = NavigationViewStyle()
    public static let stack = NavigationViewStyle()
    public static let columns = NavigationViewStyle()
}
public typealias StackNavigationViewStyle = NavigationViewStyle
public typealias DoubleColumnNavigationViewStyle = NavigationViewStyle
public typealias DefaultNavigationViewStyle = NavigationViewStyle

// ============================================================================
// Section, Button, TextField, TextEditor, ScrollView, ScrollViewReader, Presentation
// ============================================================================

private func _extractFirstText(in view: Any, depth: Int = 0) -> String? {
    if depth > 8 { return nil }
    if let str = view as? String { return str }
    let m = Mirror(reflecting: view)
    if String(describing: type(of: view)) == "Text" {
        for child in m.children {
            if let s = child.value as? String { return s }
        }
    }
    for child in m.children {
        if let found = _extractFirstText(in: child.value, depth: depth + 1) {
            return found
        }
    }
    return nil
}

public extension Section {
    init<H: View>(header: H, @ViewBuilder content: () -> Content) {
        if let h = _extractFirstText(in: header) {
            self.init(h, content: content)
        } else {
            self.init(content: content)
        }
    }
    init<F: View>(footer: F, @ViewBuilder content: () -> Content) {
        let f = _extractFirstText(in: footer) ?? ""
        self.init(header: "", footer: f, content: content)
    }
    init<H: View, F: View>(header: H, footer: F, @ViewBuilder content: () -> Content) {
        let h = _extractFirstText(in: header) ?? ""
        let f = _extractFirstText(in: footer) ?? ""
        self.init(header: h, footer: f, content: content)
    }
    init<H: View>(@ViewBuilder content: () -> Content, @ViewBuilder header: () -> H) {
        self.init(header: header(), content: content)
    }
    init<F: View>(@ViewBuilder content: () -> Content, @ViewBuilder footer: () -> F) {
        self.init(footer: footer(), content: content)
    }
    init<H: View, F: View>(@ViewBuilder content: () -> Content, @ViewBuilder header: () -> H, @ViewBuilder footer: () -> F) {
        self.init(header: header(), footer: footer(), content: content)
    }
    init<H: View>(isExpanded: Binding<Bool>, @ViewBuilder content: () -> Content, @ViewBuilder header: () -> H) {
        self.init(header: header(), content: content)
    }
}

public enum ButtonRole: Sendable, Equatable {
    case destructive
    case cancel
}

public extension Button {
    init(role: ButtonRole?, action: @escaping () -> Void, @ViewBuilder label: () -> Label) {
        self.init(action: action, label: label)
    }
}
public extension Button where Label == Text {
    init<S: StringProtocol>(_ title: S, role: ButtonRole?, action: @escaping () -> Void) {
        self.init(action: action) { Text(title) }
    }
}
public extension Button {
    init<S: StringProtocol>(_ title: S, systemImage: String, role: ButtonRole? = nil, action: @escaping () -> Void) where Label == SwiftUI.Label<Text, Image> {
        self.init(action: action) { SwiftUI.Label(title, systemImage: systemImage) }
    }
}

public extension Menu {
    init<S: StringProtocol>(_ title: S, systemImage: String, @ViewBuilder content: () -> Content) where Label == SwiftUI.Label<Text, Image> {
        self.init(content: content) { SwiftUI.Label(title, systemImage: systemImage) }
    }
}

public extension TextField {
    init<S: StringProtocol>(_ title: S, text: Binding<String>, prompt: Text?, axis: ScrollView<EmptyView>.Axis = .horizontal) {
        self.init(title, text: text)
    }
    init<S: StringProtocol>(_ title: S, text: Binding<String>, axis: ScrollView<EmptyView>.Axis) {
        self.init(title, text: text)
    }
    init<S: StringProtocol>(_ title: S, text: Binding<String>, onEditingChanged: @escaping (Bool) -> Void = { _ in }, onCommit: @escaping () -> Void = {}) {
        self.init(title, text: text)
    }
    init<S: StringProtocol, V>(_ title: S, value: Binding<V>, formatter: Foundation.Formatter, onEditingChanged: @escaping (Bool) -> Void = { _ in }, onCommit: @escaping () -> Void = {}) {
        let strBinding = Binding<String>(
            get: { String(describing: value.wrappedValue) },
            set: { _ in }
        )
        self.init(title, text: strBinding)
    }
    init<S: StringProtocol, V, F>(_ title: S, value: Binding<V>, format: F, prompt: Text? = nil) {
        let strBinding = Binding<String>(
            get: { String(describing: value.wrappedValue) },
            set: { _ in }
        )
        self.init(title, text: strBinding)
    }
}

public extension SecureField {
    init<S: StringProtocol>(_ title: S, text: Binding<String>, prompt: Text?) {
        self.init(title, text: text)
    }
    init<S: StringProtocol>(_ title: S, text: Binding<String>, onCommit: @escaping () -> Void) {
        self.init(title, text: text)
    }
}

public struct TextEditor: View {
    private let text: Binding<String>
    public init(text: Binding<String>) {
        self.text = text
    }
    public var body: some View {
        TextField("", text: text)
    }
}

public typealias Axis = ScrollView<EmptyView>.Axis
public extension ScrollView {
    init(_ axes: Axis = .vertical, showsIndicators: Bool = true, @ViewBuilder content: () -> Content) {
        self.init(axes, content: content)
    }
}

public struct ScrollViewProxy: Sendable {
    public init() {}
    public func scrollTo<ID: Hashable>(_ id: ID, anchor: UnitPoint? = nil) {}
}

public struct ScrollViewReader<Content: View>: View {
    public let content: Content
    public init(@ViewBuilder content: (ScrollViewProxy) -> Content) {
        self.content = content(ScrollViewProxy())
    }
    public var body: some View { content }
}

// ============================================================================
// Modern iOS 16–18 Views: ContentUnavailableView, LabeledContent, ShareLink, Gauge, ColorPicker, ViewThatFits, Grid
// ============================================================================

public struct ContentUnavailableView<Label: View, Description: View, Actions: View>: View {
    private let label: Label
    private let description: Description
    private let actions: Actions

    public init(
        @ViewBuilder label: () -> Label,
        @ViewBuilder description: () -> Description = { EmptyView() },
        @ViewBuilder actions: () -> Actions = { EmptyView() }
    ) {
        self.label = label()
        self.description = description()
        self.actions = actions()
    }

    public var body: some View {
        VStack(spacing: 12) {
            label
            description
            actions
        }
        .padding(24)
    }
}

public extension ContentUnavailableView where Label == VStack<TupleView<(Image, Text)>>, Description == Text?, Actions == EmptyView {
    init<S: StringProtocol>(_ title: S, systemImage: String, description: Text? = nil) {
        self.init(
            label: {
                VStack(spacing: 8) {
                    Image(systemName: systemImage)
                    Text(title)
                }
            },
            description: { description },
            actions: { EmptyView() }
        )
    }
}

public extension ContentUnavailableView where Label == Text, Description == Text, Actions == EmptyView {
    static var search: ContentUnavailableView {
        ContentUnavailableView(
            label: { Text("No Results") },
            description: { Text("Check the spelling or try a new search.") },
            actions: { EmptyView() }
        )
    }
    static func search(text: String) -> ContentUnavailableView {
        ContentUnavailableView(
            label: { Text("No Results for \"\(text)\"") },
            description: { Text("Check the spelling or try a new search.") },
            actions: { EmptyView() }
        )
    }
}

public struct LabeledContent<Label: View, Content: View>: View {
    private let label: Label
    private let content: Content

    public init(@ViewBuilder content: () -> Content, @ViewBuilder label: () -> Label) {
        self.label = label()
        self.content = content()
    }

    public var body: some View {
        HStack {
            label
            Spacer()
            content.foregroundColor(.secondary)
        }
    }
}

public extension LabeledContent where Label == Text {
    init<S: StringProtocol>(_ title: S, @ViewBuilder content: () -> Content) {
        self.init(content: content, label: { Text(title) })
    }
}

public extension LabeledContent where Label == Text, Content == Text {
    init<S1: StringProtocol, S2: StringProtocol>(_ title: S1, value: S2) {
        self.init(content: { Text(value) }, label: { Text(title) })
    }
}

public struct SharePreview<Icon: View, ImageView: View>: Sendable {
    public init<S: StringProtocol>(_ title: S, image: ImageView, icon: Icon) {}
    public init<S: StringProtocol>(_ title: S) where Icon == EmptyView, ImageView == EmptyView {}
}

public struct ShareLink<Label: View>: View {
    private let label: Label
    public init(item: Any, subject: Text? = nil, message: Text? = nil, @ViewBuilder label: () -> Label) {
        self.label = label()
    }
    public init<P>(item: Any, subject: Text? = nil, message: Text? = nil, preview: P, @ViewBuilder label: () -> Label) {
        self.label = label()
    }
    public var body: some View {
        Button(action: {}) { label }
    }
}

public extension ShareLink where Label == SwiftUI.Label<Text, Image> {
    init(item: Any, subject: Text? = nil, message: Text? = nil) {
        self.init(item: item, subject: subject, message: message) {
            SwiftUI.Label("Share", systemImage: "square.and.arrow.up")
        }
    }
    init<S: StringProtocol>(_ title: S, item: Any, subject: Text? = nil, message: Text? = nil) {
        self.init(item: item, subject: subject, message: message) {
            SwiftUI.Label(title, systemImage: "square.and.arrow.up")
        }
    }
}

public struct Gauge<Label: View, CurrentValueLabel: View>: View {
    private let value: Double
    private let bounds: ClosedRange<Double>
    private let label: Label
    private let currentValueLabel: CurrentValueLabel

    public init<V: BinaryFloatingPoint>(
        value: V,
        in bounds: ClosedRange<V> = 0...1,
        @ViewBuilder label: () -> Label,
        @ViewBuilder currentValueLabel: () -> CurrentValueLabel = { EmptyView() }
    ) {
        self.value = Double(value)
        self.bounds = Double(bounds.lowerBound)...Double(bounds.upperBound)
        self.label = label()
        self.currentValueLabel = currentValueLabel()
    }

    public var body: some View {
        let span = max(0.0001, bounds.upperBound - bounds.lowerBound)
        let norm = min(1.0, max(0.0, (value - bounds.lowerBound) / span))
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                label
                Spacer()
                currentValueLabel
            }
            ProgressView(value: norm)
        }
    }
}

public struct ColorPicker<Label: View>: View {
    private let label: Label
    private let selection: Binding<Color>
    public init(selection: Binding<Color>, supportsOpacity: Bool = true, @ViewBuilder label: () -> Label) {
        self.selection = selection
        self.label = label()
    }
    public var body: some View {
        HStack {
            label
            Spacer()
            Circle().fill(selection.wrappedValue).frame(width: 24, height: 24)
        }
    }
}

public extension ColorPicker where Label == Text {
    init<S: StringProtocol>(_ title: S, selection: Binding<Color>, supportsOpacity: Bool = true) {
        self.init(selection: selection, supportsOpacity: supportsOpacity) { Text(title) }
    }
}

public struct ViewThatFits<Content: View>: View {
    public let content: Content
    public init(in axes: Axis = .vertical, @ViewBuilder content: () -> Content) {
        self.content = content()
    }
    public var body: some View { content }
}

public struct Grid<Content: View>: View {
    public let alignment: Alignment
    public let horizontalSpacing: Double?
    public let verticalSpacing: Double?
    public let content: Content
    public init(
        alignment: Alignment = .center,
        horizontalSpacing: Double? = nil,
        verticalSpacing: Double? = nil,
        @ViewBuilder content: () -> Content
    ) {
        self.alignment = alignment
        self.horizontalSpacing = horizontalSpacing
        self.verticalSpacing = verticalSpacing
        self.content = content()
    }
    public var body: some View {
        VStack(alignment: alignment.horizontal, spacing: verticalSpacing) { content }
    }
}

public struct GridRow<Content: View>: View {
    public let alignment: VerticalAlignment?
    public let content: Content
    public init(alignment: VerticalAlignment? = nil, @ViewBuilder content: () -> Content) {
        self.alignment = alignment
        self.content = content()
    }
    public var body: some View {
        HStack(alignment: alignment ?? .center) { content }
    }
}

public struct TimelineSchedule: Sendable {
    public init() {}
    public static let animation = TimelineSchedule()
    public static func animation(minimumInterval: Double? = nil, paused: Bool = false) -> TimelineSchedule { TimelineSchedule() }
    public static func periodic(from startDate: Date, by interval: TimeInterval) -> TimelineSchedule { TimelineSchedule() }
}

public struct TimelineViewDefaultContext: Sendable {
    public enum Cadence: Int, Comparable, Sendable {
        case live = 0, seconds, minutes
        public static func < (lhs: Cadence, rhs: Cadence) -> Bool { lhs.rawValue < rhs.rawValue }
    }
    public let date: Date = Date()
    public let cadence: Cadence = .live
    public init() {}
}

public struct TimelineView<Content: View>: View {
    public typealias Context = TimelineViewDefaultContext
    private let content: Content
    public init(_ schedule: TimelineSchedule = .animation, @ViewBuilder content: @escaping (Context) -> Content) {
        self.content = content(TimelineViewDefaultContext())
    }
    public var body: some View { content }
}

// ============================================================================
// Extended Presentation: sheet(item:), fullScreenCover, alert(@ViewBuilder), Alert, confirmationDialog(@ViewBuilder), ToolbarItemGroup
// ============================================================================

public struct DatePickerComponents: OptionSet, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }
    public static let hourAndMinute = DatePickerComponents(rawValue: 1)
    public static let date = DatePickerComponents(rawValue: 2)
}

public extension DatePicker {
    init(selection: Binding<Date>, displayedComponents: DatePickerComponents, @ViewBuilder label: () -> Label) {
        self.init(selection: selection, label: label)
    }
    init(selection: Binding<Date>, in range: ClosedRange<Date>, displayedComponents: DatePickerComponents = [.hourAndMinute, .date], @ViewBuilder label: () -> Label) {
        self.init(selection: selection, label: label)
    }
    init(selection: Binding<Date>, in range: PartialRangeFrom<Date>, displayedComponents: DatePickerComponents = [.hourAndMinute, .date], @ViewBuilder label: () -> Label) {
        self.init(selection: selection, label: label)
    }
    init(selection: Binding<Date>, in range: PartialRangeThrough<Date>, displayedComponents: DatePickerComponents = [.hourAndMinute, .date], @ViewBuilder label: () -> Label) {
        self.init(selection: selection, label: label)
    }
}

public extension DatePicker where Label == Text {
    init<S: StringProtocol>(_ title: S, selection: Binding<Date>, displayedComponents: DatePickerComponents) {
        self.init(title, selection: selection)
    }
    init<S: StringProtocol>(_ title: S, selection: Binding<Date>, in range: ClosedRange<Date>, displayedComponents: DatePickerComponents = [.hourAndMinute, .date]) {
        self.init(title, selection: selection)
    }
    init<S: StringProtocol>(_ title: S, selection: Binding<Date>, in range: PartialRangeFrom<Date>, displayedComponents: DatePickerComponents = [.hourAndMinute, .date]) {
        self.init(title, selection: selection)
    }
    init<S: StringProtocol>(_ title: S, selection: Binding<Date>, in range: PartialRangeThrough<Date>, displayedComponents: DatePickerComponents = [.hourAndMinute, .date]) {
        self.init(title, selection: selection)
    }
}

public struct DatePickerStyle: Sendable {
    public init() {}
    public static let automatic = DatePickerStyle()
    public static let compact = DatePickerStyle()
    public static let wheel = DatePickerStyle()
    public static let graphical = DatePickerStyle()
}
public typealias GraphicalDatePickerStyle = DatePickerStyle
public typealias WheelDatePickerStyle = DatePickerStyle
public typealias CompactDatePickerStyle = DatePickerStyle

public extension Stepper {
    init<V: Strideable>(
        value: Binding<V>,
        in bounds: ClosedRange<V>,
        step: V.Stride = 1,
        onEditingChanged: @escaping (Bool) -> Void = { _ in },
        @ViewBuilder label: () -> Label
    ) {
        self.init(
            onIncrement: {
                let next = value.wrappedValue.advanced(by: step)
                if next <= bounds.upperBound { value.wrappedValue = next }
            },
            onDecrement: {
                let prev = value.wrappedValue.advanced(by: -step)
                if prev >= bounds.lowerBound { value.wrappedValue = prev }
            },
            label: label
        )
    }
    init<V: Strideable>(
        value: Binding<V>,
        step: V.Stride = 1,
        onEditingChanged: @escaping (Bool) -> Void = { _ in },
        @ViewBuilder label: () -> Label
    ) {
        self.init(
            onIncrement: { value.wrappedValue = value.wrappedValue.advanced(by: step) },
            onDecrement: { value.wrappedValue = value.wrappedValue.advanced(by: -step) },
            label: label
        )
    }
}

public extension Slider {
    init<V: BinaryFloatingPoint>(
        value: Binding<V>,
        in bounds: ClosedRange<V> = 0...1,
        step: V.Stride,
        onEditingChanged: @escaping (Bool) -> Void = { _ in }
    ) {
        self.init(value: value, in: bounds)
    }
    init<V: BinaryFloatingPoint, L: View>(
        value: Binding<V>,
        in bounds: ClosedRange<V> = 0...1,
        onEditingChanged: @escaping (Bool) -> Void = { _ in },
        @ViewBuilder label: () -> L
    ) {
        self.init(value: value, in: bounds)
    }
    init<V: BinaryFloatingPoint, L: View, VLabel: View>(
        value: Binding<V>,
        in bounds: ClosedRange<V> = 0...1,
        step: V.Stride = 1,
        @ViewBuilder label: () -> L,
        @ViewBuilder minimumValueLabel: () -> VLabel,
        @ViewBuilder maximumValueLabel: () -> VLabel,
        onEditingChanged: @escaping (Bool) -> Void = { _ in }
    ) {
        self.init(value: value, in: bounds)
    }
}

public struct Alert: @unchecked Sendable {
    public struct Button: @unchecked Sendable {
        public let title: String
        public let role: AlertButton.Role
        public let action: @Sendable () -> Void
        public static func `default`(_ label: Text, action: (@Sendable () -> Void)? = nil) -> Button {
            Button(title: _extractFirstText(in: label) ?? "OK", role: .normal, action: action ?? {})
        }
        public static func cancel(_ label: Text, action: (@Sendable () -> Void)? = nil) -> Button {
            Button(title: _extractFirstText(in: label) ?? "Cancel", role: .cancel, action: action ?? {})
        }
        public static func cancel(_ action: (@Sendable () -> Void)? = nil) -> Button {
            Button(title: "Cancel", role: .cancel, action: action ?? {})
        }
        public static func destructive(_ label: Text, action: (@Sendable () -> Void)? = nil) -> Button {
            Button(title: _extractFirstText(in: label) ?? "Delete", role: .destructive, action: action ?? {})
        }
    }
    public let title: String
    public let message: String?
    public let buttons: [AlertButton]

    public init(title: Text, message: Text? = nil, dismissButton: Button? = nil) {
        self.title = _extractFirstText(in: title) ?? ""
        self.message = message.flatMap { _extractFirstText(in: ${'$'}0) }
        if let b = dismissButton {
            self.buttons = [AlertButton(b.title, role: b.role, action: b.action)]
        } else {
            self.buttons = [AlertButton("OK")]
        }
    }

    public init(title: Text, message: Text? = nil, primaryButton: Button, secondaryButton: Button) {
        self.title = _extractFirstText(in: title) ?? ""
        self.message = message.flatMap { _extractFirstText(in: ${'$'}0) }
        self.buttons = [
            AlertButton(primaryButton.title, role: primaryButton.role, action: primaryButton.action),
            AlertButton(secondaryButton.title, role: secondaryButton.role, action: secondaryButton.action),
        ]
    }
}

private func _extractAlertButtons(from view: Any, into out: inout [AlertButton], depth: Int = 0) {
    if depth > 8 { return }
    if let btn = view as? _AnyButton {
        let title = _extractFirstText(in: btn._labelView) ?? "OK"
        out.append(AlertButton(title, role: .normal, action: btn._action))
        return
    }
    for child in Mirror(reflecting: view).children {
        _extractAlertButtons(from: child.value, into: &out, depth: depth + 1)
    }
}

public extension View {
    func sheet<C: View>(
        isPresented: Binding<Bool>,
        onDismiss: (() -> Void)?,
        @ViewBuilder content: () -> C
    ) -> _SheetView<Self, C> {
        self.sheet(isPresented: isPresented, content: content)
    }

    func sheet<Item: Identifiable, C: View>(
        item: Binding<Item?>,
        onDismiss: (() -> Void)? = nil,
        @ViewBuilder content: @escaping (Item) -> C
    ) -> _SheetView<Self, AnyView> {
        let isPresented = Binding<Bool>(
            get: { item.wrappedValue != nil },
            set: { if !${'$'}0 { item.wrappedValue = nil; onDismiss?() } }
        )
        let body: AnyView = if let current = item.wrappedValue {
            AnyView(content(current))
        } else {
            AnyView(EmptyView())
        }
        return self.sheet(isPresented: isPresented) { body }
    }

    func fullScreenCover<C: View>(
        isPresented: Binding<Bool>,
        onDismiss: (() -> Void)? = nil,
        @ViewBuilder content: () -> C
    ) -> _SheetView<Self, C> {
        self.sheet(isPresented: isPresented, content: content)
    }

    func fullScreenCover<Item: Identifiable, C: View>(
        item: Binding<Item?>,
        onDismiss: (() -> Void)? = nil,
        @ViewBuilder content: @escaping (Item) -> C
    ) -> _SheetView<Self, AnyView> {
        self.sheet(item: item, onDismiss: onDismiss, content: content)
    }

    func alert(isPresented: Binding<Bool>, content: () -> Alert) -> _AlertView<Self> {
        let a = content()
        return self.alert(a.title, isPresented: isPresented, message: a.message, buttons: a.buttons)
    }

    func alert<S: StringProtocol, A: View>(
        _ title: S,
        isPresented: Binding<Bool>,
        @ViewBuilder actions: () -> A
    ) -> _AlertView<Self> {
        var btns: [AlertButton] = []
        _extractAlertButtons(from: actions(), into: &btns)
        if btns.isEmpty { btns = [AlertButton("OK")] }
        return self.alert(title, isPresented: isPresented, message: nil, buttons: btns)
    }

    func alert<S: StringProtocol, A: View, M: View>(
        _ title: S,
        isPresented: Binding<Bool>,
        @ViewBuilder actions: () -> A,
        @ViewBuilder message: () -> M
    ) -> _AlertView<Self> {
        var btns: [AlertButton] = []
        _extractAlertButtons(from: actions(), into: &btns)
        if btns.isEmpty { btns = [AlertButton("OK")] }
        let msg = _extractFirstText(in: message())
        return self.alert(title, isPresented: isPresented, message: msg, buttons: btns)
    }

    func alert<S: StringProtocol, A: View, M: View, T>(
        _ title: S,
        isPresented: Binding<Bool>,
        presenting data: T?,
        @ViewBuilder actions: (T) -> A,
        @ViewBuilder message: (T) -> M
    ) -> _AlertView<Self> {
        var btns: [AlertButton] = []
        var msg: String? = nil
        if let data {
            _extractAlertButtons(from: actions(data), into: &btns)
            msg = _extractFirstText(in: message(data))
        }
        if btns.isEmpty { btns = [AlertButton("OK")] }
        return self.alert(title, isPresented: isPresented, message: msg, buttons: btns)
    }

    func alert<S: StringProtocol, A: View, T>(
        _ title: S,
        isPresented: Binding<Bool>,
        presenting data: T?,
        @ViewBuilder actions: (T) -> A
    ) -> _AlertView<Self> {
        var btns: [AlertButton] = []
        if let data {
            _extractAlertButtons(from: actions(data), into: &btns)
        }
        if btns.isEmpty { btns = [AlertButton("OK")] }
        return self.alert(title, isPresented: isPresented, message: nil, buttons: btns)
    }

    func confirmationDialog<S: StringProtocol, A: View>(
        _ title: S,
        isPresented: Binding<Bool>,
        titleVisibility: Visibility = .automatic,
        @ViewBuilder actions: () -> A
    ) -> _ConfirmationDialogView<Self> {
        var btns: [AlertButton] = []
        _extractAlertButtons(from: actions(), into: &btns)
        if btns.isEmpty { btns = [AlertButton("OK")] }
        return self.confirmationDialog(title, isPresented: isPresented, titleVisibility: titleVisibility, message: nil, buttons: btns)
    }

    func confirmationDialog<S: StringProtocol, A: View, M: View>(
        _ title: S,
        isPresented: Binding<Bool>,
        titleVisibility: Visibility = .automatic,
        @ViewBuilder actions: () -> A,
        @ViewBuilder message: () -> M
    ) -> _ConfirmationDialogView<Self> {
        var btns: [AlertButton] = []
        _extractAlertButtons(from: actions(), into: &btns)
        if btns.isEmpty { btns = [AlertButton("OK")] }
        let msg = _extractFirstText(in: message())
        return self.confirmationDialog(title, isPresented: isPresented, titleVisibility: titleVisibility, message: msg, buttons: btns)
    }

    func confirmationDialog<S: StringProtocol, A: View, M: View, T>(
        _ title: S,
        isPresented: Binding<Bool>,
        titleVisibility: Visibility = .automatic,
        presenting data: T?,
        @ViewBuilder actions: (T) -> A,
        @ViewBuilder message: (T) -> M
    ) -> _ConfirmationDialogView<Self> {
        var btns: [AlertButton] = []
        var msg: String? = nil
        if let data {
            _extractAlertButtons(from: actions(data), into: &btns)
            msg = _extractFirstText(in: message(data))
        }
        if btns.isEmpty { btns = [AlertButton("OK")] }
        return self.confirmationDialog(title, isPresented: isPresented, titleVisibility: titleVisibility, message: msg, buttons: btns)
    }
}

public extension ToolbarItemPlacement {
    static let topBarLeading = ToolbarItemPlacement.navigationBarLeading
    static let topBarTrailing = ToolbarItemPlacement.navigationBarTrailing
    static let cancellationAction = ToolbarItemPlacement.navigationBarLeading
    static let confirmationAction = ToolbarItemPlacement.navigationBarTrailing
    static let destructiveAction = ToolbarItemPlacement.navigationBarTrailing
    static let primaryAction = ToolbarItemPlacement.navigationBarTrailing
    static let secondaryAction = ToolbarItemPlacement.automatic
    static let status = ToolbarItemPlacement.bottomBar
    static let keyboard = ToolbarItemPlacement.bottomBar
}

public typealias ToolbarItemGroup<Content: View> = ToolbarItem<Content>

public enum ToolbarPlacement: Sendable {
    case automatic, bottomBar, navigationBar, tabBar, windowToolbar
}

// ============================================================================
// Broad View Modifier Compatibility Extensions
// ============================================================================

public extension View {
    func background<S: ShapeStyle, Sh>(_ style: S, in shape: Sh) -> Self { self }
    func background<S: ShapeStyle>(_ style: S) -> Self { self }
    func foregroundStyle<S: ShapeStyle>(_ style: S) -> Self { self }
    func foregroundStyle<S1: ShapeStyle, S2: ShapeStyle>(_ primary: S1, _ secondary: S2) -> Self { self }
    func foregroundStyle<S1: ShapeStyle, S2: ShapeStyle, S3: ShapeStyle>(_ primary: S1, _ secondary: S2, _ tertiary: S3) -> Self { self }
    func tint<S: ShapeStyle>(_ tint: S?) -> Self { self }
    func accentColor(_ accentColor: Color?) -> Self { self }
    func controlSize(_ size: ControlSize) -> Self { self }
    func accessibilityHint(_ hint: String) -> Self { self }
    func accessibilityElement(children: Any? = nil) -> Self { self }
    func accessibilityRemoveTraits(_ traits: Any) -> Self { self }
    func layoutPriority(_ value: Double) -> Self { self }
    func fixedSize(horizontal: Bool = true, vertical: Bool = true) -> Self { self }
    func textFieldStyle<S>(_ style: S) -> Self { self }
    func listStyle(_ style: ListStyle) -> Self { self }
    func listStyle<S>(_ style: S) -> Self { self }
    func listSectionSpacing(_ spacing: ListSectionSpacing) -> Self { self }
    func listSectionSpacing(_ spacing: Double) -> Self { self }
    func listRowBackground<V: View>(_ view: V?) -> Self { self }
    func listRowInsets(_ insets: EdgeInsets?) -> Self { self }
    func listRowSeparator(_ visibility: Visibility, edges: VerticalEdge.Set = .all) -> Self { self }
    func listSectionSeparator(_ visibility: Visibility, edges: VerticalEdge.Set = .all) -> Self { self }
    func listRowSeparatorTint(_ color: Color?, edges: VerticalEdge.Set = .all) -> Self { self }
    func swipeActions<T: View>(edge: HorizontalEdge = .trailing, allowsFullSwipe: Bool = true, @ViewBuilder content: () -> T) -> Self { self }
    func badge(_ count: Int) -> Self { self }
    func badge<S: StringProtocol>(_ label: S?) -> Self { self }
    func tabViewStyle(_ style: TabViewStyle) -> Self { self }
    func tabViewStyle<S>(_ style: S) -> Self { self }
    func navigationViewStyle(_ style: NavigationViewStyle) -> Self { self }
    func navigationViewStyle<S>(_ style: S) -> Self { self }
    func datePickerStyle(_ style: DatePickerStyle) -> Self { self }
    func datePickerStyle<S>(_ style: S) -> Self { self }
    func progressViewStyle<S>(_ style: S) -> Self { self }
    func gaugeStyle(_ style: GaugeStyle) -> Self { self }
    func gaugeStyle<S>(_ style: S) -> Self { self }
    func navigationBarTitleDisplayMode(_ mode: NavigationBarTitleDisplayMode) -> Self { self }
    func navigationBarTitle<S: StringProtocol>(_ title: S) -> _NavigationTitleView<Self> { navigationTitle(title) }
    func navigationBarTitle<S: StringProtocol>(_ title: S, displayMode: NavigationBarTitleDisplayMode) -> _NavigationTitleView<Self> { navigationTitle(title) }
    func navigationBarTitle(_ title: Text) -> _NavigationTitleView<Self> { navigationTitle(title) }
    func navigationBarTitle(_ title: Text, displayMode: NavigationBarTitleDisplayMode) -> _NavigationTitleView<Self> { navigationTitle(title) }
    func navigationBarBackButtonHidden(_ hidesBackButton: Bool = true) -> Self { self }
    func navigationBarHidden(_ hidden: Bool) -> Self { self }
    func navigationBarItems<L: View, T: View>(leading: L, trailing: T) -> Self { self }
    func navigationBarItems<L: View>(leading: L) -> Self { self }
    func navigationBarItems<T: View>(trailing: T) -> Self { self }
    func toolbar(_ visibility: Visibility, for bars: ToolbarPlacement...) -> Self { self }
    func toolbarBackground(_ visibility: Visibility, for bars: ToolbarPlacement...) -> Self { self }
    func toolbarBackground<S: ShapeStyle>(_ style: S, for bars: ToolbarPlacement...) -> Self { self }
    func toolbarColorScheme(_ colorScheme: ColorScheme?, for bars: ToolbarPlacement...) -> Self { self }
    func autocorrectionDisabled(_ disabled: Bool = true) -> Self { self }
    func disableAutocorrection(_ disable: Bool?) -> Self { self }
    func textInputAutocapitalization(_ autocapitalization: TextInputAutocapitalization?) -> Self { self }
    func autocapitalization(_ style: UITextAutocapitalizationType) -> Self { self }
    func textContentType(_ textContentType: UITextContentType?) -> Self { self }
    func onSubmit(of triggers: Any = (), _ action: @escaping () -> Void) -> Self { self }
    func textSelection(_ selectability: Any) -> Self { self }
    func monospacedDigit() -> Self { self }
    func monospaced(_ isActive: Bool = true) -> Self { self }
    func kerning(_ kerning: Double) -> Self { self }
    func baselineOffset(_ baselineOffset: Double) -> Self { self }
    func strikethrough(_ isActive: Bool = true, color: Color? = nil) -> Self { self }
    func underline(_ isActive: Bool = true, color: Color? = nil) -> Self { self }
    func lineLimit(_ limit: Int, reservesSpace: Bool) -> _ModifiedContent_LineLimit<Self> { self.lineLimit(limit) }
    func lineLimit(_ limit: ClosedRange<Int>) -> _ModifiedContent_LineLimit<Self> { self.lineLimit(limit.upperBound) }
    func lineLimit(_ limit: PartialRangeFrom<Int>) -> Self { self }
    func lineSpacing(_ lineSpacing: Double) -> Self { self }
    func truncationMode(_ mode: Any) -> Self { self }
    func onChange<V: Equatable>(of value: V, initial: Bool = false, _ action: @escaping (V, V) -> Void) -> Self { self }
    func onChange<V: Equatable>(of value: V, initial: Bool = false, _ action: @escaping () -> Void) -> Self { self }
    func onChange<V: Equatable>(of value: V, perform action: @escaping (V) -> Void) -> Self { self }
    func task<T: Equatable>(id value: T, _ action: @escaping @Sendable () async -> Void) -> ModifiedContent<Self, _TaskModifier> { self.task(action) }
    func focused(_ condition: Binding<Bool>) -> Self { self }
    func onHover(perform action: @escaping (Bool) -> Void) -> Self { self }
    func allowsHitTesting(_ enabled: Bool) -> Self { self }
    func labelsHidden() -> Self { self }
    func clipped(antialiased: Bool = false) -> Self { self }
    func drawingGroup(opaque: Bool = false, colorMode: Any = ()) -> Self { self }
    func compositingGroup() -> Self { self }
    func blendMode(_ blendMode: Any) -> Self { self }
    func blur(radius: Double, opaque: Bool = false) -> Self { self }
    func brightness(_ amount: Double) -> Self { self }
    func contrast(_ amount: Double) -> Self { self }
    func saturation(_ amount: Double) -> Self { self }
    func grayscale(_ amount: Double) -> Self { self }
    func hueRotation(_ angle: Angle) -> Self { self }
    func colorInvert() -> Self { self }
    func colorMultiply(_ color: Color) -> Self { self }
    func mask<Mask: View>(alignment: Alignment = .center, @ViewBuilder _ mask: () -> Mask) -> Self { self }
    func mask<Mask: View>(_ mask: Mask) -> Self { self }
    func safeAreaInset<V: View>(edge: VerticalEdge, alignment: HorizontalAlignment = .center, spacing: Double? = nil, @ViewBuilder content: () -> V) -> Self { self }
    func safeAreaInset<V: View>(edge: HorizontalEdge, alignment: VerticalAlignment = .center, spacing: Double? = nil, @ViewBuilder content: () -> V) -> Self { self }
    func scrollIndicators(_ visibility: Visibility, axes: Axis = .vertical) -> Self { self }
    func scrollContentBackground(_ visibility: Visibility) -> Self { self }
    func scrollDismissesKeyboard(_ mode: Any) -> Self { self }
    func scrollBounceBehavior(_ behavior: Any, axes: Axis = .vertical) -> Self { self }
    func presentationDragIndicator(_ visibility: Visibility) -> Self { self }
    func presentationCornerRadius(_ cornerRadius: Double?) -> Self { self }
    func presentationBackground<S: ShapeStyle>(_ style: S) -> Self { self }
    func presentationBackground(alignment: Alignment = .center, @ViewBuilder content: () -> some View) -> Self { self }
    func presentationBackgroundInteraction(_ interaction: Any) -> Self { self }
    func presentationContentInteraction(_ interaction: Any) -> Self { self }
    func interactiveDismissDisabled(_ isDisabled: Bool = true) -> Self { self }
    func contentTransition(_ transition: ContentTransition) -> Self { self }
    func symbolRenderingMode(_ mode: SymbolRenderingMode?) -> Self { self }
    func symbolEffect(_ effect: Any, options: Any = (), value: Any = ()) -> Self { self }
    func symbolEffect(_ effect: Any, options: Any = (), isActive: Bool = true) -> Self { self }
    func imageScale(_ scale: Image.Scale) -> Self { self }
    func aspectRatio(_ ratio: Double? = nil, contentMode: ContentMode) -> ModifiedContent<Self, _ContentModeModifier> {
        self.aspectRatio(contentMode: contentMode)
    }
    func aspectRatio(_ size: CGSize, contentMode: ContentMode) -> ModifiedContent<Self, _ContentModeModifier> {
        self.aspectRatio(contentMode: contentMode)
    }
    func scaleEffect(x: Double = 1.0, y: Double = 1.0, anchor: UnitPoint = .center) -> ModifiedContent<Self, _ScaleModifier> {
        self.scaleEffect(max(x, y))
    }
    func scaleEffect(_ s: Double, anchor: UnitPoint) -> ModifiedContent<Self, _ScaleModifier> {
        self.scaleEffect(s)
    }
    func rotationEffect(_ angle: Angle, anchor: UnitPoint) -> ModifiedContent<Self, _RotationModifier> {
        self.rotationEffect(angle)
    }
    func offset(_ size: CGSize) -> ModifiedContent<Self, _OffsetModifier> {
        self.offset(x: size.width, y: size.height)
    }
    func zIndex(_ value: Double) -> Self { self }
    func id<ID: Hashable>(_ id: ID) -> Self { self }
    func redacted(reason: RedactionReasons) -> Self { self }
    func unredacted() -> Self { self }
    func privacySensitive(_ sensitive: Bool = true) -> Self { self }
    func help<S: StringProtocol>(_ text: S) -> Self { self }
    func statusBar(hidden: Bool) -> Self { self }
    func statusBarHidden(_ hidden: Bool = true) -> Self { self }
    func persistentSystemOverlays(_ visibility: Visibility) -> Self { self }
}

public typealias _ModifiedContent_LineLimit<C: View> = ModifiedContent<C, _LineLimitModifier>

public struct _ViewModifier_Content<Modifier: ViewModifier>: View {
    internal let _view: AnyView
    internal init<V: View>(_ view: V) {
        self._view = AnyView(view)
    }
    public var body: some View { _view }
}

public protocol ViewModifier {
    associatedtype Body: View
    typealias Content = _ViewModifier_Content<Self>
    @ViewBuilder func body(content: Self.Content) -> Self.Body
}

public extension ViewModifier where Body == Never {
    func body(content: Content) -> Never { fatalError() }
}

public extension View {
    func modifier<T: ViewModifier>(_ modifier: T) -> T.Body {
        modifier.body(content: _ViewModifier_Content<T>(self))
    }
}

public struct SearchFieldPlacement: Sendable {
    public enum NavigationBarDrawerDisplayMode: Sendable {
        case automatic, always
    }
    public init() {}
    public static let automatic = SearchFieldPlacement()
    public static let navigationBarDrawer = SearchFieldPlacement()
    public static let sidebar = SearchFieldPlacement()
    public static let toolbar = SearchFieldPlacement()
    public static func navigationBarDrawer(displayMode: NavigationBarDrawerDisplayMode) -> SearchFieldPlacement {
        SearchFieldPlacement()
    }
}

public extension View {
    func searchable(text: Binding<String>, placement: SearchFieldPlacement, prompt: String? = nil) -> _SearchableView<Self> {
        let p: String? = prompt
        return self.searchable(text: text, prompt: p)
    }
    func searchable<S: StringProtocol>(text: Binding<String>, placement: SearchFieldPlacement, prompt: S) -> _SearchableView<Self> {
        let p: String? = String(prompt)
        return self.searchable(text: text, prompt: p)
    }
    func searchable(text: Binding<String>, placement: SearchFieldPlacement = .automatic, prompt: Text) -> _SearchableView<Self> {
        let p: String? = _extractFirstText(in: prompt)
        return self.searchable(text: text, prompt: p)
    }
}

public struct ListSectionSpacing: Sendable {
    public init() {}
    public static let `default` = ListSectionSpacing()
    public static let compact = ListSectionSpacing()
    public static func custom(_ spacing: Double) -> ListSectionSpacing { ListSectionSpacing() }
}

public enum VerticalEdge: Sendable, CaseIterable {
    case top, bottom
    public struct Set: OptionSet, Sendable {
        public let rawValue: Int
        public init(rawValue: Int) { self.rawValue = rawValue }
        public static let top = Set(rawValue: 1)
        public static let bottom = Set(rawValue: 2)
        public static let all: Set = [.top, .bottom]
    }
}

public struct RedactionReasons: OptionSet, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }
    public static let placeholder = RedactionReasons(rawValue: 1)
    public static let privacy = RedactionReasons(rawValue: 2)
    public static let invalidated = RedactionReasons(rawValue: 4)
}

public extension Image {
    enum Scale: Sendable { case small, medium, large }
    enum TemplateRenderingMode: Sendable { case template, original }
    init(_ name: String, bundle: Bundle?) { self.init(name) }
    init(decorative name: String, bundle: Bundle? = nil) { self.init(name) }
    func renderingMode(_ renderingMode: TemplateRenderingMode?) -> Image { self }
    func interpolation(_ interpolation: ImageInterpolation) -> Image { self }
    func antialiased(_ isAntialiased: Bool) -> Image { self }
    func symbolRenderingMode(_ mode: SymbolRenderingMode?) -> Image { self }
}

// ============================================================================
// Scene, WindowGroup Modifiers, Settings, & @UIApplicationDelegateAdaptor
// ============================================================================

public struct Settings<Content: View>: Scene {
    public let content: Content
    public init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }
}

public extension SceneBuilder {
    static func buildBlock<S0: Scene, S1: Scene>(_ s0: S0, _ s1: S1) -> S0 { s0 }
    static func buildBlock<S0: Scene, S1: Scene, S2: Scene>(_ s0: S0, _ s1: S1, _ s2: S2) -> S0 { s0 }
}

public extension WindowGroup {
    func environment<T: AnyObject>(_ object: T) -> WindowGroup<_EnvironmentWriterView<Content>> {
        WindowGroup<_EnvironmentWriterView<Content>> {
            content.environment(object)
        }
    }
    func environmentObject<T: AnyObject>(_ object: T) -> WindowGroup<_EnvironmentWriterView<Content>> {
        WindowGroup<_EnvironmentWriterView<Content>> {
            content.environmentObject(object)
        }
    }
}

public extension Scene {
    func environment<V>(_ keyPath: WritableKeyPath<EnvironmentValues, V>, _ value: V) -> Self { self }
    func defaultSize(width: Double, height: Double) -> Self { self }
    func defaultSize(_ size: CGSize) -> Self { self }
    func windowResizability(_ resizability: Any) -> Self { self }
    func windowStyle(_ style: Any) -> Self { self }
    func commands(@ViewBuilder content: () -> some View) -> Self { self }
    func commands(content: () -> Any) -> Self { self }
    func onChange<V: Equatable>(of value: V, perform action: @escaping (V) -> Void) -> Self { self }
    func onChange<V: Equatable>(of value: V, initial: Bool = false, _ action: @escaping () -> Void) -> Self { self }
    func onChange<V: Equatable>(of value: V, initial: Bool = false, _ action: @escaping (V, V) -> Void) -> Self { self }
    func backgroundTask(_ task: Any, action: @escaping @Sendable () async -> Void) -> Self { self }
}

@propertyWrapper
public struct StateObject<ObjectType: AnyObject>: DynamicProperty, _StatePropertyReflectable {
    private var _inner: SwiftUI.StateObject<ObjectType>

    @dynamicMemberLookup
    public struct Wrapper {
        internal let object: ObjectType
        public subscript<Subject>(dynamicMember keyPath: ReferenceWritableKeyPath<ObjectType, Subject>) -> Binding<Subject> {
            Binding(
                get: { object[keyPath: keyPath] },
                set: { object[keyPath: keyPath] = ${'$'}0 }
            )
        }
    }

    public init(wrappedValue thunk: @autoclosure @escaping () -> ObjectType) {
        self._inner = SwiftUI.StateObject(wrappedValue: thunk())
    }

    public var wrappedValue: ObjectType {
        _inner.wrappedValue
    }

    public var projectedValue: Wrapper {
        Wrapper(object: _inner.wrappedValue)
    }

    public var _box: AnyObject {
        _inner._box
    }
}

@propertyWrapper
public struct ObservedObject<ObjectType: AnyObject>: DynamicProperty {
    @dynamicMemberLookup
    public struct Wrapper {
        internal let object: ObjectType
        public subscript<Subject>(dynamicMember keyPath: ReferenceWritableKeyPath<ObjectType, Subject>) -> Binding<Subject> {
            Binding(
                get: { object[keyPath: keyPath] },
                set: { object[keyPath: keyPath] = ${'$'}0 }
            )
        }
    }

    public var wrappedValue: ObjectType

    public init(wrappedValue: ObjectType) {
        self.wrappedValue = wrappedValue
    }

    public init(initialValue: ObjectType) {
        self.wrappedValue = initialValue
    }

    public var projectedValue: Wrapper {
        Wrapper(object: wrappedValue)
    }
}

@propertyWrapper
public struct Environment<Value>: DynamicProperty, _EnvironmentProperty {
    private final class _Box {
        var injected: Value?
    }
    private let _inner: SwiftUI.Environment<Value>
    private let _keyPath: KeyPath<EnvironmentValues, Value>?
    private let _box = _Box()

    public init(_ type: Value.Type) where Value: AnyObject {
        self._inner = SwiftUI.Environment(type)
        self._keyPath = nil
    }

    public init(_ keyPath: KeyPath<EnvironmentValues, Value>) {
        self._inner = SwiftUI.Environment(keyPath)
        self._keyPath = keyPath
    }

    public var wrappedValue: Value {
        if let v = _box.injected {
            return v
        }
        if let kp = _keyPath {
            return EnvironmentValues()[keyPath: kp]
        }
        return _inner.wrappedValue
    }

    public func _inject(from storage: EnvironmentStorage) {
        if let kp = _keyPath {
            _box.injected = storage.values[keyPath: kp]
        }
        _inner._inject(from: storage)
    }
}

@propertyWrapper
public struct EnvironmentObject<ObjectType: AnyObject>: DynamicProperty, _EnvironmentProperty {
    private let _inner = SwiftUI.EnvironmentObject<ObjectType>()

    @dynamicMemberLookup
    public struct Wrapper {
        internal let object: ObjectType
        public subscript<Subject>(dynamicMember keyPath: ReferenceWritableKeyPath<ObjectType, Subject>) -> Binding<Subject> {
            Binding(
                get: { object[keyPath: keyPath] },
                set: { object[keyPath: keyPath] = ${'$'}0 }
            )
        }
    }

    public init() {}

    public var wrappedValue: ObjectType {
        _inner.wrappedValue
    }

    public var projectedValue: Wrapper {
        Wrapper(object: _inner.wrappedValue)
    }

    public func _inject(from storage: EnvironmentStorage) {
        _inner._inject(from: storage)
    }
}

@propertyWrapper
@dynamicMemberLookup
public struct Bindable<Value: AnyObject>: DynamicProperty {
    @dynamicMemberLookup
    public struct Wrapper {
        internal let object: Value
        public subscript<Subject>(dynamicMember keyPath: ReferenceWritableKeyPath<Value, Subject>) -> Binding<Subject> {
            Binding(
                get: { object[keyPath: keyPath] },
                set: { object[keyPath: keyPath] = ${'$'}0 }
            )
        }
    }

    public var wrappedValue: Value

    public init(wrappedValue: Value) {
        self.wrappedValue = wrappedValue
    }

    public init(_ wrappedValue: Value) {
        self.wrappedValue = wrappedValue
    }

    public var projectedValue: Wrapper {
        Wrapper(object: wrappedValue)
    }

    public subscript<Subject>(dynamicMember keyPath: ReferenceWritableKeyPath<Value, Subject>) -> Binding<Subject> {
        Binding(
            get: { wrappedValue[keyPath: keyPath] },
            set: { wrappedValue[keyPath: keyPath] = ${'$'}0 }
        )
    }
}

@propertyWrapper
public struct UIApplicationDelegateAdaptor<DelegateType: AnyObject>: @unchecked Sendable {
    private var _storage: DelegateType?
    public var wrappedValue: DelegateType { _storage! }
    public init(_ delegateType: DelegateType.Type = DelegateType.self) {
        self._storage = nil
    }
}

@propertyWrapper
public struct NSApplicationDelegateAdaptor<DelegateType: AnyObject>: @unchecked Sendable {
    private var _storage: DelegateType?
    public var wrappedValue: DelegateType { _storage! }
    public init(_ delegateType: DelegateType.Type = DelegateType.self) {
        self._storage = nil
    }
}

// ============================================================================
// SwiftData & CoreData Shims
// ============================================================================

public typealias PersistentIdentifier = ObjectIdentifier
public protocol PersistentModel: AnyObject, Identifiable, Hashable {}
public extension PersistentModel {
    var id: PersistentIdentifier { ObjectIdentifier(self) }
    var persistentModelID: PersistentIdentifier { ObjectIdentifier(self) }
    static func == (lhs: Self, rhs: Self) -> Bool { lhs === rhs }
    func hash(into hasher: inout Hasher) { hasher.combine(ObjectIdentifier(self)) }
}

public final class ModelContext: @unchecked Sendable {
    public var autosaveEnabled: Bool = true
    public init() {}
    public init(_ container: ModelContainer) {}
    public func insert<T>(_ model: T) {}
    public func delete<T>(_ model: T) {}
    public func save() throws {}
    public func processPendingChanges() {}
    public func transaction(block: () throws -> Void) throws {
        try block()
    }
    public func fetch<T>(_ descriptor: FetchDescriptor<T>) throws -> [T] { [] }
}

public final class ModelContainer: @unchecked Sendable {
    public let mainContext = ModelContext()
    public init(for types: Any.Type..., configurations: ModelConfiguration...) throws {}
    public init(for schema: Schema, configurations: [ModelConfiguration] = []) throws {}
}

public struct ModelConfiguration: Sendable {
    public init(_ name: String? = nil, schema: Schema? = nil, isStoredInMemoryOnly: Bool = false, allowsSave: Bool = true) {}
}

public struct Schema: Sendable {
    public init(_ types: [Any.Type]) {}
}

public enum SortOrder: Sendable {
    case forward, reverse
}

public struct SortDescriptor<Compared>: Sendable {
    public init(_ keyPath: AnyKeyPath, order: SortOrder = .forward) {}
}

public struct Predicate<each Input>: Sendable {
    public init(_ builder: (repeat each Input) -> Bool) {}
}

public struct FetchDescriptor<T>: Sendable {
    public init(predicate: Any? = nil, sortBy: [SortDescriptor<T>] = []) {}
}

@propertyWrapper
public struct Query<Element>: DynamicProperty {
    public var wrappedValue: [Element]
    public init(
        filter: Any? = nil,
        sort: [Any] = [],
        animation: Animation? = nil
    ) {
        self.wrappedValue = []
    }
    public init<Value>(
        filter: Any? = nil,
        sort keyPath: KeyPath<Element, Value>,
        order: SortOrder = .forward,
        animation: Animation? = nil
    ) {
        self.wrappedValue = []
    }
}

public extension EnvironmentValues {
    var modelContext: ModelContext {
        get { ModelContext() }
        set {}
    }
}

public extension View {
    func modelContainer(_ container: ModelContainer) -> Self { self }
    func modelContainer(for modelType: Any.Type, inMemory: Bool = false, isAutosaveEnabled: Bool = true, isUndoEnabled: Bool = false, onSetup: ((Result<ModelContainer, any Error>) -> Void)? = nil) -> Self { self }
    func modelContainer(for modelTypes: [Any.Type], inMemory: Bool = false, isAutosaveEnabled: Bool = true, isUndoEnabled: Bool = false, onSetup: ((Result<ModelContainer, any Error>) -> Void)? = nil) -> Self { self }
}

public extension Scene {
    func modelContainer(_ container: ModelContainer) -> Self { self }
    func modelContainer(for modelType: Any.Type, inMemory: Bool = false, isAutosaveEnabled: Bool = true, isUndoEnabled: Bool = false, onSetup: ((Result<ModelContainer, any Error>) -> Void)? = nil) -> Self { self }
    func modelContainer(for modelTypes: [Any.Type], inMemory: Bool = false, isAutosaveEnabled: Bool = true, isUndoEnabled: Bool = false, onSetup: ((Result<ModelContainer, any Error>) -> Void)? = nil) -> Self { self }
}

open class NSManagedObject: NSObject, @unchecked Sendable {
    public override init() { super.init() }
    public init(context: NSManagedObjectContext) { super.init() }
}

open class NSManagedObjectContext: @unchecked Sendable {
    public var hasChanges: Bool = false
    public var automaticallyMergesChangesFromParent: Bool = true
    public init() {}
    public func save() throws {}
    public func insert(_ object: NSManagedObject) {}
    public func delete(_ object: NSManagedObject) {}
    public func fetch<T>(_ request: NSFetchRequest<T>) throws -> [T] { [] }
    public func perform(_ block: @escaping () -> Void) { block() }
    public func performAndWait(_ block: () -> Void) { block() }
}

public struct NSPersistentStoreDescription: Sendable {
    public var url: URL?
    public init(url: URL? = nil) { self.url = url }
}

open class NSPersistentContainer: @unchecked Sendable {
    public let name: String
    public let viewContext = NSManagedObjectContext()
    public var persistentStoreDescriptions: [NSPersistentStoreDescription] = [NSPersistentStoreDescription()]
    public init(name: String) { self.name = name }
    public func loadPersistentStores(completionHandler block: @escaping (NSPersistentStoreDescription, (any Error)?) -> Void) {
        block(NSPersistentStoreDescription(), nil)
    }
    public func newBackgroundContext() -> NSManagedObjectContext { NSManagedObjectContext() }
}

open class NSPersistentCloudKitContainer: NSPersistentContainer, @unchecked Sendable {}

public class NSFetchRequest<ResultType>: @unchecked Sendable {
    public var sortDescriptors: [NSSortDescriptor]?
    public var predicate: NSPredicate?
    public var fetchLimit: Int = 0
    public init(entityName: String = "") {}
}

public class NSSortDescriptor: @unchecked Sendable {
    public init(key: String?, ascending: Bool) {}
    public init<Root, Value>(keyPath: KeyPath<Root, Value>, ascending: Bool) {}
}

public struct FetchedResults<Result>: RandomAccessCollection, @unchecked Sendable {
    private let items: [Result] = []
    public var startIndex: Int { 0 }
    public var endIndex: Int { items.count }
    public subscript(position: Int) -> Result { items[position] }
}

@propertyWrapper
public struct FetchRequest<Result>: DynamicProperty {
    public var wrappedValue: FetchedResults<Result> = FetchedResults()
    public init(
        sortDescriptors: [NSSortDescriptor],
        predicate: NSPredicate? = nil,
        animation: Animation? = nil
    ) {}
    public init(
        entity: Any? = nil,
        sortDescriptors: [Any] = [],
        predicate: NSPredicate? = nil,
        animation: Animation? = nil
    ) {}
}

public extension EnvironmentValues {
    var managedObjectContext: NSManagedObjectContext {
        get { NSManagedObjectContext() }
        set {}
    }
}

// ============================================================================
// Swift Charts Shims
// ============================================================================

public struct PlottableValue<Value>: @unchecked Sendable {
    public let label: String
    public let value: Value
    public static func value<S: StringProtocol>(_ label: S, _ value: Value) -> PlottableValue<Value> {
        PlottableValue(label: String(label), value: value)
    }
    public static func value(_ label: Text, _ value: Value) -> PlottableValue<Value> {
        PlottableValue(label: _extractFirstText(in: label) ?? "", value: value)
    }
}

public enum InterpolationMethod: Sendable {
    case linear, cardinal, catmullRom, monotone, stepStart, stepCenter, stepEnd
}

public protocol ChartContent: View {}

public extension ChartContent {
    func foregroundStyle(_ color: Color) -> Self { self }
    func foregroundStyle<V>(by value: PlottableValue<V>) -> Self { self }
    func symbol<V>(by value: PlottableValue<V>) -> Self { self }
    func position<V>(by value: PlottableValue<V>) -> Self { self }
    func interpolationMethod(_ method: InterpolationMethod) -> Self { self }
    func lineStyle(_ style: StrokeStyle) -> Self { self }
    func cornerRadius(_ radius: Double) -> Self { self }
    func opacity(_ opacity: Double) -> Self { self }
    func annotation(position: Any = (), alignment: Alignment = .center, spacing: Double? = nil, @ViewBuilder content: () -> some View) -> Self { self }
}

public struct MarkDimension: Sendable {
    public static let automatic = MarkDimension()
    public static func fixed(_ value: Double) -> MarkDimension { MarkDimension() }
    public static func ratio(_ value: Double) -> MarkDimension { MarkDimension() }
    public static func inset(_ value: Double) -> MarkDimension { MarkDimension() }
}

public struct BarMark: ChartContent, Sendable {
    public let xLabel: String
    public let yText: String
    public init<X, Y>(x: PlottableValue<X>, y: PlottableValue<Y>, width: MarkDimension = .automatic, height: MarkDimension = .automatic, stacking: Any? = nil) {
        self.xLabel = "\(x.value)"
        self.yText = "\(y.value)"
    }
    public var body: some View {
        HStack {
            Text(xLabel).font(.caption)
            Spacer()
            Text(yText).font(.caption).bold()
        }
    }
}

public struct LineMark: ChartContent, Sendable {
    public let xLabel: String
    public let yText: String
    public init<X, Y>(x: PlottableValue<X>, y: PlottableValue<Y>) {
        self.xLabel = "\(x.value)"
        self.yText = "\(y.value)"
    }
    public var body: some View {
        HStack {
            Text(xLabel).font(.caption)
            Spacer()
            Text(yText).font(.caption).bold()
        }
    }
}

public struct AreaMark: ChartContent, Sendable {
    public init<X, Y>(x: PlottableValue<X>, y: PlottableValue<Y>, stacking: Any? = nil) {}
    public var body: some View { EmptyView() }
}

public struct PointMark: ChartContent, Sendable {
    public init<X, Y>(x: PlottableValue<X>, y: PlottableValue<Y>) {}
    public var body: some View { EmptyView() }
}

public struct RuleMark: ChartContent, Sendable {
    public init<X>(x: PlottableValue<X>) {}
    public init<Y>(y: PlottableValue<Y>) {}
    public var body: some View { Divider() }
}

public struct SectorMark: ChartContent, Sendable {
    public let angleText: String
    public init<V>(angle: PlottableValue<V>, innerRadius: MarkDimension = .automatic, outerRadius: MarkDimension = .automatic, angularInset: Double = 0) {
        self.angleText = "\(angle.label): \(angle.value)"
    }
    public var body: some View {
        Text(angleText).font(.caption)
    }
}

public struct RectangleMark: ChartContent, Sendable {
    public init<X, Y>(x: PlottableValue<X>, y: PlottableValue<Y>, width: MarkDimension = .automatic, height: MarkDimension = .automatic) {}
    public var body: some View { EmptyView() }
}

public struct Chart<Content: View>: View {
    public let content: Content
    public init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }
    public init<Data: RandomAccessCollection, ID: Hashable, RowContent: View>(
        _ data: Data,
        id: KeyPath<Data.Element, ID>,
        @ViewBuilder content: @escaping (Data.Element) -> RowContent
    ) where Content == ForEach<Data, ID, RowContent> {
        self.content = ForEach(data, id: id, content: content)
    }
    public init<Data: RandomAccessCollection, RowContent: View>(
        _ data: Data,
        @ViewBuilder content: @escaping (Data.Element) -> RowContent
    ) where Data.Element: Identifiable, Content == ForEach<Data, Data.Element.ID, RowContent> {
        self.content = ForEach(data, content: content)
    }
    public var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            content
        }
        .padding(12)
    }
}

public struct AxisMarks: View {
    public init(position: Any? = nil, values: Any? = nil) {}
    public init<C: View>(position: Any? = nil, values: Any? = nil, @ViewBuilder content: () -> C) {}
    public var body: some View { EmptyView() }
}
public struct AxisGridLine: View {
    public init(centered: Bool? = nil, stroke: StrokeStyle? = nil) {}
    public var body: some View { EmptyView() }
}
public struct AxisTick: View {
    public init(centered: Bool? = nil, length: Any? = nil, stroke: StrokeStyle? = nil) {}
    public var body: some View { EmptyView() }
}
public struct AxisValueLabel: View {
    public init(centered: Bool? = nil, anchor: UnitPoint? = nil, multiLabelAlignment: Alignment? = nil) {}
    public init<C: View>(@ViewBuilder content: () -> C) {}
    public var body: some View { EmptyView() }
}

public extension View {
    func chartXAxis(_ visibility: Visibility) -> Self { self }
    func chartXAxis<C: View>(@ViewBuilder content: () -> C) -> Self { self }
    func chartYAxis(_ visibility: Visibility) -> Self { self }
    func chartYAxis<C: View>(@ViewBuilder content: () -> C) -> Self { self }
    func chartLegend(_ visibility: Visibility) -> Self { self }
    func chartLegend<C: View>(position: Any = (), alignment: Alignment = .center, spacing: Double? = nil, @ViewBuilder content: () -> C) -> Self { self }
    func chartForegroundStyleScale(_ mapping: Any) -> Self { self }
    func chartYScale(domain: Any, range: Any? = nil) -> Self { self }
    func chartXScale(domain: Any, range: Any? = nil) -> Self { self }
}

// ============================================================================
// MapKit & CoreLocation Modern iOS 17+ Shims
// ============================================================================

public extension CLLocationCoordinate2D {
    static let zero = CLLocationCoordinate2D(latitude: 0, longitude: 0)
}

public struct MapCamera: Equatable, Sendable {
    public var centerCoordinate: CLLocationCoordinate2D
    public var distance: Double
    public var heading: Double
    public var pitch: Double
    public init(centerCoordinate: CLLocationCoordinate2D, distance: Double, heading: Double = 0, pitch: Double = 0) {
        self.centerCoordinate = centerCoordinate
        self.distance = distance
        self.heading = heading
        self.pitch = pitch
    }
}

public struct MapCameraPosition: Equatable, Sendable {
    public var region: MKCoordinateRegion?
    public static let automatic = MapCameraPosition(region: MKCoordinateRegion(
        center: CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194),
        span: MKCoordinateSpan(latitudeDelta: 0.05, longitudeDelta: 0.05)
    ))
    public static func region(_ region: MKCoordinateRegion) -> MapCameraPosition {
        MapCameraPosition(region: region)
    }
    public static func camera(_ camera: MapCamera) -> MapCameraPosition {
        MapCameraPosition(region: MKCoordinateRegion(
            center: camera.centerCoordinate,
            span: MKCoordinateSpan(latitudeDelta: 0.05, longitudeDelta: 0.05)
        ))
    }
    public static func userLocation(followsHeading: Bool = false, fallback: MapCameraPosition = .automatic) -> MapCameraPosition {
        fallback
    }
}

public struct Marker<Label: View>: View {
    public let title: String
    public let coordinate: CLLocationCoordinate2D
    public init<S: StringProtocol>(_ title: S, coordinate: CLLocationCoordinate2D) where Label == Text {
        self.title = String(title)
        self.coordinate = coordinate
    }
    public init<S: StringProtocol>(_ title: S, systemImage: String, coordinate: CLLocationCoordinate2D) where Label == Text {
        self.title = String(title)
        self.coordinate = coordinate
    }
    public init(coordinate: CLLocationCoordinate2D, @ViewBuilder label: () -> Label) {
        self.title = _extractFirstText(in: label()) ?? "Pin"
        self.coordinate = coordinate
    }
    public func tint(_ color: Color?) -> Self { self }
    public var body: some View { EmptyView() }
}

public struct Annotation<Content: View>: View {
    public let title: String
    public let coordinate: CLLocationCoordinate2D
    public let content: Content
    public init<S: StringProtocol>(_ title: S, coordinate: CLLocationCoordinate2D, anchor: UnitPoint = .center, @ViewBuilder content: () -> Content) {
        self.title = String(title)
        self.coordinate = coordinate
        self.content = content()
    }
    public var body: some View { content }
}

public struct UserAnnotation: View {
    public init() {}
    public var body: some View { EmptyView() }
}

public extension Map {
    init(
        position: Binding<MapCameraPosition> = .constant(.automatic),
        bounds: Any? = nil,
        interactionModes: Any? = nil
    ) {
        let reg = position.wrappedValue.region ?? MKCoordinateRegion(
            center: CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194),
            span: MKCoordinateSpan(latitudeDelta: 0.05, longitudeDelta: 0.05)
        )
        self.init(coordinateRegion: .constant(reg))
    }
    init<C: View>(
        position: Binding<MapCameraPosition> = .constant(.automatic),
        bounds: Any? = nil,
        interactionModes: Any? = nil,
        @ViewBuilder content: () -> C
    ) {
        let reg = position.wrappedValue.region ?? MKCoordinateRegion(
            center: CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194),
            span: MKCoordinateSpan(latitudeDelta: 0.05, longitudeDelta: 0.05)
        )
        self.init(coordinateRegion: .constant(reg))
    }
    init<C: View>(
        initialPosition: MapCameraPosition,
        bounds: Any? = nil,
        interactionModes: Any? = nil,
        @ViewBuilder content: () -> C = { EmptyView() }
    ) {
        let reg = initialPosition.region ?? MKCoordinateRegion(
            center: CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194),
            span: MKCoordinateSpan(latitudeDelta: 0.05, longitudeDelta: 0.05)
        )
        self.init(coordinateRegion: .constant(reg))
    }
}

public struct MapStyle: Sendable {
    public static let standard = MapStyle()
    public static let imagery = MapStyle()
    public static let hybrid = MapStyle()
    public init() {}
}
public extension View {
    func mapStyle(_ style: MapStyle) -> Self { self }
    func mapControls(@ViewBuilder _ content: () -> some View) -> Self { self }
}
public struct MapUserLocationButton: View { public init() {}; public var body: some View { EmptyView() } }
public struct MapCompass: View { public init() {}; public var body: some View { EmptyView() } }
public struct MapScaleView: View { public init() {}; public var body: some View { EmptyView() } }
public struct MapPitchToggle: View { public init() {}; public var body: some View { EmptyView() } }

public enum CLAuthorizationStatus: Int32, Sendable {
    case notDetermined = 0, restricted, denied, authorizedAlways, authorizedWhenInUse
}
public class CLLocation: NSObject, @unchecked Sendable {
    public let coordinate: CLLocationCoordinate2D
    public let altitude: Double
    public init(latitude: Double = 37.7749, longitude: Double = -122.4194) {
        self.coordinate = CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
        self.altitude = 0
        super.init()
    }
    public func distance(from location: CLLocation) -> Double { 0 }
}
public protocol CLLocationManagerDelegate: AnyObject {}
open class CLLocationManager: NSObject, @unchecked Sendable {
    public weak var delegate: (any CLLocationManagerDelegate)?
    public var desiredAccuracy: Double = 0
    public var distanceFilter: Double = 0
    public var location: CLLocation? = CLLocation()
    public var authorizationStatus: CLAuthorizationStatus = .authorizedWhenInUse
    public override init() { super.init() }
    public func requestWhenInUseAuthorization() {}
    public func requestAlwaysAuthorization() {}
    public func startUpdatingLocation() {}
    public func stopUpdatingLocation() {}
    public func requestLocation() {}
    public static func locationServicesEnabled() -> Bool { true }
}
public let kCLLocationAccuracyBest: Double = -1.0
public let kCLLocationAccuracyNearestTenMeters: Double = 10.0
public let kCLLocationAccuracyHundredMeters: Double = 100.0
public let kCLLocationAccuracyKilometer: Double = 1000.0

// ============================================================================
// Combine, OSLog, Network, UserNotifications, LocalAuth, AuthServices, StoreKit, UTType, CryptoKit, TipKit
// ============================================================================

public protocol Cancellable {
    func cancel()
}
public final class AnyCancellable: Cancellable, Hashable, @unchecked Sendable {
    private let cancelAction: () -> Void
    public init(_ cancel: @escaping () -> Void = {}) { self.cancelAction = cancel }
    public func cancel() { cancelAction() }
    public func store(in set: inout Set<AnyCancellable>) { set.insert(self) }
    public static func == (lhs: AnyCancellable, rhs: AnyCancellable) -> Bool { lhs === rhs }
    public func hash(into hasher: inout Hasher) { hasher.combine(ObjectIdentifier(self)) }
}
public struct AnyPublisher<Output, Failure: Error>: Sendable {
    public init() {}
    public func sink(receiveCompletion: @escaping (Any) -> Void = { _ in }, receiveValue: @escaping (Output) -> Void) -> AnyCancellable {
        AnyCancellable()
    }
}
public final class PassthroughSubject<Output, Failure: Error>: @unchecked Sendable {
    public init() {}
    public func send(_ input: Output) {}
    public func eraseToAnyPublisher() -> AnyPublisher<Output, Failure> { AnyPublisher() }
}
public final class CurrentValueSubject<Output, Failure: Error>: @unchecked Sendable {
    public var value: Output
    public init(_ value: Output) { self.value = value }
    public func send(_ input: Output) { self.value = input }
    public func eraseToAnyPublisher() -> AnyPublisher<Output, Failure> { AnyPublisher() }
}
public extension View {
    func onReceive<P>(_ publisher: P, perform action: @escaping (Any) -> Void) -> Self { self }
}

public struct Logger: Sendable {
    public init(subsystem: String = "", category: String = "") {}
    public func log(_ message: String) {}
    public func info(_ message: String) {}
    public func debug(_ message: String) {}
    public func warning(_ message: String) {}
    public func error(_ message: String) {}
    public func notice(_ message: String) {}
    public func fault(_ message: String) {}
    public func critical(_ message: String) {}
}
public struct OSLog: Sendable {
    public static let `default` = OSLog()
    public init(subsystem: String = "", category: String = "") {}
}
public struct OSLogType: Sendable {
    public static let `default` = OSLogType()
    public static let info = OSLogType()
    public static let debug = OSLogType()
    public static let error = OSLogType()
    public static let fault = OSLogType()
}
public func os_log(_ message: String, log: OSLog = .default, type: OSLogType = .default, _ args: CVarArg...) {}

public struct NWInterface: Sendable {
    public enum InterfaceType: Sendable { case other, wifi, cellular, wiredEthernet, loopback }
}
public struct NWPath: Sendable {
    public enum Status: Sendable { case satisfied, unsatisfied, requiresConnection }
    public var status: Status = .satisfied
    public var isExpensive: Bool = false
    public var isConstrained: Bool = false
    public func usesInterfaceType(_ type: NWInterface.InterfaceType) -> Bool { type == .wifi }
}
public final class NWPathMonitor: @unchecked Sendable {
    public var pathUpdateHandler: ((NWPath) -> Void)?
    public var currentPath: NWPath = NWPath()
    public init() {}
    public init(requiredInterfaceType: NWInterface.InterfaceType) {}
    public func start(queue: DispatchQueue) {
        pathUpdateHandler?(currentPath)
    }
    public func cancel() {}
}

public struct UNAuthorizationOptions: OptionSet, Sendable {
    public let rawValue: UInt
    public init(rawValue: UInt) { self.rawValue = rawValue }
    public static let badge = UNAuthorizationOptions(rawValue: 1 << 0)
    public static let sound = UNAuthorizationOptions(rawValue: 1 << 1)
    public static let alert = UNAuthorizationOptions(rawValue: 1 << 2)
    public static let provisional = UNAuthorizationOptions(rawValue: 1 << 6)
}
public enum UNAuthorizationStatus: Int, Sendable {
    case notDetermined = 0, denied, authorized, provisional, ephemeral
}
public struct UNNotificationSettings: Sendable {
    public var authorizationStatus: UNAuthorizationStatus = .authorized
}
public struct UNNotificationSound: Sendable {
    public static let `default` = UNNotificationSound()
}
public final class UNMutableNotificationContent: @unchecked Sendable {
    public var title: String = ""
    public var subtitle: String = ""
    public var body: String = ""
    public var badge: NSNumber?
    public var sound: UNNotificationSound?
    public var userInfo: [AnyHashable: Any] = [:]
    public init() {}
}
public class UNNotificationTrigger: @unchecked Sendable { public init() {} }
public final class UNTimeIntervalNotificationTrigger: UNNotificationTrigger, @unchecked Sendable {
    public init(timeInterval: TimeInterval, repeats: Bool) { super.init() }
}
public final class UNCalendarNotificationTrigger: UNNotificationTrigger, @unchecked Sendable {
    public init(dateMatching dateComponents: DateComponents, repeats: Bool) { super.init() }
}
public final class UNNotificationRequest: @unchecked Sendable {
    public let identifier: String
    public init(identifier: String, content: UNMutableNotificationContent, trigger: UNNotificationTrigger?) {
        self.identifier = identifier
    }
}
public final class UNUserNotificationCenter: @unchecked Sendable {
    public static func current() -> UNUserNotificationCenter { UNUserNotificationCenter() }
    public init() {}
    public func requestAuthorization(options: UNAuthorizationOptions = [], completionHandler: @escaping (Bool, (any Error)?) -> Void) {
        completionHandler(true, nil)
    }
    public func requestAuthorization(options: UNAuthorizationOptions = []) async throws -> Bool { true }
    public func getNotificationSettings(completionHandler: @escaping (UNNotificationSettings) -> Void) {
        completionHandler(UNNotificationSettings())
    }
    public func notificationSettings() async -> UNNotificationSettings { UNNotificationSettings() }
    public func add(_ request: UNNotificationRequest, withCompletionHandler completionHandler: (((any Error)?) -> Void)? = nil) {
        completionHandler?(nil)
    }
    public func removeAllPendingNotificationRequests() {}
    public func removeAllDeliveredNotifications() {}
    public func removePendingNotificationRequests(withIdentifiers identifiers: [String]) {}
}

public enum LAPolicy: Int, Sendable {
    case deviceOwnerAuthenticationWithBiometrics = 1
    case deviceOwnerAuthentication = 2
}
public enum LABiometryType: Int, Sendable {
    case none = 0, touchID = 1, faceID = 2, opticID = 4
}
public final class LAContext: @unchecked Sendable {
    public var biometryType: LABiometryType = .faceID
    public var localizedCancelTitle: String?
    public var localizedFallbackTitle: String?
    public init() {}
    public func canEvaluatePolicy(_ policy: LAPolicy, error: Any? = nil) -> Bool { true }
    public func evaluatePolicy(_ policy: LAPolicy, localizedReason: String, reply: @escaping (Bool, (any Error)?) -> Void) {
        reply(true, nil)
    }
    public func evaluatePolicy(_ policy: LAPolicy, localizedReason: String) async throws -> Bool { true }
}

public struct SignInWithAppleButton: View {
    public enum Label: Sendable { case signIn, `continue`, signUp }
    public enum Style: Sendable { case black, white, whiteOutline }
    public init(_ label: Label = .signIn, onRequest: @escaping (Any) -> Void, onCompletion: @escaping (Result<Any, any Error>) -> Void) {}
    public func signInWithAppleButtonStyle(_ style: Style) -> Self { self }
    public var body: some View {
        Button("Sign in with Apple") {}
    }
}

public struct RequestReviewAction: Sendable {
    public init() {}
    public func callAsFunction() {}
}
public extension EnvironmentValues {
    var requestReview: RequestReviewAction {
        get { RequestReviewAction() }
        set {}
    }
}
public final class SKStoreReviewController: @unchecked Sendable {
    public static func requestReview() {}
    public static func requestReview(in windowScene: Any) {}
}

public struct UTType: Equatable, Hashable, Sendable {
    public let identifier: String
    public init(_ identifier: String = "public.data") { self.identifier = identifier }
    public init?(filenameExtension: String) { self.identifier = "public.\(filenameExtension)" }
    public init?(mimeType: String) { self.identifier = mimeType }
    public static let item = UTType("public.item")
    public static let content = UTType("public.content")
    public static let data = UTType("public.data")
    public static let plainText = UTType("public.plain-text")
    public static let utf8PlainText = UTType("public.utf8-plain-text")
    public static let json = UTType("public.json")
    public static let xml = UTType("public.xml")
    public static let yaml = UTType("public.yaml")
    public static let commaSeparatedText = UTType("public.comma-separated-values-text")
    public static let url = UTType("public.url")
    public static let fileURL = UTType("public.file-url")
    public static let image = UTType("public.image")
    public static let png = UTType("public.png")
    public static let jpeg = UTType("public.jpeg")
    public static let gif = UTType("com.compuserve.gif")
    public static let webP = UTType("org.webmproject.webp")
    public static let svg = UTType("public.svg-image")
    public static let pdf = UTType("com.adobe.pdf")
    public static let audio = UTType("public.audio")
    public static let mp3 = UTType("public.mp3")
    public static let movie = UTType("public.movie")
    public static let mpeg4Movie = UTType("public.mpeg-4")
    public static let zip = UTType("public.zip-archive")
    public static let folder = UTType("public.folder")
}

public struct SHA256Digest: Sequence, Equatable, Sendable {
    private let bytes: [UInt8]
    public init(_ bytes: [UInt8]) { self.bytes = bytes }
    public func makeIterator() -> IndexingIterator<[UInt8]> { bytes.makeIterator() }
    public var description: String { bytes.map { String(format: "%02x", ${'$'}0) }.joined() }
}
public enum SHA256 {
    public static func hash<D: DataProtocol>(data: D) -> SHA256Digest {
        var h: UInt64 = 14695981039346656037
        for b in data { h = (h ^ UInt64(b)) &* 1099511628211 }
        var out = [UInt8](repeating: 0, count: 32)
        for i in 0..<32 { out[i] = UInt8((h >> ((i % 8) * 8)) & 0xFF) }
        return SHA256Digest(out)
    }
}
public enum Insecure {
    public enum MD5 {
        public static func hash<D: DataProtocol>(data: D) -> SHA256Digest { SHA256.hash(data: data) }
    }
    public enum SHA1 {
        public static func hash<D: DataProtocol>(data: D) -> SHA256Digest { SHA256.hash(data: data) }
    }
}

public protocol Tip: Sendable {
    var title: Text { get }
    var message: Text? { get }
    var image: Image? { get }
}
public extension Tip {
    var message: Text? { nil }
    var image: Image? { nil }
    func invalidate(reason: Any? = nil) {}
}
public struct TipView<T: Tip>: View {
    public let tip: T
    public init(_ tip: T, arrowEdge: Edge? = nil, action: @escaping (Any) -> Void = { _ in }) {
        self.tip = tip
    }
    public var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            tip.title.font(.headline)
            if let msg = tip.message { msg.font(.subheadline) }
        }
        .padding(12)
    }
}
public enum Tips {
    public static func configure(_ options: [Any] = []) throws {}
    public static func resetDatastore() throws {}
}
public extension View {
    func popoverTip<T: Tip>(_ tip: T?, arrowEdge: Edge? = nil, action: @escaping (Any) -> Void = { _ in }) -> Self { self }
}

// ============================================================================
// UIKit, AppKit, CoreGraphics, Path, Shape, Canvas, Audio, Video, WebKit Shims
// ============================================================================

public struct NSColor: Sendable {
    public enum ColorSpace: Sendable {
        case sRGB, deviceRGB
    }
    public let r: Double, g: Double, b: Double, a: Double
    public init(srgbRed: Double, green: Double, blue: Double, alpha: Double) {
        self.r = srgbRed; self.g = green; self.b = blue; self.a = alpha
    }
    public init(_ color: Color) {
        let v = color.argb
        self.a = Double((v >> 24) & 0xFF) / 255.0
        self.r = Double((v >> 16) & 0xFF) / 255.0
        self.g = Double((v >> 8) & 0xFF) / 255.0
        self.b = Double(v & 0xFF) / 255.0
    }
    public var redComponent: Double { r }
    public var greenComponent: Double { g }
    public var blueComponent: Double { b }
    public var alphaComponent: Double { a }
    public func usingColorSpace(_ space: ColorSpace) -> NSColor? { self }
    public static let clear = NSColor(srgbRed: 0, green: 0, blue: 0, alpha: 0)
}
public extension Color {
    init(nsColor: NSColor) {
        self.init(red: nsColor.r, green: nsColor.g, blue: nsColor.b, opacity: nsColor.a)
    }
}
public func dynamicNSColor(light: NSColor, dark: NSColor) -> NSColor { light }
public func dynamicColor(light: NSColor, dark: NSColor) -> Color { Color(nsColor: light) }

public struct StrokeStyle: Sendable {
    public enum LineCap: Sendable { case round, butt, square }
    public enum LineJoin: Sendable { case round, miter, bevel }
    public var lineWidth: Double
    public var lineCap: LineCap
    public var lineJoin: LineJoin
    public var miterLimit: Double
    public var dash: [Double]
    public var dashPhase: Double
    public init(lineWidth: Double = 1, lineCap: LineCap = .butt, lineJoin: LineJoin = .miter, miterLimit: Double = 10, dash: [Double] = [], dashPhase: Double = 0) {
        self.lineWidth = lineWidth
        self.lineCap = lineCap
        self.lineJoin = lineJoin
        self.miterLimit = miterLimit
        self.dash = dash
        self.dashPhase = dashPhase
    }
}
public extension VerticalAlignment {
    static let firstTextBaseline = VerticalAlignment.center
    static let lastTextBaseline = VerticalAlignment.center
}
public extension Font.Design {
    static let rounded = Font.Design.default
    static let serif = Font.Design.default
}
public enum DynamicTypeSize: Int, Comparable, CaseIterable, Sendable {
    case xSmall = 0, small, medium, large, xLarge, xxLarge, xxxLarge
    case accessibility1, accessibility2, accessibility3, accessibility4, accessibility5
    public static func < (lhs: DynamicTypeSize, rhs: DynamicTypeSize) -> Bool { lhs.rawValue < rhs.rawValue }
    public var isAccessibilitySize: Bool { rawValue >= DynamicTypeSize.accessibility1.rawValue }
}
public extension EnvironmentValues {
    var dynamicTypeSize: DynamicTypeSize {
        get { .large }
        set {}
    }
}
public extension Comparable {
    func clamped(to limits: ClosedRange<Self>) -> Self {
        min(max(self, limits.lowerBound), limits.upperBound)
    }
}
public extension Angle {
    var radians: Double {
        get { degrees * .pi / 180.0 }
        set { degrees = newValue * 180.0 / .pi }
    }
    init(radians: Double) {
        self.init(degrees: radians * 180.0 / .pi)
    }
    static let zero = Angle(degrees: 0)
}

public protocol Observable: AnyObject {}
public struct ObservationRegistrar: Sendable {
    public init() {}
}

public final class UIDevice: @unchecked Sendable {
    public enum UserInterfaceIdiom: Int, Sendable {
        case unspecified = -1, phone = 0, pad = 1, tv = 2, carPlay = 3, mac = 5, vision = 6
    }
    public enum BatteryState: Int, Sendable {
        case unknown = 0, unplugged, charging, full
    }
    public static let current = UIDevice()
    public var name: String = "iPhone"
    public var model: String = "iPhone"
    public var localizedModel: String = "iPhone"
    public var systemName: String = "iOS"
    public var systemVersion: String = "18.0"
    public var userInterfaceIdiom: UserInterfaceIdiom = .phone
    public var identifierForVendor: UUID? = UUID()
    public var isBatteryMonitoringEnabled: Bool = false
    public var batteryLevel: Float = 1.0
    public var batteryState: BatteryState = .full
    public init() {}
}

public final class UIScreen: @unchecked Sendable {
    public static let main = UIScreen()
    public var bounds: CGRect = CGRect(x: 0, y: 0, width: 390, height: 844)
    public var scale: Double = 3.0
    public var nativeBounds: CGRect = CGRect(x: 0, y: 0, width: 1170, height: 2532)
    public init() {}
}

public struct CGColor: Equatable, Sendable {
    public let components: [Double]?
    public init(red: Double, green: Double, blue: Double, alpha: Double = 1.0) {
        self.components = [red, green, blue, alpha]
    }
}

public struct UIColor: Equatable, Sendable {
    public let red: Double
    public let green: Double
    public let blue: Double
    public let alpha: Double
    public init(red: Double, green: Double, blue: Double, alpha: Double = 1.0) {
        self.red = red
        self.green = green
        self.blue = blue
        self.alpha = alpha
    }
    public init(white: Double, alpha: Double = 1.0) {
        self.red = white
        self.green = white
        self.blue = white
        self.alpha = alpha
    }
    public init?(named: String) {
        self.red = 0.0
        self.green = 0.48
        self.blue = 1.0
        self.alpha = 1.0
    }
    public init(_ color: Color) {
        self.red = color.red
        self.green = color.green
        self.blue = color.blue
        self.alpha = color.opacity
    }
    public var cgColor: CGColor {
        CGColor(red: red, green: green, blue: blue, alpha: alpha)
    }
    public static let systemBackground = UIColor(red: 1.0, green: 1.0, blue: 1.0, alpha: 1.0)
    public static let secondarySystemBackground = UIColor(red: 0.95, green: 0.95, blue: 0.97, alpha: 1.0)
    public static let tertiarySystemBackground = UIColor(red: 1.0, green: 1.0, blue: 1.0, alpha: 1.0)
    public static let systemGroupedBackground = UIColor(red: 0.95, green: 0.95, blue: 0.97, alpha: 1.0)
    public static let secondarySystemGroupedBackground = UIColor(red: 1.0, green: 1.0, blue: 1.0, alpha: 1.0)
    public static let tertiarySystemGroupedBackground = UIColor(red: 0.95, green: 0.95, blue: 0.97, alpha: 1.0)
    public static let label = UIColor(red: 0.0, green: 0.0, blue: 0.0, alpha: 1.0)
    public static let secondaryLabel = UIColor(red: 0.24, green: 0.24, blue: 0.26, alpha: 0.6)
    public static let tertiaryLabel = UIColor(red: 0.24, green: 0.24, blue: 0.26, alpha: 0.3)
    public static let quaternaryLabel = UIColor(red: 0.24, green: 0.24, blue: 0.26, alpha: 0.18)
    public static let placeholderText = UIColor(red: 0.24, green: 0.24, blue: 0.26, alpha: 0.3)
    public static let separator = UIColor(red: 0.24, green: 0.24, blue: 0.26, alpha: 0.29)
    public static let opaqueSeparator = UIColor(red: 0.78, green: 0.78, blue: 0.78, alpha: 1.0)
    public static let systemFill = UIColor(red: 0.47, green: 0.47, blue: 0.50, alpha: 0.2)
    public static let secondarySystemFill = UIColor(red: 0.47, green: 0.47, blue: 0.50, alpha: 0.16)
    public static let tertiarySystemFill = UIColor(red: 0.46, green: 0.46, blue: 0.50, alpha: 0.12)
    public static let quaternarySystemFill = UIColor(red: 0.45, green: 0.45, blue: 0.50, alpha: 0.08)
    public static let systemBlue = UIColor(red: 0.0, green: 0.48, blue: 1.0, alpha: 1.0)
    public static let systemRed = UIColor(red: 1.0, green: 0.23, blue: 0.19, alpha: 1.0)
    public static let systemGreen = UIColor(red: 0.20, green: 0.78, blue: 0.35, alpha: 1.0)
    public static let systemOrange = UIColor(red: 1.0, green: 0.58, blue: 0.0, alpha: 1.0)
    public static let systemYellow = UIColor(red: 1.0, green: 0.80, blue: 0.0, alpha: 1.0)
    public static let systemPurple = UIColor(red: 0.69, green: 0.32, blue: 0.87, alpha: 1.0)
    public static let systemPink = UIColor(red: 1.0, green: 0.18, blue: 0.33, alpha: 1.0)
    public static let systemTeal = UIColor(red: 0.19, green: 0.69, blue: 0.78, alpha: 1.0)
    public static let systemCyan = UIColor(red: 0.20, green: 0.68, blue: 0.90, alpha: 1.0)
    public static let systemMint = UIColor(red: 0.0, green: 0.78, blue: 0.75, alpha: 1.0)
    public static let systemBrown = UIColor(red: 0.64, green: 0.52, blue: 0.37, alpha: 1.0)
    public static let systemIndigo = UIColor(red: 0.35, green: 0.34, blue: 0.84, alpha: 1.0)
    public static let systemGray = UIColor(red: 0.56, green: 0.56, blue: 0.58, alpha: 1.0)
    public static let systemGray2 = UIColor(red: 0.68, green: 0.68, blue: 0.70, alpha: 1.0)
    public static let systemGray3 = UIColor(red: 0.78, green: 0.78, blue: 0.80, alpha: 1.0)
    public static let systemGray4 = UIColor(red: 0.82, green: 0.82, blue: 0.84, alpha: 1.0)
    public static let systemGray5 = UIColor(red: 0.90, green: 0.90, blue: 0.92, alpha: 1.0)
    public static let systemGray6 = UIColor(red: 0.95, green: 0.95, blue: 0.97, alpha: 1.0)
    public static let clear = UIColor(red: 0.0, green: 0.0, blue: 0.0, alpha: 0.0)
    public static let white = UIColor(white: 1.0)
    public static let black = UIColor(white: 0.0)
    public static let darkGray = UIColor(white: 0.333)
    public static let lightGray = UIColor(white: 0.667)
    public func withAlphaComponent(_ alpha: Double) -> UIColor {
        UIColor(red: red, green: green, blue: blue, alpha: alpha)
    }
}
public extension Color {
    init(_ uiColor: UIColor) {
        self.init(red: uiColor.red, green: uiColor.green, blue: uiColor.blue, opacity: uiColor.alpha)
    }
    init(uiColor: UIColor) {
        self.init(red: uiColor.red, green: uiColor.green, blue: uiColor.blue, opacity: uiColor.alpha)
    }
}

public struct UIImage: Sendable {
    public let systemName: String?
    public let named: String?
    public var size: CGSize = CGSize(width: 24, height: 24)
    public init?(systemName: String) {
        self.systemName = systemName
        self.named = nil
    }
    public init?(named: String) {
        self.named = named
        self.systemName = nil
    }
    public init?(data: Data) {
        self.systemName = nil
        self.named = nil
    }
    public init?(contentsOfFile path: String) {
        self.systemName = nil
        self.named = path
    }
    public init() {
        self.systemName = nil
        self.named = nil
    }
    public func jpegData(compressionQuality: Double) -> Data? { Data() }
    public func pngData() -> Data? { Data() }
}
public extension Image {
    init(uiImage: UIImage) {
        if let sys = uiImage.systemName {
            self.init(systemName: sys)
        } else {
            self.init(uiImage.named ?? "photo")
        }
    }
}

public class UIFont: @unchecked Sendable {
    public enum Weight: Sendable {
        case ultraLight, thin, light, regular, medium, semibold, bold, heavy, black
    }
    public let pointSize: Double
    public init(size: Double = 17.0) { self.pointSize = size }
    public static func systemFont(ofSize size: Double, weight: Weight = .regular) -> UIFont { UIFont(size: size) }
    public static func boldSystemFont(ofSize size: Double) -> UIFont { UIFont(size: size) }
    public static func italicSystemFont(ofSize size: Double) -> UIFont { UIFont(size: size) }
    public static func monospacedDigitSystemFont(ofSize size: Double, weight: Weight = .regular) -> UIFont { UIFont(size: size) }
    public static func monospacedSystemFont(ofSize size: Double, weight: Weight = .regular) -> UIFont { UIFont(size: size) }
    public static func preferredFont(forTextStyle style: String) -> UIFont { UIFont(size: 17.0) }
}

open class UIView: NSObject, @unchecked Sendable {
    public var frame: CGRect
    public var bounds: CGRect
    public var backgroundColor: UIColor?
    public var isHidden: Bool = false
    public var alpha: Double = 1.0
    public var subviews: [UIView] = []
    public override init() {
        self.frame = .zero
        self.bounds = .zero
        super.init()
    }
    public init(frame: CGRect) {
        self.frame = frame
        self.bounds = CGRect(origin: CGPoint(), size: frame.size)
        super.init()
    }
    open func addSubview(_ view: UIView) { subviews.append(view) }
    open func layoutSubviews() {}
    open func setNeedsLayout() {}
    open func setNeedsDisplay() {}
}

open class UIWindow: UIView, @unchecked Sendable {
    public var rootViewController: UIViewController?
    public func makeKeyAndVisible() {}
}

open class UIViewController: NSObject, @unchecked Sendable {
    public var view: UIView = UIView()
    public var title: String?
    public var children: [UIViewController] = []
    public override init() { super.init() }
    public init(nibName: String?, bundle: Any?) { super.init() }
    open func viewDidLoad() {}
    open func viewWillAppear(_ animated: Bool) {}
    open func viewDidAppear(_ animated: Bool) {}
    open func viewWillDisappear(_ animated: Bool) {}
    open func viewDidDisappear(_ animated: Bool) {}
    open func addChild(_ childController: UIViewController) { children.append(childController) }
    open func present(_ viewControllerToPresent: UIViewController, animated flag: Bool, completion: (() -> Void)? = nil) {
        completion?()
    }
    open func dismiss(animated flag: Bool, completion: (() -> Void)? = nil) {
        completion?()
    }
}

open class UIHostingController<Content: View>: UIViewController, @unchecked Sendable {
    public var rootView: Content
    public init(rootView: Content) {
        self.rootView = rootView
        super.init()
    }
}

open class UINavigationController: UIViewController, @unchecked Sendable {
    public var viewControllers: [UIViewController] = []
    public override init() { super.init() }
    public init(rootViewController: UIViewController) {
        self.viewControllers = [rootViewController]
        super.init()
    }
    open func pushViewController(_ viewController: UIViewController, animated: Bool) {
        viewControllers.append(viewController)
    }
    @discardableResult
    open func popViewController(animated: Bool) -> UIViewController? {
        guard viewControllers.count > 1 else { return nil }
        return viewControllers.removeLast()
    }
}

public protocol UIViewRepresentable: View {
    associatedtype UIViewType = Any
    typealias Context = UIViewRepresentableContext<Self>
    func makeUIView(context: Context) -> UIViewType
    func updateUIView(_ uiView: UIViewType, context: Context)
}
public struct UIViewRepresentableContext<T> {
    public let coordinator: Any? = nil
    public let environment: EnvironmentValues = EnvironmentValues()
    public init() {}
}
public extension UIViewRepresentable {
    var body: some View { EmptyView() }
    func makeUIView(context: Context) -> Any { () }
    func updateUIView(_ uiView: Any, context: Context) {}
}

public protocol UIViewControllerRepresentable: View {
    associatedtype UIViewControllerType = UIViewController
    typealias Context = UIViewControllerRepresentableContext<Self>
    func makeUIViewController(context: Context) -> UIViewControllerType
    func updateUIViewController(_ uiViewController: UIViewControllerType, context: Context)
}
public struct UIViewControllerRepresentableContext<T> {
    public let coordinator: Any? = nil
    public let environment: EnvironmentValues = EnvironmentValues()
    public init() {}
}
public extension UIViewControllerRepresentable {
    var body: some View { EmptyView() }
    func makeUIViewController(context: Context) -> UIViewController { UIViewController() }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

public protocol UIApplicationDelegate: AnyObject {}
public protocol UISceneDelegate: AnyObject {}
public protocol UIWindowSceneDelegate: UISceneDelegate {}

public final class UIApplication: @unchecked Sendable {
    public struct LaunchOptionsKey: Hashable, Equatable, RawRepresentable, Sendable {
        public let rawValue: String
        public init(rawValue: String) { self.rawValue = rawValue }
    }
    public enum State: Int, Sendable { case active = 0, inactive, background }
    public static let shared = UIApplication()
    public static let openSettingsURLString = "app-settings:"
    public var applicationState: State = .active
    public var isIdleTimerDisabled: Bool = false
    public var applicationIconBadgeNumber: Int = 0
    public init() {}
    public func open(_ url: URL, options: [String: Any] = [:], completionHandler completion: ((Bool) -> Void)? = nil) {
        completion?(true)
    }
    public func canOpenURL(_ url: URL) -> Bool { true }
    public func sendAction(_ action: Any, to target: Any?, from sender: Any?, for event: Any?) -> Bool { true }
    public func endEditing(_ force: Bool) {}
}

public final class UIPasteboard: @unchecked Sendable {
    public static let general = UIPasteboard()
    public var string: String?
    public var strings: [String]? {
        get { string.map { [${'$'}0] } }
        set { string = newValue?.first }
    }
    public var url: URL?
    public var image: UIImage?
    public init() {}
}

public class UIFeedbackGenerator: @unchecked Sendable {
    public init() {}
    public func prepare() {}
}
public final class UIImpactFeedbackGenerator: UIFeedbackGenerator, @unchecked Sendable {
    public enum FeedbackStyle: Int, Sendable {
        case light = 0, medium = 1, heavy = 2, soft = 3, rigid = 4
    }
    public let style: FeedbackStyle
    public override init() {
        self.style = .medium
        super.init()
    }
    public init(style: FeedbackStyle) {
        self.style = style
        super.init()
    }
    public func impactOccurred(intensity: Double = 1.0) {}
}
public final class UINotificationFeedbackGenerator: UIFeedbackGenerator, @unchecked Sendable {
    public enum FeedbackType: Int, Sendable {
        case success = 0, warning = 1, error = 2
    }
    public override init() { super.init() }
    public func notificationOccurred(_ notificationType: FeedbackType) {}
}
public final class UISelectionFeedbackGenerator: UIFeedbackGenerator, @unchecked Sendable {
    public override init() { super.init() }
    public func selectionChanged() {}
}

// ToggleStyle shim
public protocol ToggleStyle {
    associatedtype Body: View
    typealias Configuration = ToggleStyleConfiguration
    @ViewBuilder func makeBody(configuration: Configuration) -> Body
}
public struct ToggleStyleConfiguration {
    public struct Label: View {
        public init() {}
        public var body: some View { EmptyView() }
    }
    @Binding public var isOn: Bool
    public var label: Label
    public init(isOn: Binding<Bool> = .constant(false), label: Label = Label()) {
        self._isOn = isOn
        self.label = label
    }
}
public extension ToggleStyle where Body == EmptyView {
    func makeBody(configuration: Configuration) -> EmptyView { EmptyView() }
}
public extension View {
    func toggleStyle<S: ToggleStyle>(_ style: S) -> some View { self }
}
public struct DefaultToggleStyle: ToggleStyle {
    public init() {}
    public func makeBody(configuration: Configuration) -> some View { EmptyView() }
}
public struct SwitchToggleStyle: ToggleStyle {
    public init() {}
    public init(tint: Color) {}
    public func makeBody(configuration: Configuration) -> some View { EmptyView() }
}
public extension ToggleStyle where Self == DefaultToggleStyle {
    static var `default`: DefaultToggleStyle { DefaultToggleStyle() }
}
public extension ToggleStyle where Self == SwitchToggleStyle {
    static var `switch`: SwitchToggleStyle { SwitchToggleStyle() }
}

// 2D geometry, Path, Shape, Canvas, and Capsule shims
public struct CGPoint: Equatable, Sendable {
    public var x: Double
    public var y: Double
    public init(x: Double = 0, y: Double = 0) { self.x = x; self.y = y }
    public static let zero = CGPoint(x: 0, y: 0)
}
public struct CGSize: Equatable, Sendable {
    public var width: Double
    public var height: Double
    public init(width: Double = 0, height: Double = 0) { self.width = width; self.height = height }
    public static let zero = CGSize(width: 0, height: 0)
}
public struct CGRect: Equatable, Sendable {
    public var origin: CGPoint
    public var size: CGSize
    public init(origin: CGPoint = CGPoint(), size: CGSize = CGSize()) { self.origin = origin; self.size = size }
    public init(x: Double, y: Double, width: Double, height: Double) { self.origin = CGPoint(x: x, y: y); self.size = CGSize(width: width, height: height) }
    public static let zero = CGRect(x: 0, y: 0, width: 0, height: 0)
    public var width: Double { size.width }
    public var height: Double { size.height }
    public var midX: Double { origin.x + size.width / 2 }
    public var midY: Double { origin.y + size.height / 2 }
    public var minX: Double { origin.x }
    public var minY: Double { origin.y }
    public var maxX: Double { origin.x + size.width }
    public var maxY: Double { origin.y + size.height }
    public func insetBy(dx: Double, dy: Double) -> CGRect {
        CGRect(x: origin.x + dx, y: origin.y + dy, width: max(0, size.width - dx * 2), height: max(0, size.height - dy * 2))
    }
    public func contains(_ point: CGPoint) -> Bool {
        point.x >= minX && point.x <= maxX && point.y >= minY && point.y <= maxY
    }
}
public struct Path: View, Shape, Sendable {
    private var _commands: [String] = []
    public var svgPath: String { _commands.joined(separator: " ") }
    public var isEmpty: Bool { _commands.isEmpty }
    public init() {}
    public init(_ callback: (inout Path) -> Void) {
        var p = Path()
        callback(&p)
        self = p
    }
    public init(_ rect: CGRect) {
        self.init()
        addRect(rect)
    }
    public init(roundedRect rect: CGRect, cornerRadius: Double, style: RoundedCornerStyle = .continuous) {
        self.init()
        addRoundedRect(in: rect, cornerSize: CGSize(width: cornerRadius, height: cornerRadius), style: style)
    }
    public init(roundedRect rect: CGRect, cornerSize: CGSize, style: RoundedCornerStyle = .continuous) {
        self.init()
        addRoundedRect(in: rect, cornerSize: cornerSize, style: style)
    }
    public init(ellipseIn rect: CGRect) {
        self.init()
        addEllipse(in: rect)
    }
    public mutating func move(to p: CGPoint) {
        _commands.append("M \(p.x) \(p.y)")
    }
    public mutating func addLine(to p: CGPoint) {
        _commands.append("L \(p.x) \(p.y)")
    }
    public mutating func addLines(_ lines: [CGPoint]) {
        guard let first = lines.first else { return }
        move(to: first)
        for pt in lines.dropFirst() {
            addLine(to: pt)
        }
    }
    public mutating func addQuadCurve(to p: CGPoint, control: CGPoint) {
        _commands.append("Q \(control.x) \(control.y) \(p.x) \(p.y)")
    }
    public mutating func addCurve(to p: CGPoint, control1: CGPoint, control2: CGPoint) {
        _commands.append("C \(control1.x) \(control1.y) \(control2.x) \(control2.y) \(p.x) \(p.y)")
    }
    public mutating func addRect(_ rect: CGRect) {
        _commands.append("M \(rect.minX) \(rect.minY) L \(rect.maxX) \(rect.minY) L \(rect.maxX) \(rect.maxY) L \(rect.minX) \(rect.maxY) Z")
    }
    public mutating func addRoundedRect(in rect: CGRect, cornerSize: CGSize, style: RoundedCornerStyle = .continuous) {
        let rx = min(max(0, cornerSize.width), rect.width / 2)
        let ry = min(max(0, cornerSize.height), rect.height / 2)
        if rx <= 0 && ry <= 0 {
            addRect(rect)
            return
        }
        _commands.append(
            "M \(rect.minX + rx) \(rect.minY) " +
            "L \(rect.maxX - rx) \(rect.minY) " +
            "Q \(rect.maxX) \(rect.minY) \(rect.maxX) \(rect.minY + ry) " +
            "L \(rect.maxX) \(rect.maxY - ry) " +
            "Q \(rect.maxX) \(rect.maxY) \(rect.maxX - rx) \(rect.maxY) " +
            "L \(rect.minX + rx) \(rect.maxY) " +
            "Q \(rect.minX) \(rect.maxY) \(rect.minX) \(rect.maxY - ry) " +
            "L \(rect.minX) \(rect.minY + ry) " +
            "Q \(rect.minX) \(rect.minY) \(rect.minX + rx) \(rect.minY) Z"
        )
    }
    public mutating func addEllipse(in rect: CGRect) {
        let cx = rect.midX
        let cy = rect.midY
        let rx = rect.width / 2
        let ry = rect.height / 2
        let k = 0.5522847498307936
        let ox = rx * k
        let oy = ry * k
        _commands.append(
            "M \(cx - rx) \(cy) " +
            "C \(cx - rx) \(cy - oy) \(cx - ox) \(cy - ry) \(cx) \(cy - ry) " +
            "C \(cx + ox) \(cy - ry) \(cx + rx) \(cy - oy) \(cx + rx) \(cy) " +
            "C \(cx + rx) \(cy + oy) \(cx + ox) \(cy + ry) \(cx) \(cy + ry) " +
            "C \(cx - ox) \(cy + ry) \(cx - rx) \(cy + oy) \(cx - rx) \(cy) Z"
        )
    }
    public mutating func addArc(center: CGPoint, radius: Double, startAngle: Angle, endAngle: Angle, clockwise: Bool) {
        let startRad = startAngle.radians
        let endRad = endAngle.radians
        let sx = center.x + radius * cos(startRad)
        let sy = center.y + radius * sin(startRad)
        let ex = center.x + radius * cos(endRad)
        let ey = center.y + radius * sin(endRad)
        if _commands.isEmpty {
            _commands.append("M \(sx) \(sy)")
        } else {
            _commands.append("L \(sx) \(sy)")
        }
        let sweep = clockwise ? 0 : 1
        let delta = abs(endRad - startRad)
        let largeArc = delta > .pi ? 1 : 0
        _commands.append("A \(radius) \(radius) 0 \(largeArc) \(sweep) \(ex) \(ey)")
    }
    public mutating func closeSubpath() {
        _commands.append("Z")
    }
    public var body: some View {
        ComposableView("Path", props: ["d": .string(svgPath), "svgPath": .string(svgPath)]) { EmptyView() }
    }
    public func fill(_ color: Color) -> some View {
        ComposableView("Path", props: ["d": .string(svgPath), "svgPath": .string(svgPath), "fill": .int(Int(color.argb))]) { EmptyView() }
    }
    public func stroke(_ color: Color, lineWidth: Double = 1) -> some View {
        ComposableView("Path", props: ["d": .string(svgPath), "svgPath": .string(svgPath), "stroke": .int(Int(color.argb)), "strokeWidth": .double(lineWidth)]) { EmptyView() }
    }
    public func stroke(_ color: Color, style: StrokeStyle) -> some View {
        ComposableView("Path", props: ["d": .string(svgPath), "svgPath": .string(svgPath), "stroke": .int(Int(color.argb)), "strokeWidth": .double(style.lineWidth)]) { EmptyView() }
    }
}
public extension Shape {
    var _shapeKind: String { "rectangle" }
    var _cornerRadius: Double? { nil }
    var body: some View { Rectangle() }
    func fill(_ color: Color) -> some View { Rectangle().fill(color) }
    func stroke(_ color: Color, lineWidth: Double = 1) -> some View { Rectangle().stroke(color, lineWidth: lineWidth) }
    func stroke(_ color: Color, style: StrokeStyle) -> some View { Rectangle().stroke(color, lineWidth: style.lineWidth) }
}
public struct GraphicsContext: Sendable {
    public enum Shading: Sendable {
        public static func color(_ color: Color) -> Shading { .color }
        case color
    }
    public var opacity: Double = 1.0
    public init() {}
    public func fill(_ path: Path, with shading: Shading) {}
    public func stroke(_ path: Path, with shading: Shading, lineWidth: Double = 1.0) {}
    public func stroke(_ path: Path, with shading: Shading, style: StrokeStyle) {}
}
public struct Canvas<Symbols: View>: View {
    public init(
        opaque: Bool = false,
        colorMode: Any? = nil,
        rendersAsynchronously: Bool = false,
        renderer: @escaping (inout GraphicsContext, CGSize) -> Void,
        @ViewBuilder symbols: () -> Symbols
    ) {}
    public var body: some View { EmptyView() }
}
public extension Canvas where Symbols == EmptyView {
    init(
        opaque: Bool = false,
        colorMode: Any? = nil,
        rendersAsynchronously: Bool = false,
        renderer: @escaping (inout GraphicsContext, CGSize) -> Void
    ) {
        self.init(opaque: opaque, colorMode: colorMode, rendersAsynchronously: rendersAsynchronously, renderer: renderer) {
            EmptyView()
        }
    }
}

// AudioToolbox shims
public typealias SystemSoundID = UInt32
public func AudioServicesPlaySystemSound(_ inSystemSoundID: SystemSoundID) {}
public func AudioServicesPlayAlertSound(_ inSystemSoundID: SystemSoundID) {}
public let kSystemSoundID_Vibrate: SystemSoundID = 4095

// AVFoundation shims
public enum AVMediaType: Sendable {
    case audio, video
}
public enum AVAuthorizationStatus: Int, Sendable {
    case notDetermined = 0, restricted = 1, denied = 2, authorized = 3
}
public final class AVCaptureDevice: @unchecked Sendable {
    public static func authorizationStatus(for mediaType: AVMediaType) -> AVAuthorizationStatus { .authorized }
    public static func requestAccess(for mediaType: AVMediaType, completionHandler handler: @escaping (Bool) -> Void) {
        handler(true)
    }
}
public class AVAudioPlayer: @unchecked Sendable {
    public init() {}
    public init(data: Data) throws {}
    public init(contentsOf url: URL) throws {}
    public func play() -> Bool { true }
    public func pause() {}
    public func stop() {}
    public var isPlaying: Bool = false
    public var numberOfLoops: Int = 0
    public var volume: Float = 1.0
}
public class AVPlayer: @unchecked Sendable {
    public init() {}
    public init(url: URL) {}
    public func play() {}
    public func pause() {}
}
public struct VideoPlayer<VideoOverlay: View>: View {
    public var body: some View { EmptyView() }
    public init(player: Any? = nil, @ViewBuilder videoOverlay: () -> VideoOverlay = { EmptyView() }) {}
}
public extension VideoPlayer where VideoOverlay == EmptyView {
    init(player: Any? = nil) { self.init(player: player, videoOverlay: { EmptyView() }) }
}

// AppKit shims for macOS SwiftUI targets
public typealias NSPoint = CGPoint
public typealias NSSize = CGSize
public typealias NSRect = CGRect

public final class NSCursor: @unchecked Sendable {
    public static let arrow = NSCursor()
    public static let pointingHand = NSCursor()
    public static let iBeam = NSCursor()
    public init() {}
    public func push() {}
    public static func pop() {}
    public func pop() {}
    public func set() {}
}

open class NSWindow: @unchecked Sendable {
    public init() {}
    open func invalidateCursorRects(for view: NSView) {}
}

open class NSPanel: NSWindow, @unchecked Sendable {
    public override init() { super.init() }
}

open class NSView: @unchecked Sendable {
    public var frame: CGRect = .zero
    public var bounds: CGRect = .zero
    public var window: NSWindow? = nil
    public var toolTip: String? = nil
    public init(frame: CGRect = .zero) {
        self.frame = frame
        self.bounds = CGRect(origin: .zero, size: frame.size)
    }
    open func resetCursorRects() {}
    public func addCursorRect(_ rect: CGRect, cursor: NSCursor) {}
    open func hitTest(_ point: NSPoint) -> NSView? { nil }
}

public protocol NSViewRepresentable: View {
    associatedtype NSViewType = NSView
    typealias Context = NSViewRepresentableContext<Self>
    func makeNSView(context: Context) -> NSViewType
    func updateNSView(_ nsView: NSViewType, context: Context)
}
public struct NSViewRepresentableContext<T> {
    public let coordinator: Any? = nil
    public let environment: EnvironmentValues = EnvironmentValues()
    public init() {}
}
public extension NSViewRepresentable {
    var body: some View { EmptyView() }
}

public final class NSWorkspace: @unchecked Sendable {
    public static let shared = NSWorkspace()
    public init() {}
    @discardableResult
    public func open(_ url: URL) -> Bool { true }
}

public final class NSApplication: @unchecked Sendable {
    public static let shared = NSApplication()
    public init() {}
    public func terminate(_ sender: Any?) {}
}
public let NSApp = NSApplication.shared

// PhotosUI shims
public struct PhotosPicker<Label: View>: View {
    public var body: some View { label }
    private let label: Label
    public init(selection: Any, maxSelectionCount: Int? = nil, selectionBehavior: Any? = nil, matching: Any? = nil, preferredItemEncoding: Any? = nil, photoLibrary: Any? = nil, @ViewBuilder label: () -> Label) {
        self.label = label()
    }
}
public extension PhotosPicker where Label == Text {
    init<S: StringProtocol>(_ title: S, selection: Any, maxSelectionCount: Int? = nil, matching: Any? = nil) {
        self.init(selection: selection, maxSelectionCount: maxSelectionCount, matching: matching) { Text(title) }
    }
}
public struct PHPickerFilter: Sendable {
    public static let images = PHPickerFilter()
    public static let videos = PHPickerFilter()
    public static let livePhotos = PHPickerFilter()
    public static func any(of filters: [PHPickerFilter]) -> PHPickerFilter { PHPickerFilter() }
}
public struct PhotosPickerItem: Equatable, Hashable, Sendable {
    public var itemIdentifier: String? = nil
    public init() {}
    public func loadTransferable<T>(type: T.Type) async throws -> T? { nil }
}

// WebKit shims
public class WKWebView: UIView, @unchecked Sendable {
    public weak var navigationDelegate: (any WKNavigationDelegate)?
    public weak var uiDelegate: (any WKUIDelegate)?
    public override init() { super.init() }
    public init(frame: CGRect, configuration: Any) { super.init(frame: frame) }
    public func load(_ request: Any) {}
    public func loadHTMLString(_ string: String, baseURL: URL?) {}
}
public class WKWebViewConfiguration: NSObject, @unchecked Sendable {
    public override init() { super.init() }
}
public class WKNavigation: NSObject, @unchecked Sendable {
    public override init() { super.init() }
}
public protocol WKNavigationDelegate: AnyObject {}
public protocol WKUIDelegate: AnyObject {}

""".trimIndent()
