import SwiftUI

// Equal eager trees: only ownership of the changing state differs.
struct LeafRow: View {
    let index: Int
    @State private var value = 0
    var body: some View {
        Button("row \(index): \(value)") { value += 1 }
    }
}

struct LeafWorkload: View {
    let rows: Int
    var body: some View {
        VStack { ForEach(0..<rows, id: \.self) { LeafRow(index: $0) } }
    }
}

struct RootWorkload: View {
    let rows: Int
    @State private var value = 0
    var body: some View {
        VStack {
            ForEach(0..<rows, id: \.self) { index in
                Button("row \(index): \(index == rows / 2 ? value : 0)") { value += 1 }
            }
        }
    }
}

func benchmarkRoot(_ rows: Int, _ leaf: Bool) -> any View {
    if leaf { return LeafWorkload(rows: rows) }
    return RootWorkload(rows: rows)
}
