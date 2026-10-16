# Swift → Cupertino preview on Android

An executable proof for the Swift runtime: compile Swift view sources against
the open-source `SwiftUICore` shim, run the resulting ARM64 executable **on
Android**, render its view tree with AndroidSwiftUI / Compose Cupertino, and
send native control events back to Swift. `@State` and application actions stay
in Swift. This is not a screenshot, JavaScript translation, or remote simulator.

The first deliverable is a separate preview APK and an embeddable
`SwiftPreview(executable, libraries, modifier)` composable. It does not yet add a
clone/build/run command to Building's editor. Compilation happens on the Mac;
execution and UI happen on Android. The sample executable is packaged in the
APK's executable native-library directory. Running newly compiled files on a
phone must use Building's runtime launcher, not execute files from writable app
storage directly.

## Run

Requires macOS, Swift **6.3.2**, its Android SDK, Android SDK 35, JDK 21 and an
ARM64 Android 9+ device/emulator. The first run fetches pinned source dependencies
and Gradle. `SWIFT`, `JAVA_HOME`, `ANDROID_HOME` and `ANDROID_SERIAL` can override
the defaults. Nothing is downloaded at app runtime.

```sh
python3 tools/swift-preview/preview.py --install
python3 tools/swift-preview/verify_device.py --case sample

# Clone an independent MIT-licensed SwiftUI counter and compile its original
# ContentView.swift without editing it:
python3 tools/swift-preview/preview.py --github-counter --install
python3 tools/swift-preview/verify_device.py --case github

# Compile your own root view and any supporting model/view files:
python3 tools/swift-preview/preview.py --source /path/ContentView.swift \
  --source /path/Model.swift --root ContentView --install
```

The GitHub proof uses [Takenori Kabeya's SwiftUI counter](https://github.com/takenori-kabeya/SwiftUIStateManagementBook-Counter/tree/05bb80af3d0700eb926bf4031da50729ab1d78f3),
specifically `SimpleCounterSwiftUI/SimpleCounterSwiftUI/ContentView.swift`.
The iOS `@main App` entry is replaced by the Android preview host; the view source
and actions remain unchanged. This does not build the upstream Xcode project or
its separate TCA example.

Build outputs are under `.build/android/preview-app/build/outputs/apk/debug/`
and `.build/android/preview/build/outputs/aar/`. The AAR's renderer/Compose
dependencies must also be included by an embedding app; it is not a fat AAR.
Task source checkouts and generated products remain under ignored `.build/`;
Gradle uses its standard user cache.
`build-receipt.json` records the compiler, source hashes, dependency revision and
APK hash. The device verifier separately saves screenshots, UI hierarchy and
interaction/process-lifecycle receipts in `.build/evidence/`.

## Scope

The shim exposes the existing SwiftUICore API, and the renderer is reused from
[AndroidSwiftUI in the SwiftKey fork](https://github.com/maceip/Swiftkey/tree/9bbcc0bc008d43fa3e204fe5856cd41cc03f4d7c/AndroidSwiftUI).
That fork includes [Compose Cupertino](https://github.com/slanos/compose-cupertino).
Ordinary `Text`, `Button`, `Toggle`, and nonsecure `TextField` nodes receive
Cupertino controls; stacks, modifiers and other nodes use the existing renderer.
Two common counter symbols have explicit mappings (minus uses a minus glyph).
Unsupported symbols retain the renderer's visible diagnostic.

This transport supports one live view, synchronous actions, and full tree
updates. Lazy provider containers fail explicitly. Async tasks/timers, native
UIKit/AppKit, SwiftData, entitlements, assets, arbitrary package dependencies,
multiple windows and full SwiftUI conformance are not provided by this harness.
Compiler errors remain build failures: there is no success stub or automatic
source rewrite. SwiftUI syntax coverage is not a claim that every supported API
has been exercised through this host.

The single-view limitation is enforced because the upstream renderer has a
global callback sink. Closing the pane terminates its process and restores the
previous sink. A second simultaneous pane shows an error.

## Verify the Swift state round trip

```sh
python3 tools/swift-preview/preview.py --host-only
python3 tools/swift-preview/verify_protocol.py \
  tools/swift-preview/.build/swift/.build/arm64-apple-macosx/debug/PreviewApp
```

This sends real button/toggle/text events to the compiled sample and checks
increment, reset, Unicode text binding and clean EOF. Device interaction is a
separate check; a host test alone does not establish Android execution.

Validated on 2026-10-16 on the connected ARM64 API 36 emulator: both APKs built,
installed and rendered; the sample's counter, toggle, text binding and reset
passed, and the unchanged GitHub view's increment/decrement passed. Closing
each view terminated its Swift child. Installed APK hashes matched the build
receipts. The host protocol checks passed; unknown callbacks and UIKit imports
failed explicitly. These checks do not establish physical-device or editor
integration support.

Electron remains a separate renderer route: its Chromium/native desktop shell
needs a platform port and a display host (for example Termux:X11), or an explicit
adaptation of its web UI and Electron APIs. Node's runtime alone does not create
that desktop window. The existing Node/Python local-server WebView path and
their CLI/TUI paths do not depend on this Swift bridge.
