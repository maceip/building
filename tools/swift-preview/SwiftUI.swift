// A source compatibility module: app sources keep `import SwiftUI`.
// This exports the open-source evaluator, not Apple's framework.
@_exported import SwiftUICore

public protocol PreviewProvider {
    associatedtype Previews: View
    @ViewBuilder static var previews: Previews { get }
}
