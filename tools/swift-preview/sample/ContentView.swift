import SwiftUI

struct ContentView: View {
    @State private var count = 0
    @State private var name = "World"
    @State private var enabled = true

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            Text("Hello, \(name)").font(.largeTitle).bold()
            Text("A Swift app. On Android.").foregroundColor(.gray)
            TextField("Your name", text: $name)
            Toggle("Count by two", isOn: $enabled)
            Divider()
            Text("\(count)").font(.system(size: 72)).bold()
            Button("Add \(enabled ? 2 : 1)") { count += enabled ? 2 : 1 }
            Button("Reset") { count = 0 }
            Text("State and actions run in compiled Swift.").font(.footnote).foregroundColor(.gray)
        }
        .padding(24)
    }
}
