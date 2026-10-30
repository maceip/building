package dev.ide.core.runtime

import dev.ide.build.BuildContext
import dev.ide.build.BuildLogEntry
import dev.ide.build.BuildLogLevel
import dev.ide.build.ProgramIo
import dev.ide.build.TaskContext
import dev.ide.build.TaskResult
import dev.ide.build.engine.reportToolDiagnostics
import dev.ide.workbench.runtime.RuntimeHost
import dev.ide.workbench.runtime.RuntimeInstallation
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Interactive GUI Bridge runtime support for both:
 *  1. SwiftUI / Cupertino (`SwiftUI.swiftmodule` + `libSwiftUI.a` on-device `ViewHost` bridge)
 *  2. Node.js GUI (`@nodegui/nodegui`, `libui-node`, and `x11` window/widget bridge shim)
 *
 * Both bridges speak the newline-delimited JSON `ViewNode` protocol over stdout and receive
 * user interaction callbacks (`{"id":"...","kind":"void|bool|string|double|int","value":...}`)
 * over stdin, rendering live interactive native Compose windows inside `RunScreen`.
 */
object GuiBridgeRuntime {

    fun isBridgeJsonLine(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("{") &&
            trimmed.endsWith("}") &&
            trimmed.contains("\"type\":") &&
            trimmed.contains("\"children\":")
    }

