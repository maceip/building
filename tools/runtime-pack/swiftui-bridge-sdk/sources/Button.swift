//
//  Button.swift
//  SwiftUICore
//

/// A control that performs an action when triggered.
public struct Button<Label: View>: View {

    internal let action: () -> Void
    internal let label: Label

    public init(action: @escaping () -> Void, @ViewBuilder label: () -> Label) {
        self.action = action
        self.label = label()
    }

    public typealias Body = Never
}

public extension Button where Label == Text {
    init<S: StringProtocol>(_ title: S, action: @escaping () -> Void) {
        self.init(action: action) { Text(title) }
    }
}

extension Button: PrimitiveView {
    public func _render(in context: ResolveContext) -> RenderNode {
        let callbackID = context.registerCallback(.void(action))
        return RenderNode(
            type: "Button",
            id: context.path,
            props: ["onTap": .int(Int(callbackID))],
            children: Evaluator.resolveChildren(label, context.descending("label"))
        )
    }
}

public protocol _AnyButton {
    var _action: () -> Void { get }
    var _labelView: any View { get }
}

extension Button: _AnyButton {
    public var _action: () -> Void { action }
    public var _labelView: any View { label }
}

public struct ButtonStyleConfiguration {
    public struct Label: View, PrimitiveView {
        internal let nodes: [RenderNode]
        public typealias Body = Never
        public func _render(in context: ResolveContext) -> RenderNode {
            if nodes.count == 1 { return nodes[0] }
            return RenderNode(type: "HStack", id: context.path, children: nodes)
        }
    }
    public let label: Label
    public let isPressed: Bool
}

public protocol ButtonStyle {
    associatedtype Body: View
    typealias Configuration = ButtonStyleConfiguration
    @ViewBuilder func makeBody(configuration: Self.Configuration) -> Self.Body
}

public struct _CustomButtonStyleView<Content: View, Style: ButtonStyle>: View, PrimitiveView {
    let content: Content
    let style: Style

    public typealias Body = Never

    public func _render(in context: ResolveContext) -> RenderNode {
        if let btn = content as? _AnyButton {
            let callbackID = context.registerCallback(.void(btn._action))
            let labelNodes = Evaluator.resolveChildren(btn._labelView, context.descending("label"))
            let config = ButtonStyleConfiguration(
                label: ButtonStyleConfiguration.Label(nodes: labelNodes),
                isPressed: false
            )
            let styledBody = style.makeBody(configuration: config)
            let styledNodes = Evaluator.resolveChildren(styledBody, context.descending("styled"))
            return RenderNode(
                type: "Button",
                id: context.path,
                props: ["onTap": .int(Int(callbackID))],
                modifiers: [ModifierNode(kind: "buttonStyle", args: ["style": .string("plain")])],
                children: styledNodes
            )
        }
        let children = Evaluator.resolveChildren(content, context.descending("content"))
        return children.first ?? RenderNode(type: "EmptyView", id: context.path)
    }
}

public extension View {
    func buttonStyle<S: ButtonStyle>(_ style: S) -> _CustomButtonStyleView<Self, S> {
        _CustomButtonStyleView(content: self, style: style)
    }
}
