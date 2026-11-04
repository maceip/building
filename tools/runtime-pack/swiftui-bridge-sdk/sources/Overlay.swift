//
//  Overlay.swift
//  SwiftUICore
//
//  `.overlay { … }` layers content over a view, sized to the base. Emitted as an
//  Overlay node whose first child is the base and second is the overlay; the
//  interpreter stacks the overlay at the base's size with the given alignment.
//

public struct _OverlayView<Base: View, Overlay: View>: View {
    internal let base: Base
    internal let overlay: Overlay
    internal let alignment: Alignment
    internal var isBackground: Bool = false
    public typealias Body = Never
}

extension _OverlayView: PrimitiveView {
    public func _render(in context: ResolveContext) -> RenderNode {
        var props: [String: PropValue] = [
            "horizontal": .string(alignment.horizontal.rawValue),
            "vertical": .string(alignment.vertical.rawValue),
        ]
        if isBackground {
            props["isBackground"] = .bool(true)
        }
        return RenderNode(
            type: "Overlay",
            id: context.path,
            props: props,
            children: [
                Evaluator.resolve(base, context.descending("base")),
                Evaluator.resolve(overlay, context.descending("overlay")),
            ]
        )
    }
}

public extension View {
    func overlay<V: View>(
        alignment: Alignment = .center,
        @ViewBuilder content: () -> V
    ) -> _OverlayView<Self, V> {
        _OverlayView(base: self, overlay: content(), alignment: alignment)
    }

    func overlay<V: View>(
        _ overlay: V,
        alignment: Alignment = .center
    ) -> _OverlayView<Self, V> {
        _OverlayView(base: self, overlay: overlay, alignment: alignment)
    }

    func background<V: View>(
        _ background: V,
        alignment: Alignment = .center
    ) -> _OverlayView<Self, V> {
        _OverlayView(base: self, overlay: background, alignment: alignment, isBackground: true)
    }

    func background<V: View>(
        alignment: Alignment = .center,
        @ViewBuilder content: () -> V
    ) -> _OverlayView<Self, V> {
        _OverlayView(base: self, overlay: content(), alignment: alignment, isBackground: true)
    }
}