    /**
     * Ensures the precompiled on-device `SwiftUI.swiftmodule` and `libSwiftUI.a` exist in
     * `<prefix>/lib/swift/android/` (restoring from `developer-runtime/swiftui-sdk` or
     * `/data/local/tmp/swiftui-sdk` if the runtime prefix was freshly extracted).
     */
    fun ensureSwiftUiBridgeSdk(installation: RuntimeInstallation): Boolean = runCatching {
        val swiftAndroidDir = installation.prefix.resolve("lib/swift/android")
        val targetLib = swiftAndroidDir.resolve("libSwiftUI.a")
        val targetModDir = swiftAndroidDir.resolve("SwiftUI.swiftmodule")
        val targetMod = targetModDir.resolve("aarch64-unknown-linux-android.swiftmodule")

        val filesRoot = installation.prefix.parent?.parent?.parent
        val detectedPkg = runCatching {
            var curr: Path? = installation.prefix
            var pkg: String? = null
            while (curr != null) {
                val name = curr.fileName?.toString()
                if (name == "ai.building.dev" || name == "dev.ide.android.dev") {
                    pkg = name
                    break
                }
                curr = curr.parent
            }
            pkg
        }.getOrNull()
        val pkgNames = listOfNotNull(detectedPkg, "ai.building.dev", "dev.ide.android.dev").distinct()
        val dynamicCandidates = pkgNames.flatMap { pkg ->
            listOf(
                Path.of("/sdcard/Android/data/$pkg/files/codeassist/swiftui-sdk/android-aarch64"),
                Path.of("/sdcard/Android/data/$pkg/files/codeassist/swiftui-sdk"),
                Path.of("/sdcard/Android/data/$pkg/files/swiftui-sdk/android-aarch64"),
                Path.of("/sdcard/Android/data/$pkg/files/swiftui-sdk"),
                Path.of("/data/user/0/$pkg/files/developer-runtime/swiftui-sdk/android-aarch64"),
                Path.of("/data/data/$pkg/files/developer-runtime/swiftui-sdk/android-aarch64"),
            )
        }
        val backupCandidates = listOfNotNull(
            filesRoot?.resolve("developer-runtime/swiftui-sdk/android-aarch64"),
            filesRoot?.resolve("developer-runtime/swiftui-sdk"),
        ) + dynamicCandidates + listOfNotNull(
            Path.of("/data/local/tmp/swiftui-sdk/android-aarch64"),
            Path.of("/data/local/tmp/swiftui-sdk"),
            Path.of(System.getProperty("user.home", ""), ".building/swiftui-bridge-sdk/android-aarch64"),
            Path.of(System.getProperty("user.home", ""), ".building/swiftui-bridge-sdk"),
        )
        for (candidate in backupCandidates) {
            val backup = if (Files.isDirectory(candidate.resolve("android-aarch64"))) {
                candidate.resolve("android-aarch64")
            } else {
                candidate
            }
            val srcLib = backup.resolve("libSwiftUI.a")
            val srcMod = backup.resolve("SwiftUI.swiftmodule/aarch64-unknown-linux-android.swiftmodule")
                .takeIf { Files.isRegularFile(it) }
                ?: backup.resolve("aarch64-unknown-linux-android.swiftmodule")
                    .takeIf { Files.isRegularFile(it) }
            if (Files.isRegularFile(srcLib) && srcMod != null && Files.isDirectory(swiftAndroidDir)) {
                val needsSync = !Files.isRegularFile(targetLib) ||
                    !Files.isRegularFile(targetMod) ||
                    Files.size(srcLib) != Files.size(targetLib)
                if (needsSync) {
                    Files.createDirectories(targetModDir)
                    Files.copy(srcLib, targetLib, StandardCopyOption.REPLACE_EXISTING)
                    val aarch64Dir = swiftAndroidDir.resolve("aarch64")
                    if (Files.isDirectory(aarch64Dir)) {
                        Files.copy(srcLib, aarch64Dir.resolve("libSwiftUI.a"), StandardCopyOption.REPLACE_EXISTING)
                    }
                    Files.copy(srcMod, targetMod, StandardCopyOption.REPLACE_EXISTING)
                    Files.copy(
                        srcMod,
                        targetModDir.resolve("aarch64-unknown-linux-android28.swiftmodule"),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                for (extraMod in listOf("Combine", "CoreGraphics", "UIKit")) {
                    val extraSrc = backup.resolve("$extraMod.swiftmodule/aarch64-unknown-linux-android.swiftmodule")
                        .takeIf { Files.isRegularFile(it) }
                        ?: backup.resolve("$extraMod.swiftmodule")
                            .takeIf { Files.isRegularFile(it) }
                    if (extraSrc != null && Files.isRegularFile(extraSrc)) {
                        val extraTargetDir = swiftAndroidDir.resolve("$extraMod.swiftmodule")
                        val extraTarget = extraTargetDir.resolve("aarch64-unknown-linux-android.swiftmodule")
                        if (!Files.isRegularFile(extraTarget)) {
                            Files.createDirectories(extraTargetDir)
                            Files.copy(extraSrc, extraTarget, StandardCopyOption.REPLACE_EXISTING)
                            Files.copy(
                                extraSrc,
                                extraTargetDir.resolve("aarch64-unknown-linux-android28.swiftmodule"),
                                StandardCopyOption.REPLACE_EXISTING,
                            )
                        }
                    }
                }
                return true
            }
        }
        if (Files.isRegularFile(targetLib) && Files.isRegularFile(targetMod)) {
            return true
        }
        if (installation.packId == "host-system") {
            val hostSdk = Path.of(System.getProperty("user.home", ""), ".building/swiftui-bridge-sdk")
            if (Files.isRegularFile(hostSdk.resolve("libSwiftUI.a")) && Files.isDirectory(hostSdk.resolve("SwiftUI.swiftmodule"))) {
                return true
            }
        }
        false
    }.getOrDefault(false)

    private val SUPPORTED_SWIFTUI_IMPORTS = setOf(
        "SwiftUI",
        "Foundation",
        "Combine",
        "CoreGraphics",
        "UIKit",
        "AppKit",
        "QuartzCore",
        "Observation",
        "Swift",
        "Darwin",
        "Glibc",
        "Android",
        "AudioToolbox",
        "AVFoundation",
        "PhotosUI",
        "WebKit",
    )

    internal val CUPERTINO_BRIDGE_PRELUDE = """
        import SwiftUI
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
        public typealias ObservedObject<ObjectType: SwiftUI.ObservableObject> = SwiftUI.ObservedObject<ObjectType>
        public typealias StateObject<ObjectType: SwiftUI.ObservableObject> = SwiftUI.StateObject<ObjectType>

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

        public struct Locale: Sendable {
            public static let current = Locale()
            public init() {}
            public func localizedString(forIdentifier id: String) -> String? { id }
        }

        public enum RoundedCornerStyle: Sendable { case circular, continuous }
        public extension RoundedRectangle {
            init(cornerRadius: Double, style: RoundedCornerStyle) {
                self.init(cornerRadius: cornerRadius)
            }
            func trim(from: Double, to: Double) -> Self { self }
            func stroke(_ content: Color, lineWidth: Double = 1) -> Self { self }
        }
        public extension Circle {
            func stroke(_ content: Color, lineWidth: Double = 1) -> Self { self }
        }
        public extension Animation {
            func repeatForever(autoreverses: Bool = true) -> Animation { self }
            func repeatCount(_ repeatCount: Int, autoreverses: Bool = true) -> Animation { self }
            func delay(_ delay: Double) -> Animation { self }
            func speed(_ speed: Double) -> Animation { self }
        }
        public struct Material: ShapeStyle, Sendable {
            public static let regularMaterial = Material()
            public static let thinMaterial = Material()
            public static let ultraThinMaterial = Material()
            public static let thickMaterial = Material()
        }
        public extension ShapeStyle where Self == Material {
            static var regularMaterial: Material { .regularMaterial }
            static var thinMaterial: Material { .thinMaterial }
            static var ultraThinMaterial: Material { .ultraThinMaterial }
            static var thickMaterial: Material { .thickMaterial }
        }
        public enum ControlSize: Sendable { case mini, small, regular, large, extraLarge }
        public extension _ControlStyle {
            static let glassProminent = _ControlStyle.borderedProminent
            static let glass = _ControlStyle.bordered
        }
        public struct RoundedBorderTextFieldStyle: Sendable {
            public init() {}
        }
        public enum NavigationBarTitleDisplayMode: Sendable { case automatic, inline, large }
        public enum TextInputAutocapitalization: Sendable { case never, words, sentences, characters }
        public extension View {
            func background<S: ShapeStyle, Sh>(_ style: S, in shape: Sh) -> Self { self }
            func controlSize(_ size: ControlSize) -> Self { self }
            func accessibilityHint(_ hint: String) -> Self { self }
            func accessibilityValue(_ value: String) -> Self { self }
            func layoutPriority(_ value: Double) -> Self { self }
            func fixedSize(horizontal: Bool = true, vertical: Bool = true) -> Self { self }
            func textFieldStyle<S>(_ style: S) -> Self { self }
            func navigationBarTitleDisplayMode(_ mode: NavigationBarTitleDisplayMode) -> Self { self }
            func autocorrectionDisabled(_ disabled: Bool = true) -> Self { self }
            func textInputAutocapitalization(_ autocapitalization: TextInputAutocapitalization?) -> Self { self }
            func onChange<V: Equatable>(of value: V, initial: Bool = false, _ action: @escaping (V, V) -> Void) -> Self { self }
            func focused(_ condition: Binding<Bool>) -> Self { self }
            func onHover(perform action: @escaping (Bool) -> Void) -> Self { self }
            func allowsHitTesting(_ enabled: Bool) -> Self { self }
            func labelsHidden() -> Self { self }
        }

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
            static let cyan = Color(red: 0.0, green: 0.8, blue: 1.0)
            static let indigo = Color(red: 0.35, green: 0.34, blue: 0.84)
        }
        public extension ShapeStyle where Self == Color {
            static var indigo: Color { .indigo }
            static var secondary: Color { .secondary }
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
        public extension Circle {
            func trim(from: Double, to: Double) -> Self { self }
            func stroke<S>(_ content: S, style: StrokeStyle) -> Self { self }
        }
        public extension VerticalAlignment {
            static let firstTextBaseline = VerticalAlignment.center
            static let lastTextBaseline = VerticalAlignment.center
        }
        public extension Font.Design {
            static let rounded = Font.Design.default
        }
        public enum DynamicTypeSize: Int, Comparable, Sendable {
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

        // Observation / @Observable fallback when OpenAppleMacrosServer is not loaded
        // (@propertyWrapper public struct Observable fallback handled via @State / Observable protocol)
        public protocol Observable: AnyObject {}

        // UIKit & Foundation compatibility shims for Android / Cupertino Bridge
        public final class UIScreen: @unchecked Sendable {
            public static let main = UIScreen()
            public var bounds: CGRect = CGRect(x: 0, y: 0, width: 390, height: 844)
            public var scale: Double = 3.0
            public var nativeBounds: CGRect = CGRect(x: 0, y: 0, width: 1170, height: 2532)
            public init() {}
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
            public static let systemBackground = UIColor(red: 1.0, green: 1.0, blue: 1.0, alpha: 1.0)
            public static let secondarySystemBackground = UIColor(red: 0.95, green: 0.95, blue: 0.97, alpha: 1.0)
            public static let tertiarySystemBackground = UIColor(red: 1.0, green: 1.0, blue: 1.0, alpha: 1.0)
            public static let systemGroupedBackground = UIColor(red: 0.95, green: 0.95, blue: 0.97, alpha: 1.0)
            public static let secondarySystemGroupedBackground = UIColor(red: 1.0, green: 1.0, blue: 1.0, alpha: 1.0)
            public static let label = UIColor(red: 0.0, green: 0.0, blue: 0.0, alpha: 1.0)
            public static let secondaryLabel = UIColor(red: 0.24, green: 0.24, blue: 0.26, alpha: 0.6)
            public static let tertiaryLabel = UIColor(red: 0.24, green: 0.24, blue: 0.26, alpha: 0.3)
            public static let systemBlue = UIColor(red: 0.0, green: 0.48, blue: 1.0, alpha: 1.0)
            public static let systemRed = UIColor(red: 1.0, green: 0.23, blue: 0.19, alpha: 1.0)
            public static let systemGreen = UIColor(red: 0.20, green: 0.78, blue: 0.35, alpha: 1.0)
            public static let systemOrange = UIColor(red: 1.0, green: 0.58, blue: 0.0, alpha: 1.0)
            public static let systemYellow = UIColor(red: 1.0, green: 0.80, blue: 0.0, alpha: 1.0)
            public static let systemPurple = UIColor(red: 0.69, green: 0.32, blue: 0.87, alpha: 1.0)
            public static let systemPink = UIColor(red: 1.0, green: 0.18, blue: 0.33, alpha: 1.0)
            public static let systemTeal = UIColor(red: 0.19, green: 0.69, blue: 0.78, alpha: 1.0)
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
            public init() {
                self.systemName = nil
                self.named = nil
            }
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
            public static func preferredFont(forTextStyle style: String) -> UIFont { UIFont(size: 17.0) }
        }

        open class UIView: @unchecked Sendable {
            public var frame: CGRect
            public var bounds: CGRect
            public var backgroundColor: UIColor?
            public var isHidden: Bool = false
            public var alpha: Double = 1.0
            public var subviews: [UIView] = []
            public init(frame: CGRect = CGRect()) {
                self.frame = frame
                self.bounds = CGRect(origin: CGPoint(), size: frame.size)
            }
            open func addSubview(_ view: UIView) { subviews.append(view) }
            open func layoutSubviews() {}
            open func setNeedsLayout() {}
            open func setNeedsDisplay() {}
        }

        open class UIViewController: @unchecked Sendable {
            public var view: UIView = UIView()
            public var title: String?
            public var children: [UIViewController] = []
            public init() {}
            public init(nibName: String?, bundle: Any?) {}
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

        // UIViewRepresentable & UIViewControllerRepresentable shims
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

        public final class UIApplication: @unchecked Sendable {
            public static let shared = UIApplication()
            public init() {}
            public func open(_ url: URL, options: [String: Any] = [:], completionHandler completion: ((Bool) -> Void)? = nil) {
                completion?(true)
            }
            public func canOpenURL(_ url: URL) -> Bool { true }
            public func sendAction(_ action: Any, to target: Any?, from sender: Any?, for event: Any?) -> Bool { true }
        }

        public final class UIPasteboard: @unchecked Sendable {
            public static let general = UIPasteboard()
            public var string: String?
            public var strings: [String]? {
                get { string.map { [$0] } }
                set { string = newValue?.first }
            }
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
        public extension Capsule {
            init(style: RoundedCornerStyle) { self.init() }
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
            public var body: some View { EmptyView() }
            public init(selection: Any, matching: Any? = nil, photoLibrary: Any? = nil, @ViewBuilder label: () -> Label) {}
        }
        public struct PhotosPickerItem: Sendable {
            public init() {}
            public func loadTransferable<T>(type: T.Type) async throws -> T? { nil }
        }

        // WebKit shims
        public class WKWebView: @unchecked Sendable {
            public init() {}
            public init(frame: Any, configuration: Any) {}
            public func load(_ request: Any) {}
            public func loadHTMLString(_ string: String, baseURL: URL?) {}
        }
        public class WKWebViewConfiguration: @unchecked Sendable {
            public init() {}
        }
        public class WKNavigation: @unchecked Sendable {
            public init() {}
        }
        public protocol WKNavigationDelegate: AnyObject {}
        public protocol WKUIDelegate: AnyObject {}
    """.trimIndent()

    internal fun stripPreviewMacros(text: String): String {
        if (!text.contains("#Preview")) return text
        val out = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            val idx = text.indexOf("#Preview", i)
            if (idx == -1) {
                out.append(text, i, n)
                break
            }
            out.append(text, i, idx)
            var j = idx + "#Preview".length
            while (j < n && text[j].isWhitespace()) j++
            if (j < n && text[j] == '(') {
                var depth = 1
                j++
                while (j < n && depth > 0) {
                    when (text[j]) {
                        '(' -> depth++
                        ')' -> depth--
                    }
                    j++
                }
            }
            while (j < n && text[j].isWhitespace()) j++
            if (j < n && text[j] == '{') {
                var depth = 1
                j++
                while (j < n && depth > 0) {
                    when (text[j]) {
                        '{' -> depth++
                        '}' -> depth--
                    }
                    j++
                }
            }
            out.append("// [sanitized #Preview]")
            i = j
        }
        return out.toString()
    }

    /**
     * Preserves all `struct`/`class`/`enum`/`extension` declarations intact without deleting
     * user types that reference UIKit/AppKit/Canvas symbols (which are provided by
     * [CUPERTINO_BRIDGE_PRELUDE]).
     */
    internal fun dropUnsupportedDeclarations(text: String): String = text

    internal fun discoverInternalSwiftModules(projectRoot: Path): Set<String> {
        val mods = LinkedHashSet<String>()
        runCatching {
            Files.walk(projectRoot, 4).use { stream ->
                stream.filter { it.fileName?.toString() == "Package.swift" && Files.isRegularFile(it) }
                    .forEach { pkg ->
                        val text = runCatching { pkg.readText() }.getOrDefault("")
                        Regex("""name:\s*"([A-Za-z0-9_]+)"""").findAll(text).forEach { m ->
                            mods.add(m.groupValues[1])
                        }
                    }
            }
        }
        return mods
    }

    internal fun sanitizeSwiftUiSource(
        text: String,
        internalModules: Set<String> = emptySet(),
        hasMacroServer: Boolean = false,
    ): String {
        var t = stripPreviewMacros(text)
        t = dropUnsupportedDeclarations(t)
        t = t.replace(Regex("""#if\s+os\(macOS\)"""), "#if false")
        val foundationReexportMods = setOf("AppKit", "UIKit", "AVFoundation")
        val sanitizedMods = internalModules + listOf(
            "AppKit", "UIKit", "QuartzCore",
            "AudioToolbox", "AVFoundation", "PhotosUI", "WebKit", "Observation"
        )
        for (mod in sanitizedMods) {
            val replacement = if (mod in foundationReexportMods) {
                "import Foundation // [sanitized import $mod]"
            } else {
                "// [sanitized import $mod]"
            }
            t = t.replace(
                Regex("""(?m)^\s*import\s+${Regex.escape(mod)}\b.*$"""),
                replacement,
            )
        }
        if (!hasMacroServer) {
            t = t.replace(
                Regex("""@Observable\s+((?:(?:public|internal|private|fileprivate|final)\s+)*class\s+\w+)"""),
                "/* @Observable */ $1",
            )
        }
        t = t.replace("private struct ", "struct ")
        t = t.replace("private enum ", "enum ")
        t = t.replace("private class ", "class ")
        t = t.replace(
            Regex("""@FocusState\s+(private\s+)?var\s+(\w+)\s*:\s*Bool(?!\s*=)"""),
            "@State $1var $2: Bool = false",
        )
        return t
    }

    private val MEMBERWISE_PROP_REGEX = Regex(
        """^(?:@(ObservedObject|Binding|Bindable)\s+)?(?:(?:public|internal)\s+)?(?:let|var)\s+(\w+)\s*:\s*([^={]+)$"""
    )

    internal fun inferPreviewExpression(text: String): String {
        val prevMatch = Regex("""struct\s+(\w+)\s*:\s*PreviewProvider\b""").find(text)
        if (prevMatch != null) {
            return "${prevMatch.groupValues[1]}.previews"
        }
        val views = Regex("""struct\s+(\w+)\s*:\s*View\b""").findAll(text).map { it.groupValues[1] }.toList()
        if (views.isEmpty()) return "EmptyView()"
        val orderedViews = views.sortedByDescending {
            when {
                it.contains("Content") || it.contains("Main") || it.contains("Root") ||
                    it.contains("App") || it.contains("Panel") || it.contains("Home") -> 2
                it.contains("View") -> 1
                else -> 0
            }
        }
        for (v in orderedViews) {
            val idx = text.indexOf("struct $v")
            val bodyIdx = if (idx >= 0) text.indexOf("var body", idx) else -1
            val headerSlice = if (idx >= 0 && bodyIdx > idx) text.substring(idx, bodyIdx) else ""
            if (headerSlice.contains("init()")) return "$v()"
            val uninitProps = headerSlice.lineSequence().drop(1).map { it.trim() }.filter { s ->
                !s.contains("=") && !s.contains("{") && MEMBERWISE_PROP_REGEX.matches(s)
            }.toList()
            if (uninitProps.isEmpty()) return "$v()"
        }
        val v = orderedViews.first()
        val idx = text.indexOf("struct $v")
        val bodyIdx = if (idx >= 0) text.indexOf("var body", idx) else -1
        val headerSlice = if (idx >= 0 && bodyIdx > idx) text.substring(idx, bodyIdx) else ""
        data class PreviewInitArg(val name: String, val type: String, val isBinding: Boolean)
        val args = ArrayList<PreviewInitArg>()
        val initMatch = Regex("""init\s*\(([^\)]*)\)""").find(headerSlice)
        if (initMatch != null) {
            for (rawParam in initMatch.groupValues[1].split(',')) {
                val p = rawParam.trim()
                if (p.isEmpty() || p.contains("=")) continue
                val pm = Regex("""(\w+)(?:\s+\w+)?\s*:\s*(.+)""").find(p)
                if (pm != null) {
                    val ptype = pm.groupValues[2].trim()
                    args.add(PreviewInitArg(pm.groupValues[1], ptype, ptype.startsWith("Binding<")))
                }
            }
        } else {
            for (line in headerSlice.lineSequence().drop(1)) {
                val s = line.trim()
                if (!s.contains("=") && !s.contains("{")) {
                    val pm = MEMBERWISE_PROP_REGEX.find(s)
                    if (pm != null) {
                        val wrapper = pm.groupValues[1]
                        args.add(PreviewInitArg(pm.groupValues[2], pm.groupValues[3].trim(), wrapper == "Binding"))
                    }
                }
            }
        }
        val renderedArgs = args.map { arg ->
            val ptype = arg.type.removePrefix("Binding<").removeSuffix(">")
            val baseVal = when {
                "->" in ptype -> "{}"
                ptype.endsWith("?") -> "nil"
                ptype == "String" -> "\"${arg.name.replaceFirstChar { it.uppercaseChar() }}\""
                ptype in setOf("Double", "CGFloat", "Float") -> "0.72"
                ptype in setOf("Int", "UInt64", "Int64") -> "42"
                ptype == "Bool" -> "true"
                else -> ".init()"
            }
            val argVal = if (arg.isBinding) ".constant($baseVal)" else baseVal
            "${arg.name}: $argVal"
        }
        return "$v(${renderedArgs.joinToString(", ")})"
    }

    internal fun countUnsupportedSwiftImports(text: String, internalModules: Set<String> = emptySet()): Int {
        val sanitized = text.replace(Regex("""#if\s+os\(macOS\)"""), "#if false")
        var inFalse = 0
        var count = 0
        for (line in sanitized.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("#if false")) {
                inFalse++
            } else if (t.startsWith("#if ") && inFalse > 0) {
                inFalse++
            } else if (t.startsWith("#else") && inFalse == 1) {
                inFalse = 0
            } else if (t.startsWith("#endif") && inFalse > 0) {
                inFalse--
            } else if (inFalse == 0 && t.startsWith("import ")) {
                val rest = t.removePrefix("import ").trim()
                val parts = rest.split(Regex("""\s+"""))
                val mod = parts.lastOrNull()?.substringBefore('.')?.takeWhile { it.isLetterOrDigit() || it == '_' }.orEmpty()
                if (mod.isNotEmpty() && mod !in SUPPORTED_SWIFTUI_IMPORTS && mod !in internalModules) {
                    count++
                }
            }
        }
        return count
    }

