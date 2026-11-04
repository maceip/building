import Foundation

public struct CupertinoTextSpan: Codable, Equatable, Sendable {
    public let start: Int
    public let end: Int
    public let fontWeight: Double?
    public let fontSize: Double?
    public let fontFamily: String?
    public let color: [Int]?
    public init(start: Int, end: Int, fontWeight: Double? = nil, fontSize: Double? = nil,
                fontFamily: String? = nil, color: Color? = nil) {
        precondition(start >= 0 && end >= start)
        precondition(fontWeight.map { $0.isFinite && (1...1000).contains($0) } ?? true)
        precondition(fontSize.map { $0.isFinite && $0 > 0 } ?? true)
        self.start = start; self.end = end; self.fontWeight = fontWeight; self.fontSize = fontSize; self.fontFamily = fontFamily
        switch color?.propValue {
        case .int(let argb): self.color = [argb, argb]
        case .array(let colors): self.color = colors.compactMap { if case .int(let argb) = $0 { argb } else { nil } }
        default: self.color = nil
        }
    }
}
public struct CupertinoStringAnnotation: Codable, Equatable, Sendable {
    public let start: Int
    public let end: Int
    public let tag: String
    public let value: String
    public init(start: Int, end: Int, tag: String, value: String) {
        precondition(start >= 0 && end >= start && !tag.isEmpty)
        self.start = start; self.end = end; self.tag = tag; self.value = value
    }
}
public struct CupertinoAnnotatedText: Codable, Equatable, Sendable {
    public let text: String
    public let spans: [CupertinoTextSpan]
    public let annotations: [CupertinoStringAnnotation]
    public init(_ text: String, spans: [CupertinoTextSpan] = [], annotations: [CupertinoStringAnnotation] = []) {
        precondition(spans.allSatisfy { $0.end <= text.utf16.count } && annotations.allSatisfy { $0.end <= text.utf16.count })
        self.text = text; self.spans = spans; self.annotations = annotations
    }
}
public extension CupertinoText {
    init(_ value: CupertinoAnnotatedText, maxLines: Int? = nil) {
        var props: [String: PropValue] = ["annotatedJson": .string(cupertinoJSON(value))]
        if let maxLines { precondition(maxLines > 0); props["maxLines"] = .int(maxLines) }
        component = CupertinoComponent("CupertinoText", props: props)
    }
}

/// Resources are resolved locally by the native/desktop host; a missing resource
/// is an explicit diagnostic, never a substitution with a different symbol.
public struct CupertinoIconResource: Sendable {
    public enum Kind: String, Sendable { case painter = "painterResource", bitmap = "imageBitmapResource" }
    public let name: String
    public let kind: Kind
    public let width: Int?
    public let height: Int?
    public init(_ name: String, kind: Kind = .painter, width: Int? = nil, height: Int? = nil) {
        precondition(!name.isEmpty && !name.contains("..") && !name.hasPrefix("/"))
        precondition(width.map { (1...4096).contains($0) } ?? true)
        precondition(height.map { (1...4096).contains($0) } ?? true)
        self.name = name; self.kind = kind; self.width = width; self.height = height
    }
    var props: [String: PropValue] {
        var p: [String: PropValue] = [kind.rawValue: .string(name)]
        if let width { p["bitmapWidth"] = .int(width) }
        if let height { p["bitmapHeight"] = .int(height) }
        return p
    }
}
public extension CupertinoIcon {
    init(resource: CupertinoIconResource, contentDescription: String? = nil, tint: Color? = nil) {
        var props = resource.props
        if let contentDescription { props["contentDescription"] = .string(contentDescription) }
        if let tint { props["tint"] = .color(tint) }
        component = CupertinoComponent("CupertinoIcon", props: props)
    }
}
public extension CupertinoLinkIcon {
    init(resource: CupertinoIconResource, contentDescription: String? = nil, tint: Color? = nil) {
        var props = resource.props
        if let contentDescription { props["contentDescription"] = .string(contentDescription) }
        if let tint { props["tint"] = .color(tint) }
        component = CupertinoComponent("CupertinoLinkIcon", props: props)
    }
}

public struct CupertinoAdaptation {
    public let cupertino: [String: PropValue]
    public let material: [String: PropValue]
    public init(cupertino: [String: PropValue] = [:], material: [String: PropValue] = [:]) {
        self.cupertino = cupertino; self.material = material
    }
    var json: String {
        func object(_ props: [String: PropValue]) -> [String: Any] { props.mapValues(value) }
        func value(_ p: PropValue) -> Any {
            switch p {
            case .string(let v): return v
            case .double(let v): precondition(v.isFinite); return v
            case .bool(let v): return v
            case .int(let v): return v
            case .array(let v): return v.map(value)
            }
        }
        let raw = ["cupertino": object(cupertino), "material": object(material)]
        guard let data = try? JSONSerialization.data(withJSONObject: raw, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else { preconditionFailure("Invalid adaptation options") }
        return text
    }
}
public extension CupertinoComponent {
    func adaptation(_ value: CupertinoAdaptation) -> Self {
        var copy = self
        copy.props["adaptationJson"] = .string(value.json)
        return copy
    }
}
