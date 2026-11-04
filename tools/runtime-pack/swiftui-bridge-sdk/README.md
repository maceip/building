# Cupertino SwiftUI Bridge SDK (Android / aarch64)

This directory is the **canonical, version-pinned source** of the SwiftUI bridge
that `stage_xtool.py` bakes into the on-device Swift runtime pack at
`lib/swift/android/`.

## Why this exists

The on-device Swift toolchain (compiler + real Android SDK) is pinned to a single
Swift version in `../xtool-inputs.lock.json` (currently **6.4.1**). The SwiftUI
bridge is a *separately built* static library (`libSwiftUI.a`) plus a set of
`.swiftmodule` shims (`SwiftUI`, `CoreGraphics`, `UIKit`, `Combine`) that let
user code `import SwiftUI` on Android.

A `.swiftmodule` is stamped with the compiler version that produced it. If the
bridge is built with Swift **X** but the on-device `swiftc` is Swift **Y**, every
`import SwiftUI` fails with:

```
error: module compiled with Swift X cannot be imported by the Swift Y compiler
```

This is a hard compile error — SwiftUI previews / Run have never worked while the
versions were mismatched. The bridge and the runtime pack MUST be built with the
same Swift version.

## Layout

```
swiftui-bridge-sdk/
├── VERSION                 # the Swift version this bridge is built against (must == lock "swift" version)
├── sources/                # 73 Swift files that compile into the single `SwiftUI` module
├── build-on-device.sh      # rebuilds + installs the bridge on a connected Pixel (uses the device's own swiftc)
├── .gitattributes          # LFS rules for the built *.a / *.swiftmodule artifacts
└── android-aarch64/        # BUILT OUTPUT (populated by build-on-device.sh); consumed by stage_xtool.py
    ├── libSwiftUI.a
    ├── SwiftUI.swiftmodule/{aarch64-unknown-linux-android,aarch64-unknown-linux-android28}.swiftmodule
    ├── CoreGraphics.swiftmodule/...
    ├── UIKit.swiftmodule/...
    └── Combine.swiftmodule/...
```

## Building

The host Mac has **no 6.4.1 Android SDK**, so the bridge cannot be compiled on the
Mac. Instead it is compiled **on-device** with the Pixel's own `swiftc` (which is
always the same version as the runtime pack), guaranteeing consistency.

```
# with the Pixel connected via ADB and USB debugging enabled:
bash tools/runtime-pack/swiftui-bridge-sdk/build-on-device.sh
```

This:
1. Pushes `sources/*.swift` to the device.
2. Compiles them into the `SwiftUI` module + `libSwiftUI.a` with the device toolchain.
3. Regenerates the `CoreGraphics` / `UIKit` / `Combine` re-export stubs.
4. Installs everything into the live runtime pack **and** the app's
   `developer-runtime/swiftui-sdk` cache (so it survives app restarts).
5. Writes `VERSION` and the built artifacts into `android-aarch64/`.

After a successful run, `git add android-aarch64` (the artifacts go through LFS)
commits the version-matched bridge so a fresh Mac pack build bakes the correct
one.

## Guardrail

`stage_xtool.py` reads `VERSION` and compares it to the lock's `swift` version.
A mismatch **fails the build** instead of silently baking a broken bridge. If the
bridge directory has no `VERSION` file, staging warns but proceeds (legacy
behavior) — so always keep `VERSION` in sync after a rebuild.