    internal fun isNonUiPlatformServiceFile(raw: String): Boolean {
        return raw.contains(": NSPanel") ||
            raw.contains(": NSWindow") ||
            raw.contains("NSPanel?") ||
            raw.contains("NSBitmapImageRep") ||
            raw.contains("CGEvent.tapCreate") ||
            raw.contains("AVAudioPCMBuffer") ||
            raw.contains("URLSessionConfiguration")
    }

    internal fun synthesizeFocusedCompanionShims(includedTexts: List<String>): String? {
        val combined = includedTexts.joinToString("\n")
        if (!combined.contains("CompanionManager") ||
            Regex("""(?:class|struct)\s+CompanionManager\b""").containsMatchIn(combined)
        ) {
            return null
        }
        return """
            import Foundation
            import SwiftUI

            extension Notification.Name {
                static let clickyDismissPanel = Notification.Name("clickyDismissPanel")
            }

            enum CompanionVoiceState: Sendable {
                case idle
                case listening
                case processing
                case responding
            }

            final class BuddyDictationManagerStub: ObservableObject, @unchecked Sendable {
                @Published var transcriptionProviderDisplayName: String = "Apple Speech"
                init() {}
            }

            final class CompanionManager: ObservableObject, @unchecked Sendable {
                nonisolated(unsafe) static weak var sharedInstance: CompanionManager?

                @Published var voiceState: CompanionVoiceState = .idle
                @Published var hasAccessibilityPermission: Bool = false
                @Published var hasScreenRecordingPermission: Bool = false
                @Published var hasMicrophonePermission: Bool = false
                @Published var hasScreenContentPermission: Bool = true
                @Published var isOverlayVisible: Bool = true
                @Published var selectedModel: String = "claude-sonnet-4-6"
                @Published var isClickyCursorEnabled: Bool = true
                @Published var hasCompletedOnboarding: Bool = false
                @Published var hasSubmittedEmail: Bool = false
                let buddyDictationManager = BuddyDictationManagerStub()

                var allPermissionsGranted: Bool {
                    hasAccessibilityPermission && hasScreenRecordingPermission && hasMicrophonePermission && hasScreenContentPermission
                }

                init() {
                    Self.sharedInstance = self
                }

                func setSelectedModel(_ model: String) {
                    selectedModel = model
                }

                func setClickyCursorEnabled(_ enabled: Bool) {
                    isClickyCursorEnabled = enabled
                    isOverlayVisible = enabled
                }

                func submitEmail(_ email: String) {
                    let trimmed = email.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !trimmed.isEmpty else { return }
                    hasSubmittedEmail = true
                }

                func triggerOnboarding() {
                    hasCompletedOnboarding = true
                    isOverlayVisible = true
                }

                func replayOnboarding() {
                    hasCompletedOnboarding = true
                    isOverlayVisible = true
                }

                func requestScreenContentPermission() {
                    hasScreenContentPermission = true
                }
            }

            enum WindowPositionManager {
                static func requestAccessibilityPermission() {
                    CompanionManager.sharedInstance?.hasAccessibilityPermission = true
                }
                static func revealAppInFinder() {}
                static func openAccessibilitySettings() {}
                static func requestScreenRecordingPermission() {
                    CompanionManager.sharedInstance?.hasScreenRecordingPermission = true
                }
            }
        """.trimIndent() + "\n"
    }

    /**
     * Source files that a focused Cupertino preview must compile together with its candidate.
     *
     * The previous behaviour compiled only the single candidate file, which dropped every other
     * declaration in the app target; a candidate that referenced a type declared elsewhere then
     * failed with `cannot find type 'X' in scope` (e.g. a preview referencing a `ButtonType` enum
     * declared in a sibling `Models/` file). Here we collect every repo source that has no
     * unsupported imports, excluding the real `@main` app entry (the shadow main supplies its own)
     * and build/test directories, so transitive module dependencies resolve.
     */
    internal fun focusedCompanionSources(
        projectRoot: Path,
        internalModules: Set<String>,
    ): List<Path> {
        return SwiftBuildSystem.findSwiftSourceFiles(projectRoot).filterNot { f ->
            val s = f.toString().replace('\\', '/')
            s.contains("/.build/") || s.contains("/Pods/") ||
                s.contains("/Tests/") || s.contains("/UITests/") || s.contains("/UnitTests/")
        }.filter { f ->
            val raw = runCatching { f.readText() }.getOrDefault("")
            countUnsupportedSwiftImports(raw, internalModules) == 0 &&
                !isNonUiPlatformServiceFile(raw) &&
                !Regex("""@main|struct\s+\w+\s*:\s*App\b""").containsMatchIn(raw)
        }
    }

