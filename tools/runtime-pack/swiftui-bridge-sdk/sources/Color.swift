//
//  Color.swift
//  SwiftUICore
//

import Foundation

public typealias CGFloat = Double

public struct CGSize: Equatable, Sendable {
    public var width: Double
    public var height: Double
    public init(width: Double = 0, height: Double = 0) {
        self.width = width
        self.height = height
    }
    public static let zero = CGSize(width: 0, height: 0)
}

public struct CGRect: Equatable, Sendable {
    public var x: Double
    public var y: Double
    public var width: Double
    public var height: Double
    public init(x: Double = 0, y: Double = 0, width: Double = 252, height: Double = 480) {
        self.x = x
        self.y = y
        self.width = width
        self.height = height
    }
}

public struct UIScreen: Sendable {
    public static let main = UIScreen()
    public var bounds: CGRect { CGRect(x: 0, y: 0, width: 252, height: 480) }
    public init() {}
}

public struct UIApplication: Sendable {
    public static let shared = UIApplication()
    public init() {}
    public func open(_ url: URL, options: [String: Any] = [:], completionHandler: ((Bool) -> Void)? = nil) {}
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
        self.init(red: white, green: white, blue: white, alpha: alpha)
    }

    public static let lightGray = UIColor(red: 0.65, green: 0.65, blue: 0.67)
    public static let darkGray = UIColor(red: 0.33, green: 0.33, blue: 0.35)
    public static let black = UIColor(red: 0, green: 0, blue: 0)
    public static let white = UIColor(red: 1, green: 1, blue: 1)
    public static let clear = UIColor(red: 0, green: 0, blue: 0, alpha: 0)
    public static let systemGray = UIColor(red: 0.56, green: 0.56, blue: 0.58)
    public static let systemGray2 = UIColor(red: 0.68, green: 0.68, blue: 0.70)
    public static let systemGray3 = UIColor(red: 0.78, green: 0.78, blue: 0.80)
    public static let systemGray4 = UIColor(red: 0.82, green: 0.82, blue: 0.84)
    public static let systemGray5 = UIColor(red: 0.90, green: 0.90, blue: 0.92)
    public static let systemGray6 = UIColor(red: 0.95, green: 0.95, blue: 0.97)
    public static let systemBackground = UIColor(red: 1.0, green: 1.0, blue: 1.0)
    public static let secondarySystemBackground = UIColor(red: 0.95, green: 0.95, blue: 0.97)
}

/// An sRGB color with an opacity.
public struct Color: Equatable, Sendable {

    public let red: Double
    public let green: Double
    public let blue: Double
    public let opacity: Double
    private var darkARGB: Int64? = nil

    public init(red: Double, green: Double, blue: Double, opacity: Double = 1) {
        self.red = red; self.green = green; self.blue = blue; self.opacity = opacity
    }

    /// A platform-resolved light/dark pair. Both variants stay in the render
    /// tree so appearance changes never require a protocol or service request.
    public init(light: Color, dark: Color) {
        self.init(red: light.red, green: light.green, blue: light.blue, opacity: light.opacity)
        self.darkARGB = dark.argb
    }

    public static let black = Color(red: 0, green: 0, blue: 0)
    public static let white = Color(red: 1, green: 1, blue: 1)
    public static let red = Color(red: 1, green: 0, blue: 0)
    public static let green = Color(red: 0, green: 0.8, blue: 0)
    public static let blue = Color(red: 0, green: 0.48, blue: 1)
    public static let orange = Color(red: 1, green: 0.58, blue: 0)
    public static let yellow = Color(red: 1, green: 0.8, blue: 0)
    public static let purple = Color(red: 0.69, green: 0.32, blue: 0.87)
    public static let pink = Color(red: 1, green: 0.18, blue: 0.33)
    public static let gray = Color(red: 0.56, green: 0.56, blue: 0.58)
    public static let clear = Color(red: 0, green: 0, blue: 0, opacity: 0)
    public static let primary = Color(light: .black, dark: .white)
    public static let secondary = Color(red: 0.22, green: 0.22, blue: 0.24)
    public static let lightGray = Color(red: 0.65, green: 0.65, blue: 0.67)
    public static let darkGray = Color(red: 0.33, green: 0.33, blue: 0.35)
    public static let link = Color.blue

    public init(white: Double, opacity: Double = 1) {
        self.init(red: white, green: white, blue: white, opacity: opacity)
    }

    public func opacity(_ value: Double) -> Color {
        Color(red: red, green: green, blue: blue, opacity: opacity * value)
    }

    public init(_ uiColor: UIColor) {
        self.init(red: uiColor.red, green: uiColor.green, blue: uiColor.blue, opacity: uiColor.alpha)
    }

    public init(uiColor: UIColor) {
        self.init(uiColor)
    }

    public init(_ name: String) {
        switch name.lowercased() {
        case "calcbg":
            self.init(red: 0.68, green: 0.74, blue: 0.64)
        default:
            self.init(red: 0.56, green: 0.56, blue: 0.58)
        }
    }

    /// 0xAARRGGBB, the form Android's `setColor`/Compose `Color(argb)` expect.
    public var argb: Int64 {
        func channel(_ value: Double) -> Int64 { Int64((value * 255).rounded()) & 0xFF }
        return (channel(opacity) << 24) | (channel(red) << 16) | (channel(green) << 8) | channel(blue)
    }

    internal var propValue: PropValue {
        if let darkARGB { return .array([.int(Int(argb)), .int(Int(darkARGB))]) }
        return .int(Int(argb))
    }
}
