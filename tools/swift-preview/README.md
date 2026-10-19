# Swift → Cupertino preview on Android

Compile Swift view sources against the open-source `SwiftUICore` shim and run
native ARM64 Swift **inside the Android preview app**. The default path uses
AndroidSwiftUI's existing typed JNI materializer, generated `BridgeExport`
callbacks, `ViewHost.evaluateUpdate()` and Kotlin `TreeStore.patch()`.
State and application actions stay in Swift; Compose Cupertino renders the UI.
No live source translation, model inference or TPU is involved.

This is a standalone preview APK and an embeddable `SwiftPreview(modifier)`
composable. Building's editor Run action is not integrated yet. Compilation
happens on the Mac; execution and UI happen on Android. A newly built native
library must be packaged with the host; arbitrary binary hot-loading is not
implemented.

## Run

Requires macOS, Swift **6.3.2**, its Android SDK, Android SDK 35, JDK 21 and an
ARM64 Android 9+ device/emulator. The first build fetches pinned sources and
builds the SwiftJava/JExtract tools. `SWIFT`, `ANDROID_HOME` and `ANDROID_SERIAL`
can override defaults. The compiler's matching toolchain and JDK 21 are selected
explicitly for nested generators. Nothing is downloaded at app runtime.
The current SwiftPM cross-build can rebuild host generator dependencies on
subsequent invocations; build-cache optimization remains separate work.

```sh
python3 tools/swift-preview/preview.py --install
python3 tools/swift-preview/verify_device.py --case sample

# Compile the pinned third-party counter's unchanged ContentView.swift:
python3 tools/swift-preview/preview.py --github-counter --install
python3 tools/swift-preview/verify_device.py --case github

python3 tools/swift-preview/preview.py --source /path/ContentView.swift \
  --source /path/Model.swift --root ContentView --install
```

The GitHub example is [Takenori Kabeya's SwiftUI counter](https://github.com/takenori-kabeya/SwiftUIStateManagementBook-Counter/tree/05bb80af3d0700eb926bf4031da50729ab1d78f3),
specifically `SimpleCounterSwiftUI/SimpleCounterSwiftUI/ContentView.swift`.
The Android host supplies the entry point; the view source and actions remain
unchanged. This does not build its Xcode project or separate TCA example.

Outputs are under `.build/android/preview-app/build/outputs/apk/debug/` and
`.build/android/preview/build/outputs/aar/`. An embedding app needs the native
libraries and Gradle dependencies as well as the AAR. `.build/build-receipt.json`
records compiler, source and binary hashes. `.build/evidence/` holds device
screenshots, interaction receipts and benchmarks. Generated products stay ignored.

## Reused bridge and small host adaptations

The pinned [AndroidSwiftUI fork](https://github.com/maceip/Swiftkey/tree/9bbcc0bc008d43fa3e204fe5856cd41cc03f4d7c/AndroidSwiftUI)
includes [Compose Cupertino](https://github.com/slanos/compose-cupertino).
Its `Materializer`, typed `ViewNode` constructor, `JextractCallbackSink` and
`AndroidBridgeHost` are reused. The existing materializer transports scalar and
homogeneous-array values in typed JNI slots; nested/mixed property arrays retain
its JSON fallback. There is no whole-tree JSON serialization in the preview path.

The host adds a small SwiftJava bootstrap and `BridgeRuntime.stop()` so disposal
clears the active root and invalidates queued renders. A per-node styling hook
maps ordinary Text, Button, Toggle and nonsecure TextField to Cupertino controls.
It does not recursively copy the tree. Stacks and other supported nodes use the
existing renderer. Two counter symbols have explicit mappings; minus uses a
glyph. Unsupported symbols retain the renderer's diagnostic.

Subtree savings depend on where state lives. A row with its own `@State` can
produce a small patch. State owned by the root may re-evaluate/materialize the
whole root, even though the operation is called a patch. The existing tree
splice may also search siblings; this is not a promise of constant-time updates.

Only one live Swift pane is supported by the upstream global registry. Closing
it stops the native runtime and restores the prior callback sink. This harness
is not an isolation boundary for untrusted native code. UIKit/AppKit, SwiftData,
entitlements, arbitrary assets/dependencies, multiple windows and full SwiftUI
conformance are not provided. The existing JNI lazy-provider and scheduling
paths are connected; broad lazy-view/async behavior needs separate validation.

## Android benchmark

See [measured results and limitations](BENCHMARK.md).

```sh
python3 tools/swift-preview/benchmark.py
# Reuse an existing --benchmark build:
python3 tools/swift-preview/benchmark.py --skip-build
```

The opt-in build includes the previous JSON process protocol **only as a
benchmark baseline**. Both paths compile the same eager 10/100/1,000-row workload
with Swift release optimization. Each case performs 15 warmups and 50 measured
callbacks targeting the middle row. Tests assert state advancement, subtree delivery, untouched sibling
identity, disposal with a pending update, and reopening with fresh state.

Timing starts immediately before callback dispatch on Android's main thread
and ends after `TreeStore` assignment. It includes evaluation, transport,
materialization/JSON decoding, main-thread scheduling and tree splicing.
It excludes Compose rendering and display frames: these numbers are not FPS or
end-to-end touch latency. The baseline omits the old recursive styling pass,
which makes the comparison conservative. Initial-tree timing is diagnostic only;
process startup and JNI library loading are not equivalent.

Electron remains separate: Node alone supplies neither Chromium's desktop shell
nor a display host. Its web UI needs an explicit Android adaptation or a desktop
runtime/display route. The existing Node/Python CLI/TUI and local-server WebView
paths do not depend on this Swift bridge.