    /**
     * When a repository contains multiple app targets or subprojects (for example,
     * `takenori-kabeya/SwiftUIStateManagementBook-Counter` which has both `SimpleCounterSwiftUI/`
     * and `SimpleCounterTCA/`), selects the single runnable SwiftUI target directory that has no
     * unresolvable external framework imports.
     */
    internal fun selectSwiftUiTargetFiles(projectRoot: Path, candidateFiles: List<Path>): List<Path> {
        if (candidateFiles.isEmpty()) return candidateFiles
        val internalModules = discoverInternalSwiftModules(projectRoot)
        // 1. Filter out non-iOS directories (Mac, tests, watch, etc.)
        val iosFiles = candidateFiles.filterNot { f ->
            val s = f.toString().replace('\\', '/')
            s.contains("/Mac/") || s.contains("/macOS/") ||
                s.contains("/Tests/") || s.contains("/UnitTests/") || s.contains("/UITests/") ||
                s.contains("/Watch/") || s.contains("/watchOS/") || s.contains("/Pods/")
        }.ifEmpty { candidateFiles }

        val fileTexts = iosFiles.associateWith { f ->
            runCatching { f.readText() }.getOrDefault("")
        }

        val pureSwiftUiFiles = iosFiles.filter { f ->
            val text = fileTexts[f].orEmpty()
            text.contains("import SwiftUI") && countUnsupportedSwiftImports(text, internalModules) == 0
        }

        val mainFiles = iosFiles.filter { f ->
            val text = fileTexts[f].orEmpty()
            text.contains("@main") || Regex("""struct\s+\w+\s*:\s*App\b""").containsMatchIn(text)
        }

        if (mainFiles.isNotEmpty()) {
            data class TargetCandidate(
                val mainFile: Path,
                val targetDir: Path,
                val files: List<Path>,
                val unsupportedImports: Int,
                val importsSwiftUi: Boolean,
            )

            val candidates = mainFiles.map { mainFile ->
                var targetDir = mainFile.parent ?: projectRoot
                var curr = mainFile.parent
                while (curr != null && curr != projectRoot) {
                    if (Files.exists(curr.resolve("Package.swift")) ||
                        runCatching { Files.list(curr).use { s -> s.anyMatch { it.fileName.toString().endsWith(".xcodeproj") } } }.getOrDefault(false)) {
                        targetDir = curr
                        break
                    }
                    val p = curr.parent
                    if (p != null && p != projectRoot) {
                        val pName = p.fileName?.toString()?.lowercase()
                        if (pName in setOf("experiments", "samples", "examples", "apps", "packages", "projects", "subprojects", "demos")) {
                            targetDir = curr
                            break
                        }
                        val hasCommonSubdirs = runCatching {
                            Files.list(p).use { stream ->
                                stream.anyMatch { dir ->
                                    Files.isDirectory(dir) && dir.fileName?.toString()?.lowercase() in setOf(
                                        "views", "models", "styles", "viewmodels", "components", "shared", "model", "view"
                                    )
                                }
                            }
                        }.getOrDefault(false)
                        if (hasCommonSubdirs) {
                            targetDir = p
                        }
                    }
                    curr = p
                }
                val scopedFiles = iosFiles.filter { it.startsWith(targetDir) }
                val unsupported = scopedFiles.sumOf { countUnsupportedSwiftImports(fileTexts[it].orEmpty(), internalModules) }
                val hasSwiftUi = scopedFiles.any { fileTexts[it].orEmpty().contains("import SwiftUI") }
                TargetCandidate(mainFile, targetDir, scopedFiles, unsupported, hasSwiftUi)
            }

            val best = candidates.sortedWith(
                compareBy<TargetCandidate> { !it.importsSwiftUi }
                    .thenBy { it.unsupportedImports }
                    .thenBy { it.mainFile.toString().contains("TCA", ignoreCase = true) }
                    .thenBy { it.targetDir.toString().length },
            ).firstOrNull()

            if (best != null && (best.unsupportedImports == 0 || pureSwiftUiFiles.isEmpty())) {
                val cleanFiles = best.files.filter {
                    val raw = fileTexts[it].orEmpty()
                    countUnsupportedSwiftImports(raw, internalModules) == 0 && !isNonUiPlatformServiceFile(raw)
                }
                return if (cleanFiles.isNotEmpty()) cleanFiles else best.files
            }
        }

        // When no clean @main candidate exists (or in multi-target projects like NetNewsWire where @main is a Widget),
        // pick the cluster of pure SwiftUI views and include all sibling/child directories
        if (pureSwiftUiFiles.isNotEmpty()) {
            val byTargetDir = pureSwiftUiFiles.groupBy { f ->
                var d = f.parent ?: projectRoot
                val p = d.parent
                if (p != null && p != projectRoot) {
                    val hasCommonSubdirs = runCatching {
                        Files.list(p).use { stream ->
                            stream.anyMatch { dir ->
                                Files.isDirectory(dir) && dir.fileName?.toString()?.lowercase() in setOf(
                                    "views", "models", "styles", "viewmodels", "components", "shared", "model", "view"
                                )
                            }
                        }
                    }.getOrDefault(false)
                    if (hasCommonSubdirs) d = p
                }
                d
            }
            val bestTarget = byTargetDir.maxByOrNull { (_, files) ->
                files.count { f ->
                    val t = fileTexts[f].orEmpty()
                    t.contains(": View") && (t.contains("About") || t.contains("Content") || t.contains("Main") || t.contains("App") || t.contains("Panel"))
                } * 10 + files.size
            }?.key
            if (bestTarget != null) {
                val targetClean = iosFiles.filter {
                    val raw = fileTexts[it].orEmpty()
                    it.startsWith(bestTarget) && countUnsupportedSwiftImports(raw, internalModules) == 0 && !isNonUiPlatformServiceFile(raw)
                }
                return if (targetClean.isNotEmpty()) targetClean else iosFiles.filter { it.startsWith(bestTarget) }
            }
            return pureSwiftUiFiles
        }

        return iosFiles
    }

    /**
     * Build a disposable **shadow workspace** for Cupertino Run so the cloned GitHub/Xcode
     * project is never rewritten for the Android bridge. Copies the selected SwiftUI sources
     * and, when the tree has no bridge entry, a tiny `@main` that calls `runPreview { … }`.
     * xtool always runs on the original project root for the real IPA.
     */
    internal fun prepareCupertinoShadow(
        projectRoot: Path,
        scratchDir: Path?,
    ): Path {
        val shadow = (scratchDir ?: projectRoot.resolve(".building")).resolve("cupertino-shadow")
        if (Files.isDirectory(shadow)) {
            Files.walk(shadow).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
        Files.createDirectories(shadow)
        val internalModules = discoverInternalSwiftModules(projectRoot)
        val allSwiftFiles = SwiftBuildSystem.findSwiftSourceFiles(projectRoot)
        val filteredFiles = allSwiftFiles.filter { f ->
            val name = f.fileName.toString()
            if (name.endsWith("Main.swift")) {
                val text = runCatching { f.readText() }.getOrDefault("")
                !text.contains("Swift TargetReady")
            } else true
        }.ifEmpty { allSwiftFiles }
        val swiftUiFiles = selectSwiftUiTargetFiles(projectRoot, filteredFiles)
        val copied = ArrayList<Path>()
        fun copyAndSanitize(src: Path, dst: Path) {
            Files.createDirectories(dst.parent)
            val raw = runCatching { src.readText() }.getOrDefault("")
            val content = sanitizeSwiftUiSource(raw, internalModules)
            dst.writeText(content)
            copied.add(dst)
        }

        for (src in swiftUiFiles) {
            val rel = runCatching { projectRoot.relativize(src) }.getOrDefault(src.fileName)
            val dst = shadow.resolve(rel.toString())
            copyAndSanitize(src, dst)
        }

        // Recursively discover and copy all .swift files across subdirectories
        // (Models/, Views/, Styles/, ViewModels/, Components/) in the target directory tree
        val targetRoots = swiftUiFiles.mapNotNull { f ->
            var d = f.parent
            while (d != null && d != projectRoot && d.parent != null && d.parent != projectRoot) {
                val pName = d.fileName?.toString()?.lowercase()
                if (pName in setOf("experiments", "samples", "examples", "apps", "packages", "projects", "subprojects", "demos")) {
                    break
                }
                if (Files.exists(d.resolve("Package.swift")) ||
                    runCatching { Files.list(d).use { s -> s.anyMatch { it.fileName.toString().endsWith(".xcodeproj") } } }.getOrDefault(false)) {
                    break
                }
                d = d.parent
            }
            d ?: f.parent
        }.distinct().ifEmpty { listOf(projectRoot) }

        for (root in targetRoots) {
            if (Files.isDirectory(root)) {
                Files.walk(root).use { stream ->
                    stream.filter { f ->
                        Files.isRegularFile(f) &&
                            f.fileName.toString().endsWith(".swift") &&
                            !f.fileName.toString().endsWith("Main.swift")
                    }.forEach { candidate ->
                        val relStr = candidate.toString().replace('\\', '/')
                        val isIgnored = relStr.contains("/.build/") || relStr.contains("/Pods/") ||
                            relStr.contains("/Tests/") || relStr.contains("/UnitTests/") || relStr.contains("/UITests/") ||
                            relStr.contains("/Mac/") || relStr.contains("/macOS/") || relStr.contains("/Watch/")
                        if (!isIgnored) {
                            val text = runCatching { candidate.readText() }.getOrDefault("")
                            if (!text.contains("import XCTest") &&
                                !text.contains("exit(") &&
                                countUnsupportedSwiftImports(text, internalModules) == 0 &&
                                !isNonUiPlatformServiceFile(text)
                            ) {
                                val rel = runCatching { projectRoot.relativize(candidate) }.getOrDefault(candidate.fileName)
                                val dst = shadow.resolve(rel.toString())
                                if (!Files.exists(dst)) {
                                    copyAndSanitize(candidate, dst)
                                }
                            }
                        }
                    }
                }
            }
        }

        val copiedTexts = copied.map { runCatching { it.readText() }.getOrDefault("") }
        synthesizeFocusedCompanionShims(copiedTexts)?.let { shims ->
            val shimFile = shadow.resolve("FocusedCompanionShims.swift")
            shimFile.writeText(shims)
            copied.add(shimFile)
        }

        // Always write CupertinoBridgePrelude.swift into the shadow workspace so all shims
        // and extensions (UIViewRepresentable, ToggleStyle, frameworks, etc.) are available
        shadow.resolve("CupertinoBridgePrelude.swift").writeText(CUPERTINO_BRIDGE_PRELUDE + "\n")

        // Copy .xcassets asset catalogs and image resources into shadow workspace
        runCatching {
            Files.walk(projectRoot, 6).use { stream ->
                stream.filter { p ->
                    Files.isDirectory(p) && p.fileName.toString().endsWith(".xcassets")
                }.forEach { xcassetsDir ->
                    val rel = runCatching { projectRoot.relativize(xcassetsDir) }.getOrDefault(xcassetsDir.fileName)
                    val shadowXcassets = shadow.resolve(rel.toString())
                    Files.walk(xcassetsDir).use { assetStream ->
                        assetStream.forEach { src ->
                            val subRel = xcassetsDir.relativize(src)
                            val dst = shadowXcassets.resolve(subRel.toString())
                            if (Files.isDirectory(src)) {
                                Files.createDirectories(dst)
                            } else {
                                Files.createDirectories(dst.parent)
                                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
                            }
                        }
                    }
                    val topAssets = shadow.resolve("Assets.xcassets")
                    if (!Files.exists(topAssets)) {
                        Files.walk(xcassetsDir).use { assetStream ->
                            assetStream.forEach { src ->
                                val subRel = xcassetsDir.relativize(src)
                                val dst = topAssets.resolve(subRel.toString())
                                if (Files.isDirectory(src)) {
                                    Files.createDirectories(dst)
                                } else {
                                    Files.createDirectories(dst.parent)
                                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
                                }
                            }
                        }
                    }
                }
            }
        }
        runCatching {
            for (root in targetRoots) {
                if (Files.isDirectory(root)) {
                    Files.walk(root, 4).use { stream ->
                        stream.filter { p ->
                            Files.isRegularFile(p) && p.fileName.toString().lowercase().let { n ->
                                n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".webp")
                            } && !p.toString().contains("/.build/")
                        }.forEach { imgFile ->
                            val rel = runCatching { projectRoot.relativize(imgFile) }.getOrDefault(imgFile.fileName)
                            val dst = shadow.resolve(rel.toString())
                            if (!Files.exists(dst)) {
                                Files.createDirectories(dst.parent)
                                Files.copy(imgFile, dst, StandardCopyOption.REPLACE_EXISTING)
                            }
                        }
                    }
                }
            }
        }

        val hasAppMain = copied.any { f ->
            val text = runCatching { f.readText() }.getOrDefault("")
            Regex("""@main\s+(?:public\s+|internal\s+)?struct\s+\w+\s*:\s*App\b""").containsMatchIn(text) ||
                (text.contains("@main") && text.contains(": App"))
        }
        val hasBridgeEntry = hasAppMain || copied.any { f ->
            val text = runCatching { f.readText() }.getOrDefault("")
            text.contains("runPreview") || text.contains("ViewHost(")
        }
        if (!hasBridgeEntry) {
            val text = copied.joinToString("\n") { runCatching { it.readText() }.getOrDefault("") }
            val expr = inferPreviewExpression(text)
            shadow.resolve("CupertinoShadowMain.swift").writeText(
                """
                import SwiftUI

                @main
                struct CupertinoShadowMain {
                    static func main() throws {
                        try runPreview { $expr }
                    }
                }
                """.trimIndent() + "\n",
            )
        }
        return shadow
    }

    /**
     * Compiles a SwiftUI project's `.swift` sources against the on-device `SwiftUI` bridge module
     * and launches the interactive Cupertino GUI session in `RunScreen`.
     *
     * Compiles from [shadowRoot] when provided (see [prepareCupertinoShadow]); [projectRoot]
     * remains the original clone and is what xtool exports from.
     */
    suspend fun compileAndRunSwiftUiBridge(
        host: RuntimeHost,
        installation: RuntimeInstallation,
        swiftcBin: Path,
        projectRoot: Path,
        projectName: String,
        scratchDir: Path?,
        env: Map<String, String>,
        ctx: TaskContext,
        buildCtx: BuildContext?,
        shadowRoot: Path? = null,
    ): TaskResult {
        val compileRoot = shadowRoot ?: projectRoot
        val allSwiftFiles = SwiftBuildSystem.findSwiftSourceFiles(compileRoot)
        // Prefer real SwiftUI source files over any synthetic stub *Main.swift
        val filteredFiles = allSwiftFiles.filter { f ->
            val name = f.fileName.toString()
            if (name.endsWith("Main.swift")) {
                val text = runCatching { f.readText() }.getOrDefault("")
                !text.contains("Swift TargetReady")
            } else true
        }.ifEmpty { allSwiftFiles }
        val swiftUiFiles = if (shadowRoot != null) {
            filteredFiles
        } else {
            selectSwiftUiTargetFiles(compileRoot, filteredFiles)
        }

        if (swiftUiFiles.isEmpty()) {
            return TaskResult.Failed("No Swift source files found in $compileRoot")
        }

        val pkgName = SwiftBuildSystem.extractSwiftPackageName(projectRoot)
        val outDir = (scratchDir ?: projectRoot.resolve(".building")).resolve("cupertino-shadow/debug")
        Files.createDirectories(outDir)
        val outBin = outDir.resolve("$pkgName-cupertino")

        val newestSourceMtime = swiftUiFiles.maxOfOrNull {
            runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L)
        } ?: 0L
        val binMtime = if (Files.isRegularFile(outBin) && Files.isExecutable(outBin)) {
            runCatching { Files.getLastModifiedTime(outBin).toMillis() }.getOrDefault(0L)
        } else 0L

        if (binMtime < newestSourceMtime || binMtime == 0L) {
            val sdkRoot = installation.prefix.parent ?: installation.prefix
            val swiftAndroidLib = installation.prefix.resolve("lib/swift/android")
            val archInclude = installation.prefix.resolve("include/aarch64-linux-android")
            val archLib = installation.prefix.resolve("lib/aarch64-linux-android")
            val hostBridgeSdk = Path.of(System.getProperty("user.home", ""), ".building/swiftui-bridge-sdk")
            val macroServer = installation.prefix.resolve("bin/OpenAppleMacrosServer")
            val hasMacroServer = Files.isRegularFile(macroServer) && Files.isExecutable(macroServer)
            fun buildSwiftcCommand(sources: List<Path>): List<String> = buildList {
                add(swiftcBin.toString())
                add("-parse-as-library")
                for (f in sources) add(f.toString())
                if (hasMacroServer) {
                    add("-load-plugin-executable")
                    add("$macroServer#ObservationMacros")
                }
                if (Files.isDirectory(swiftAndroidLib)) {
                    add("-sdk")
                    add(sdkRoot.toString())
                    add("-I$swiftAndroidLib")
                    add("-L$swiftAndroidLib")
                    if (Files.isDirectory(archInclude)) {
                        add("-Xcc")
                        add("-isystem")
                        add("-Xcc")
                        add(archInclude.toString())
                    }
                    if (Files.isDirectory(archLib)) {
                        add("-L$archLib")
                    }
                } else if (Files.isDirectory(hostBridgeSdk)) {
                    add("-I$hostBridgeSdk")
                    add("-L$hostBridgeSdk")
                }
                add("-lSwiftUI")
                add("-o")
                add(outBin.toString())
            }
            ctx.reportStep(":swift:compileCupertinoBridge", "started")
            val relNames = swiftUiFiles.joinToString(" ") {
                runCatching { compileRoot.relativize(it).toString() }.getOrDefault(it.fileName.toString())
            }
            ctx.buildLog.log(BuildLogEntry("$ swiftc -parse-as-library $relNames -lSwiftUI -o ${outBin.fileName}", BuildLogLevel.INFO))
            val compileLines = ArrayList<String>()
            var code = execSimple(host, buildSwiftcCommand(swiftUiFiles), compileRoot, env, ctx, compileLines)

            if (code != 0) {
                // Multi-file target had cross-module/platform symbols; try focused candidate SwiftUI view files in projectRoot
                val internalModules = discoverInternalSwiftModules(projectRoot)
                val repoSwiftFiles = SwiftBuildSystem.findSwiftSourceFiles(projectRoot).filterNot { f ->
                    val s = f.toString().replace('\\', '/')
                    s.contains("/.build/") || s.contains("/Pods/") ||
                        s.contains("/Tests/") || s.contains("/UITests/") || s.contains("/UnitTests/")
                }
                data class FocusedCandidate(val score: Int, val file: Path, val sanitized: String)
                val focusedCandidates = repoSwiftFiles.mapNotNull { p ->
                    val raw = runCatching { p.readText() }.getOrDefault("")
                    if (!raw.contains("import SwiftUI")) return@mapNotNull null
                    if (countUnsupportedSwiftImports(raw, internalModules) > 0) return@mapNotNull null
                    if (isNonUiPlatformServiceFile(raw)) return@mapNotNull null
                    val san = sanitizeSwiftUiSource(raw, internalModules, hasMacroServer)
                    val hasView = Regex("""struct\s+\w+\s*:\s*View\b""").containsMatchIn(san)
                    val hasPreview = Regex("""struct\s+\w+\s*:\s*PreviewProvider\b""").containsMatchIn(san)
                    if (!hasView && !hasPreview) return@mapNotNull null
                    val fname = p.fileName.toString()
                    val pathStr = p.toString().replace('\\', '/')
                    var score = 0
                    if (hasPreview) score += 50
                    if (listOf("ContentView", "MainView", "RootView", "AppView", "PanelView", "HomeView").any { fname.contains(it) }) {
                        score += 100
                    }
                    if (listOf("/Shared/", "/DesignSystem/", "/Views/", "Previews").any { pathStr.contains(it) }) {
                        score += 30
                    }
                    score -= san.length / 500
                    FocusedCandidate(score, p, san)
                }.sortedByDescending { it.score }

                val focusedDir = outDir.parent?.resolve("focused") ?: compileRoot.resolve("focused")
                // A focused preview must compile the candidate together with the rest of its module.
                // Building only the single candidate file (the previous behaviour) dropped the other
                // source files in the app target, so the candidate's references to types declared
                // elsewhere failed with "cannot find type 'X' in scope". Compile every repo source
                // that has no unsupported imports and is not the real @main app entry (the shadow main
                // supplies its own @main), so transitive module dependencies resolve.
                val safeCompanions = focusedCompanionSources(projectRoot, internalModules)
                for (cand in focusedCandidates.take(8)) {
                    if (Files.isDirectory(focusedDir)) {
                        Files.walk(focusedDir).use { stream ->
                            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                        }
                    }
                    Files.createDirectories(focusedDir)
                    val srcs = ArrayList<Path>()
                    val written = HashSet<String>()
                    val targetName = cand.file.fileName.toString()
                    written.add(targetName)
                    val targetFile = focusedDir.resolve(targetName)
                    targetFile.writeText(cand.sanitized)
                    srcs.add(targetFile)
                    for (comp in safeCompanions) {
                        val name = comp.fileName.toString()
                        if (!written.add(name)) continue
                        val target = focusedDir.resolve(name)
                        target.writeText(sanitizeSwiftUiSource(runCatching { comp.readText() }.getOrDefault(""), internalModules, hasMacroServer))
                        srcs.add(target)
                    }
                    val focusedTexts = srcs.map { runCatching { it.readText() }.getOrDefault("") }
                    synthesizeFocusedCompanionShims(focusedTexts)?.let { shims ->
                        val shimFile = focusedDir.resolve("FocusedCompanionShims.swift")
                        shimFile.writeText(shims)
                        srcs.add(shimFile)
                    }
                    val preludeFile = focusedDir.resolve("CupertinoBridgePrelude.swift")
                    preludeFile.writeText(CUPERTINO_BRIDGE_PRELUDE + "\n")
                    srcs.add(preludeFile)
                    val expr = inferPreviewExpression(cand.sanitized)
                    val mainFile = focusedDir.resolve("CupertinoShadowMain.swift")
                    mainFile.writeText(
                        """
                        import SwiftUI

                        @main
                        struct CupertinoShadowMain {
                            static func main() throws {
                                try runPreview { $expr }
                            }
                        }
                        """.trimIndent() + "\n",
                    )
                    srcs.add(mainFile)
                    compileLines.clear()
                    val attemptCode = execSimple(host, buildSwiftcCommand(srcs), focusedDir, env, ctx, compileLines)
                    if (attemptCode == 0) {
                        code = 0
                        break
                    }
                }
            }

            ctx.reportToolDiagnostics("swiftc", compileLines)
            if (code != 0) {
                ctx.reportStep(":swift:compileCupertinoBridge", "failed")
                return TaskResult.Failed("swiftc exited with code $code")
            }
            outBin.toFile().setExecutable(true)
            ctx.reportStep(":swift:compileCupertinoBridge", "succeeded")
        } else {
            ctx.buildLog.log(
                BuildLogEntry(
                    "✓ Using up-to-date ARM64 SwiftUI binary: ${outBin.fileName} (${Files.size(outBin) / 1024} KB)",
                    BuildLogLevel.INFO,
                ),
            )
        }

        val runEnv = LinkedHashMap(env).apply {
            val swiftAndroidLib = installation.prefix.resolve("lib/swift/android")
            val curLd = get("LD_LIBRARY_PATH").orEmpty()
            if (Files.isDirectory(swiftAndroidLib)) {
                put("LD_LIBRARY_PATH", listOf(curLd, swiftAndroidLib.toString()).filter { it.isNotBlank() }.joinToString(":"))
            }
        }

        return execInteractiveBridge(
            host = host,
            command = listOf(outBin.toString()),
            workingDir = compileRoot,
            env = runEnv,
            projectName = projectName,
            displayCommand = "$pkgName",
            stepName = ":swift:runCupertinoBridge",
            ctx = ctx,
            buildCtx = buildCtx,
        )
    }

    /**
     * Returns true if the Node.js project at [root] uses a desktop/window GUI toolkit
     * (`@nodegui/nodegui`, `libui-node`, or `x11`) that can be hosted via our GUI bridge shim.
     */
    fun isNodeGuiProject(root: Path): Boolean = runCatching {
        val pkg = root.resolve("package.json")
        if (Files.isRegularFile(pkg)) {
            val text = pkg.readText()
            if (text.contains("@nodegui/nodegui") ||
                text.contains("libui-node") ||
                text.contains("\"x11\"") ||
                text.contains("qode ")
            ) {
                return true
            }
        }
        val candidates = listOf(
            "src/index.ts", "src/index.js", "index.ts", "index.js", "main.js", "app.js",
        )
        for (rel in candidates) {
            val p = root.resolve(rel)
            if (Files.isRegularFile(p)) {
                val content = p.readText()
                if (content.contains("@nodegui/nodegui") ||
                    content.contains("libui-node") ||
                    content.contains("require('x11')") ||
                    content.contains("from 'x11'")
                ) {
                    return true
                }
            }
        }
        false
    }.getOrDefault(false)

    fun findNodeGuiEntrypoint(root: Path): Path? {
        val candidates = listOf(
            "src/index.ts",
            "src/index.js",
            "index.ts",
            "index.js",
            "src/main.ts",
            "src/main.js",
            "main.js",
            "app.js",
        )
        for (rel in candidates) {
            val p = root.resolve(rel)
            if (Files.isRegularFile(p)) return p
        }
        return null
    }

    /**
     * Installs our pure-JS Node GUI Bridge shims (`@nodegui/nodegui`, `libui-node`, `x11`, and `lodash`)
     * into `<projectRoot>/node_modules/` so unmodified NodeGUI / libui / X11 repositories run directly on
     * the embedded Node.js runtime and stream interactive `ViewNode` trees to `RunScreen`.
     */
    fun ensureNodeGuiBridgeShimInstalled(projectRoot: Path) {
        runCatching {
            val nodeModules = projectRoot.resolve("node_modules")
            // 1. @nodegui/nodegui
            val nodeguiDir = nodeModules.resolve("@nodegui/nodegui")
            Files.createDirectories(nodeguiDir)
            nodeguiDir.resolve("package.json").writeText(
                """
                {
                  "name": "@nodegui/nodegui",
                  "version": "0.60.0-bridge",
                  "main": "index.js"
                }
                """.trimIndent() + "\n",
            )
            nodeguiDir.resolve("index.js").writeText(NODEGUI_BRIDGE_SHIM_JS)

            // 2. libui-node
            val libuiDir = nodeModules.resolve("libui-node")
            Files.createDirectories(libuiDir)
            libuiDir.resolve("package.json").writeText(
                """
                {
                  "name": "libui-node",
                  "version": "0.3.0-bridge",
                  "main": "index.js"
                }
                """.trimIndent() + "\n",
            )
            libuiDir.resolve("index.js").writeText(NODEGUI_BRIDGE_SHIM_JS)

            // 3. x11
            val x11Dir = nodeModules.resolve("x11")
            Files.createDirectories(x11Dir)
            x11Dir.resolve("package.json").writeText(
                """
                {
                  "name": "x11",
                  "version": "2.3.0-bridge",
                  "main": "index.js"
                }
                """.trimIndent() + "\n",
            )
            x11Dir.resolve("index.js").writeText(NODEGUI_BRIDGE_SHIM_JS)
        }
    }

    /**
     * Runs an interactive GUI process (SwiftUI or NodeGUI/X11) while streaming `ViewNode` JSON trees
     * from `stdout` to `ProgramIo.frame(...)` and forwarding callback JSON lines from `ProgramIo.stdin`
     * to `child.stdin`.
     */
    suspend fun execInteractiveBridge(
        host: RuntimeHost,
        command: List<String>,
        workingDir: Path,
        env: Map<String, String>,
        projectName: String,
        displayCommand: String,
        stepName: String,
        ctx: TaskContext,
        buildCtx: BuildContext?,
    ): TaskResult = coroutineScope {
        ctx.reportStep(stepName, "started")
        ctx.buildLog.log(BuildLogEntry("$ $displayCommand", BuildLogLevel.INFO))

        // Open the interactive RunConsoleUi session immediately if a BuildContext is wired
        val io: ProgramIo? = buildCtx?.openProgramIo?.invoke(projectName, displayCommand)
        io?.started()

        val child = try {
            host.start(command, workingDir, env)
        } catch (e: Exception) {
            val msg = "Failed to start $displayCommand: ${e.message ?: e.javaClass.simpleName}"
            ctx.buildLog.log(BuildLogEntry(msg, BuildLogLevel.ERROR))
            io?.exited(1)
            ctx.reportStep(stepName, "failed")
            return@coroutineScope TaskResult.Failed(msg, e)
        }

        val frameSeq = AtomicLong(0L)
        val firstFrameLogged = AtomicBoolean(false)

        // Pump interactive callback events from RunScreen (io.stdin) -> child.stdin
        val stdinJob = if (io != null) {
            launch(Dispatchers.IO) {
                try {
                    val buf = ByteArray(4096)
                    val input = io.stdin
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) {
                            child.stdin.write(buf, 0, n)
                            child.stdin.flush()
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { child.stdin.close() }
                }
            }
        } else null

        val stdoutJob = launch(Dispatchers.IO) {
            try {
                child.stdout.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (isBridgeJsonLine(line)) {
                            val seq = frameSeq.incrementAndGet()
                            val trimmed = line.trim()
                            io?.frame(trimmed, -1, -1, seq)
                            if (firstFrameLogged.compareAndSet(false, true)) {
                                val msg = "✓ GUI Bridge connected — live interactive window active in Run view"
                                ctx.buildLog.log(BuildLogEntry(msg, BuildLogLevel.INFO))
                                io?.stdout("$msg\n")
                                // In headless unit tests where no UI ProgramIo is attached, close stdin
                                // after the first frame so the process exits cleanly.
                                if (io == null) {
                                    runCatching { child.stdin.close() }
                                }
                            }
                        } else {
                            ctx.buildLog.log(BuildLogEntry(line, BuildLogLevel.INFO))
                            io?.stdout("$line\n")
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        val stderrJob = launch(Dispatchers.IO) {
            try {
                child.stderr.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        val level = if (line.contains("error:", ignoreCase = true)) BuildLogLevel.ERROR else BuildLogLevel.INFO
                        ctx.buildLog.log(BuildLogEntry(line, level))
                        io?.stdout("$line\n")
                    }
                }
            } catch (_: Exception) {
            }
        }

        try {
            val exitCode = child.awaitExit()
            withContext(NonCancellable) { child.terminate() }
            runCatching { io?.stdin?.close() }
            stdinJob?.cancel()
            withTimeoutOrNull(2_000) {
                stdoutJob.join()
                stderrJob.join()
            }
            io?.exited(exitCode)
            if (exitCode == 0 || firstFrameLogged.get()) {
                ctx.reportStep(stepName, "succeeded")
                TaskResult.Success
            } else {
                ctx.reportStep(stepName, "failed")
                TaskResult.Failed("$displayCommand exited with code $exitCode")
            }
        } catch (e: CancellationException) {
            io?.exited(0)
            ctx.reportStep(stepName, "failed")
            throw e
        } finally {
            withContext(NonCancellable) { child.terminate() }
            runCatching { io?.stdin?.close() }
            runCatching { child.stdin.close() }
            runCatching { child.stdout.close() }
            runCatching { child.stderr.close() }
            stdinJob?.cancel()
            stdoutJob.cancel()
            stderrJob.cancel()
        }
    }

    private suspend fun execSimple(
        host: RuntimeHost,
        command: List<String>,
        workingDir: Path,
        env: Map<String, String>,
        ctx: TaskContext,
        capturedLines: MutableList<String>,
    ): Int = coroutineScope {
        val child = host.start(command, workingDir, env)
        val stdoutJob = launch(Dispatchers.IO) {
            streamSimpleLines(child.stdout) { line ->
                ctx.buildLog.log(BuildLogEntry(line, BuildLogLevel.INFO))
                synchronized(capturedLines) { capturedLines += line }
            }
        }
        val stderrJob = launch(Dispatchers.IO) {
            streamSimpleLines(child.stderr) { line ->
                val level = if (line.contains("error:", ignoreCase = true)) BuildLogLevel.ERROR else BuildLogLevel.WARN
                ctx.buildLog.log(BuildLogEntry(line, level))
                synchronized(capturedLines) { capturedLines += line }
            }
        }
        try {
            runCatching { child.stdin.close() }
            val code = child.awaitExit()
            withContext(NonCancellable) { child.terminate() }
            withTimeoutOrNull(2_000) {
                stdoutJob.join()
                stderrJob.join()
            }
            code
        } finally {
            withContext(NonCancellable) { child.terminate() }
            runCatching { child.stdout.close() }
            runCatching { child.stderr.close() }
            runCatching { child.stdin.close() }
            stdoutJob.cancel()
            stderrJob.cancel()
        }
    }

    private fun streamSimpleLines(input: InputStream, onLine: (String) -> Unit) {
        try {
            input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { onLine(it) }
            }
        } catch (_: Exception) {
        }
    }

    private val NODEGUI_BRIDGE_SHIM_JS = """
        'use strict';
        const readline = require('readline');

        let nextCallbackId = 1;
        const callbacks = new Map();
        const activeWindows = [];
        let renderScheduled = false;
        let clipboardText = '';

        function registerCallback(fn) {
          const id = nextCallbackId++;
          callbacks.set(String(id), fn);
          return id;
        }

        function scheduleRender() {
          if (renderScheduled || activeWindows.length === 0) return;
          renderScheduled = true;
          queueMicrotask(() => {
            renderScheduled = false;
            emitActiveWindow();
          });
        }

        function hexToArgb(hex) {
          if (!hex || typeof hex !== 'string') return null;
          let clean = hex.trim().replace(/^#/, '');
          if (clean.toLowerCase() === 'white') clean = 'FFFFFF';
          if (clean.toLowerCase() === 'black') clean = '000000';
          if (clean.length === 3) {
            clean = clean.split('').map(c => c + c).join('');
          }
          if (clean.length === 6) {
            const rgb = parseInt(clean, 16);
            if (Number.isNaN(rgb)) return null;
            return ((0xFF000000 | rgb) >>> 0);
          }
          return null;
        }

        function parseCssRules(cssText) {
          const rules = [];
          if (!cssText || typeof cssText !== 'string') return rules;
          const cleaned = cssText.replace(/\/\*[\s\S]*?\*\//g, '');
          const regex = /([^{]+)\{([^}]*)\}/g;
          let match;
          while ((match = regex.exec(cleaned)) !== null) {
            const selectors = match[1].split(',').map(s => s.trim()).filter(Boolean);
            const props = {};
            match[2].split(';').forEach(decl => {
              const idx = decl.indexOf(':');
              if (idx > 0) {
                const k = decl.slice(0, idx).trim().toLowerCase();
                const v = decl.slice(idx + 1).trim().replace(/^['"]|['"]$/g, '');
                props[k] = v;
              }
            });
            rules.push({ selectors, props });
          }
          return rules;
        }

        function resolveStylesForWidget(widget, ancestorRules, parentObjectName) {
          const allRules = [...ancestorRules, ...parseCssRules(widget._styleSheet)];
          const matched = {};
          const objName = widget._objectName || '';
          const typeName = widget._widgetType || 'QWidget';
          for (const rule of allRules) {
            for (const sel of rule.selectors) {
              if (sel.includes(':pressed') || sel.includes(':hover')) continue;
              const matches =
                sel === '*' ||
                sel === typeName ||
                (objName && sel === '#' + objName) ||
                (parentObjectName && sel === '#' + parentObjectName + ' ' + typeName) ||
                (parentObjectName && sel === '#' + parentObjectName + ' *');
              if (matches) {
                Object.assign(matched, rule.props);
              }
            }
          }
          return { matched, allRules };
        }

        class NativeElement {}
        class FlexNode {}

        class QKeyEvent {
          constructor(nativeEvt) { this._text = (nativeEvt && nativeEvt.text) || ''; }
          text() { return this._text; }
        }

        class QWidget {
          constructor() {
            this._widgetType = 'QWidget';
            this._objectName = '';
            this._styleSheet = '';
            this._layout = null;
            this._listeners = new Map();
            this._flexNode = new FlexNode();
            this._windowTitle = '';
            this._centralWidget = null;
            this._children = [];
          }
          setObjectName(name) { this._objectName = String(name || ''); scheduleRender(); }
          objectName() { return this._objectName; }
          setStyleSheet(css) { this._styleSheet = String(css || ''); scheduleRender(); }
          styleSheet() { return this._styleSheet; }
          setLayout(layout) {
            this._layout = layout;
            if (layout) layout._owner = this;
            scheduleRender();
          }
          layout() { return this._layout; }
          getFlexNode() { return this._flexNode; }
          setFixedSize(w, h) { this._width = w; this._height = h; }
          resize(w, h) { this._width = w; this._height = h; }
          setWindowTitle(title) { this._windowTitle = String(title || ''); scheduleRender(); }
          windowTitle() { return this._windowTitle; }
          addEventListener(evt, fn) {
            if (!this._listeners.has(evt)) this._listeners.set(evt, []);
            this._listeners.get(evt).push(fn);
          }
          _emit(evt, arg) {
            const list = this._listeners.get(evt) || [];
            for (const fn of list) fn(arg);
            scheduleRender();
          }
          show() {
            if (!activeWindows.includes(this)) activeWindows.push(this);
            process.stderr.write('[NodeGUI Bridge] Window shown — streaming live ViewNode tree\n');
            emitActiveWindow();
          }
          close() {}
          toViewNode(ancestorRules = [], parentObjName = '', pathPrefix = 'root') {
            const { matched, allRules } = resolveStylesForWidget(this, ancestorRules, parentObjName);
            const id = pathPrefix + '/' + (this._objectName || this._widgetType);
            const childWidgets = this._layout ? this._layout._widgets : this._children;
            const isRow = matched['flex-direction'] === 'row' || (this._layout && this._layout._isRow);
            const children = childWidgets.map((c, idx) =>
              c.toViewNode(allRules, this._objectName || parentObjName, id + '/' + idx)
            );
            const equalWidth = isRow && childWidgets.length >= 2 && childWidgets.every(w => w._widgetType === 'QPushButton' || w._widgetType === 'QLabel');
            const props = {
              spacing: 6,
              alignment: isRow ? 'center' : 'leading',
              equalWidth,
            };
            const modifiers = [
              { kind: 'frame', args: { fillWidth: true } },
            ];
            if (matched['background'] && matched['background'].startsWith('#')) {
              const argb = hexToArgb(matched['background']);
              if (argb !== null) {
                modifiers.push({ kind: 'background', args: { color: argb } });
                modifiers.push({ kind: 'cornerRadius', args: { radius: 8 } });
              }
            }
            if (matched['padding']) {
              const p = Math.min(parseFloat(matched['padding']) || 6, 10);
              modifiers.push({ kind: 'padding', args: { top: p, bottom: p, leading: p, trailing: p } });
            }
            return {
              type: isRow ? 'HStack' : 'VStack',
              id,
              props,
              modifiers,
              children,
            };
          }
        }

        class QMainWindow extends QWidget {
          constructor() {
            super();
            this._widgetType = 'QMainWindow';
          }
          setCentralWidget(widget) {
            this._centralWidget = widget;
            scheduleRender();
          }
          centralWidget() { return this._centralWidget; }
          toViewNode() {
            const rootChild = this._centralWidget
              ? this._centralWidget.toViewNode([], '', 'root/central')
              : { type: 'VStack', id: 'root/empty', props: {}, modifiers: [], children: [] };
            const { matched } = resolveStylesForWidget(this._centralWidget || this, [], '');
            const cssBg = matched['background'] || matched['background-color'] || '';
            const windowBg = cssBg.startsWith('#') ? cssBg : '#F2F2F7';
            let pkgName = '';
            try {
              const fs = require('fs');
              const path = require('path');
              const pkgPath = path.join(process.cwd(), 'package.json');
              if (fs.existsSync(pkgPath)) {
                pkgName = JSON.parse(fs.readFileSync(pkgPath, 'utf8')).name || '';
              }
            } catch (_) {}
            const title = this._windowTitle || pkgName || 'NodeGUI Window';
            return {
              type: 'ScrollView',
              id: 'root',
              props: {
                windowTitle: title,
                windowBackground: windowBg,
              },
              modifiers: [
                { kind: 'frame', args: { fillWidth: true } },
              ],
              children: [rootChild],
            };
          }
        }

        class FlexLayout {
          constructor() {
            this._widgets = [];
            this._isRow = false;
          }
          setFlexNode() {}
          addWidget(widget) {
            if (widget && !this._widgets.includes(widget)) {
              this._widgets.push(widget);
              scheduleRender();
            }
          }
        }

        class QBoxLayout extends FlexLayout {
          constructor(dir) {
            super();
            this._isRow = dir === 0 || dir === 'LeftToRight' || dir === 'row';
          }
          setDirection(dir) {
            this._isRow = dir === 0 || dir === 'LeftToRight' || dir === 'row';
          }
        }

        class QLabel extends QWidget {
          constructor(parent) {
            super();
            this._widgetType = 'QLabel';
            this._text = '';
          }
          setText(t) {
            this._text = String(t ?? '');
            scheduleRender();
          }
          text() { return this._text; }
          toViewNode(ancestorRules = [], parentObjName = '', pathPrefix = 'root') {
            const { matched } = resolveStylesForWidget(this, ancestorRules, parentObjName);
            const isRight = (matched['qproperty-alignment'] || '').includes('AlignRight');
            const style = {};
            if (matched['font-size']) {
              const px = parseFloat(matched['font-size']);
              if (!Number.isNaN(px)) style.fontSize = Math.min(px, 34);
            }
            if (matched['color']) {
              const argb = hexToArgb(matched['color']);
              if (argb !== null) style.color = argb;
            }
            if (isRight) {
              style.textAlign = 'right';
              style.fontFamily = 'monospace';
              style.fontWeight = 'semibold';
            }
            return {
              type: 'Composable',
              id: pathPrefix + '/' + (this._objectName || 'label'),
              props: {
                name: 'CupertinoText',
                text: this._text,
                style,
              },
              modifiers: isRight ? [{ kind: 'frame', args: { fillWidth: true } }] : [],
              children: [],
            };
          }
        }

        class QPushButton extends QWidget {
          constructor() {
            super();
            this._widgetType = 'QPushButton';
            this._text = 'Button';
          }
          setText(t) {
            this._text = String(t ?? '');
            scheduleRender();
          }
          text() { return this._text; }
          setFlat() {}
          toViewNode(ancestorRules = [], parentObjName = '', pathPrefix = 'root') {
            const { matched } = resolveStylesForWidget(this, ancestorRules, parentObjName);
            const onTapId = registerCallback(() => {
              this._emit('clicked');
            });
            const id = pathPrefix + '/' + (this._objectName || this._text);
            const props = {
              name: 'CupertinoButton',
              onClick: onTapId,
            };
            if (matched['background'] && matched['background'].startsWith('#')) {
              const bgArgb = hexToArgb(matched['background']);
              if (bgArgb !== null) props.containerColor = bgArgb;
            }
            const fgArgb = matched['color'] ? hexToArgb(matched['color']) : 0xFFFFFFFF;
            if (fgArgb !== null) props.contentColor = fgArgb;
            const modifiers = [
              { kind: 'frame', args: { fillWidth: true } },
            ];
            if (matched['height']) {
              const h = parseFloat(matched['height']);
              if (!Number.isNaN(h)) {
                modifiers.push({ kind: 'frame', args: { height: Math.min(h, 46) } });
              }
            }
            return {
              type: 'Composable',
              id,
              props,
              modifiers,
              children: [
                {
                  type: 'CupertinoSlot',
                  id: id + '/content',
                  props: { name: 'content' },
                  modifiers: [],
                  children: [
                    {
                      type: 'Composable',
                      id: id + '/label',
                      props: {
                        name: 'CupertinoText',
                        text: this._text,
                        style: {
                          color: fgArgb || 0xFFFFFFFF,
                          fontSize: 16,
                          fontWeight: 'semibold',
                        },
                      },
                      modifiers: [],
                      children: [],
                    },
                  ],
                },
              ],
            };
          }
        }

        class QCheckBox extends QWidget {
          constructor() {
            super();
            this._widgetType = 'QCheckBox';
            this._text = '';
            this._checked = false;
          }
          setText(t) { this._text = String(t ?? ''); scheduleRender(); }
          text() { return this._text; }
          setChecked(c) { this._checked = Boolean(c); scheduleRender(); }
          isChecked() { return this._checked; }
          toViewNode(ancestorRules = [], parentObjName = '', pathPrefix = 'root') {
            const onChangeId = registerCallback((val) => {
              this._checked = Boolean(val);
              this._emit('toggled', this._checked);
              this._emit('stateChanged', this._checked ? 2 : 0);
            });
            const id = pathPrefix + '/' + (this._objectName || 'checkbox');
            return {
              type: 'HStack',
              id,
              props: { spacing: 10, alignment: 'center' },
              modifiers: [{ kind: 'frame', args: { fillWidth: true } }],
              children: [
                {
                  type: 'Composable',
                  id: id + '/box',
                  props: {
                    name: 'CupertinoCheckBox',
                    checked: this._checked,
                    onCheckedChange: onChangeId,
                  },
                  modifiers: [],
                  children: [],
                },
                {
                  type: 'Composable',
                  id: id + '/label',
                  props: {
                    name: 'CupertinoText',
                    text: this._text,
                  },
                  modifiers: [],
                  children: [],
                },
                {
                  type: 'Spacer',
                  id: id + '/spacer',
                  props: {},
                  modifiers: [],
                  children: [],
                },
              ],
            };
          }
        }

        class QLineEdit extends QWidget {
          constructor() {
            super();
            this._widgetType = 'QLineEdit';
            this._text = '';
            this._placeholder = '';
            this._readOnly = false;
          }
          setText(t) { this._text = String(t ?? ''); scheduleRender(); }
          text() { return this._text; }
          setPlaceholderText(t) { this._placeholder = String(t ?? ''); scheduleRender(); }
          setReadOnly(r) { this._readOnly = Boolean(r); scheduleRender(); }
          toViewNode(ancestorRules = [], parentObjName = '', pathPrefix = 'root') {
            const onChangeId = registerCallback((val) => {
              this._text = String(val ?? '');
              this._emit('textChanged', this._text);
            });
            const id = pathPrefix + '/' + (this._objectName || 'lineedit');
            return {
              type: 'Composable',
              id,
              props: {
                name: 'CupertinoBorderedTextField',
                value: this._text,
                readOnly: this._readOnly,
                singleLine: true,
                onValueChange: onChangeId,
              },
              modifiers: [{ kind: 'frame', args: { fillWidth: true } }],
              children: [
                {
                  type: 'CupertinoSlot',
                  id: id + '/placeholder',
                  props: { name: 'placeholder' },
                  modifiers: [],
                  children: [
                    {
                      type: 'Composable',
                      id: id + '/placeholder/text',
                      props: { name: 'CupertinoText', text: this._placeholder || '' },
                      modifiers: [],
                      children: [],
                    },
                  ],
                },
              ],
            };
          }
        }

        class QPlainTextEdit extends QWidget {
          constructor() {
            super();
            this._widgetType = 'QPlainTextEdit';
            this._text = '';
            this._placeholder = '';
            this._readOnly = false;
          }
          setPlainText(t) { this._text = String(t ?? ''); scheduleRender(); }
          toPlainText() { return this._text; }
          setPlaceholderText(t) { this._placeholder = String(t ?? ''); scheduleRender(); }
          setReadOnly(r) { this._readOnly = Boolean(r); scheduleRender(); }
          setWordWrapMode() {}
          toViewNode(ancestorRules = [], parentObjName = '', pathPrefix = 'root') {
            const onChangeId = registerCallback((val) => {
              this._text = String(val ?? '');
              this._emit('textChanged');
            });
            const id = pathPrefix + '/' + (this._objectName || 'textarea');
            return {
              type: 'Composable',
              id,
              props: {
                name: 'CupertinoBorderedTextField',
                value: this._text,
                readOnly: this._readOnly,
                singleLine: false,
                minLines: 2,
                maxLines: 3,
                onValueChange: onChangeId,
              },
              modifiers: [{ kind: 'frame', args: { fillWidth: true } }],
              children: [
                {
                  type: 'CupertinoSlot',
                  id: id + '/placeholder',
                  props: { name: 'placeholder' },
                  modifiers: [],
                  children: [
                    {
                      type: 'Composable',
                      id: id + '/placeholder/text',
                      props: {
                        name: 'CupertinoText',
                        text: this._placeholder || '',
                      },
                      modifiers: [],
                      children: [],
                    },
                  ],
                },
              ],
            };
          }
        }

        class QSlider extends QWidget {
          constructor() {
            super();
            this._widgetType = 'QSlider';
            this._value = 50;
            this._min = 0;
            this._max = 100;
          }
          setValue(v) { this._value = Number(v) || 0; scheduleRender(); }
          value() { return this._value; }
          setMinimum(v) { this._min = Number(v) || 0; }
          setMaximum(v) { this._max = Number(v) || 100; }
          setOrientation() {}
          toViewNode(ancestorRules = [], parentObjName = '', pathPrefix = 'root') {
            const onChangeId = registerCallback((val) => {
              this._value = Math.round(Number(val) || 0);
              this._emit('valueChanged', this._value);
            });
            return {
              type: 'Slider',
              id: pathPrefix + '/' + (this._objectName || 'slider'),
              props: {
                value: this._value,
                min: this._min,
                max: this._max,
                onChange: onChangeId,
              },
              modifiers: [{ kind: 'frame', args: { fillWidth: true } }],
              children: [],
            };
          }
        }

        class QProgressBar extends QWidget {
          constructor() {
            super();
            this._widgetType = 'QProgressBar';
            this._value = 0;
            this._max = 100;
          }
          setValue(v) { this._value = Number(v) || 0; scheduleRender(); }
          setMaximum(m) { this._max = Number(m) || 100; }
          toViewNode(ancestorRules = [], parentObjName = '', pathPrefix = 'root') {
            return {
              type: 'ProgressView',
              id: pathPrefix + '/' + (this._objectName || 'progress'),
              props: { value: this._value / Math.max(this._max, 1) },
              modifiers: [{ kind: 'frame', args: { fillWidth: true } }],
              children: [],
            };
          }
        }

        const QApplication = {
          instance: () => QApplication,
          clipboard: () => ({
            setText: (t) => {
              clipboardText = String(t ?? '');
              process.stderr.write('[NodeGUI Bridge] Copied to clipboard: ' + clipboardText + '\n');
            },
            text: () => clipboardText,
          }),
          exec: () => {},
        };

        const QPushButtonEvents = { clicked: 'clicked', pressed: 'pressed', released: 'released' };
        const QCheckBoxEvents = { toggled: 'toggled', stateChanged: 'stateChanged' };
        const QLineEditEvents = { textChanged: 'textChanged' };
        const BaseWidgetEvents = { KeyRelease: 'KeyRelease', KeyPress: 'KeyPress' };
        const Direction = { LeftToRight: 0, RightToLeft: 1, TopToBottom: 2, BottomToTop: 3 };
        const Orientation = { Horizontal: 1, Vertical: 2 };

        function emitActiveWindow() {
          const win = activeWindows[activeWindows.length - 1];
          if (!win) return;
          callbacks.clear();
          nextCallbackId = 1;
          const tree = win.toViewNode();
          process.stdout.write(JSON.stringify(tree) + '\n');
        }

        // Listen for callback events on stdin from RunScreen
        const rl = readline.createInterface({ input: process.stdin, terminal: false });
        rl.on('line', (line) => {
          const trimmed = line.trim();
          if (!trimmed.startsWith('{')) return;
          try {
            const evt = JSON.parse(trimmed);
            const fn = callbacks.get(String(evt.id));
            if (fn) {
              fn(evt.value);
              scheduleRender();
            }
          } catch (_) {}
        });
        rl.on('close', () => {
          process.exit(0);
        });

        module.exports = {
          QMainWindow,
          QWidget,
          FlexLayout,
          QBoxLayout,
          QLabel,
          QPushButton,
          QPushButtonEvents,
          QCheckBox,
          QCheckBoxEvents,
          QLineEdit,
          QLineEditEvents,
          QPlainTextEdit,
          QSlider,
          QProgressBar,
          QApplication,
          BaseWidgetEvents,
          Direction,
          Orientation,
          QKeyEvent,
          NativeElement,
        };
    """.trimIndent()
}
