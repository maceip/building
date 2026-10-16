# Editor-first delivery proof

An isolated Android application in the main checkout, package
`ai.building.loader.prototype`. It does not replace `ai.building` or change the
product's dependency graph. Uses the repository Gradle wrapper and version
catalog, with a separate settings file to keep the large IDE out of this build.

Verified 2026-10-16: **1,180,023-byte standalone APK**, **1,057,166-byte initial
download estimate**, and **33,647,082 bytes** for the optional Node download.
All eight device checks passed on ARM64/API 36, with 4 KB pages, using the shrunk
non-debuggable release. Full release lint and APK v2/v3 signatures passed.
See the [measured report](../../docs/editor-first-delivery-assessment.md).

Stage one is a Compose plain text editor: Android document-picker open/save,
undo/redo, find, atomic draft recovery and a persistent JavaScript enable switch.
`EditorDocument.kt` and `Rope.kt` are compiled directly from the product sources
through a generated source directory; there is no separately maintained copy.
The full IDE canvas, language intelligence and preview UI are outside this proof.
Opening files is limited to UTF-8 documents of at most 1,000,000 bytes.

Stage two is a real `node_runtime` on-demand feature containing its implementation
DEX and the product's checksum-verified Node ELF plus recursive native library
dependencies. It runs the editor's source, including Node child processes. The
base knows only a `RuntimeFeature` interface and a reflection entrypoint. Node
is neither statically referenced nor available before installation. Small native
launch adapters remain installer-owned in base, as in the existing runtime.
This spike does not yet move npm, Swift, the compiler stack or the full IDE into
the new shell. The product's existing Node/Swift Play release is unchanged.

## Reproduce

Requirements: Android SDK 36.1, JDK 21, the repo's provisioned Android NDK,
bundletool 1.18.3, and a disposable ARM64 emulator. The locked product runtime
inputs must already be staged; if missing, prepare them from the repo root:

```sh
./gradlew -PIDE_RUNTIME_DELIVERY=play :ide-android:partitionDeveloperRuntimePack
```

Build the optimized, signed, **non-debuggable release**, then prove it:

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
./gradlew -p experimental/editor-loader \
  :app:bundleRelease :app:assembleRelease :app:assembleReleaseAndroidTest
python3 experimental/editor-loader/verify.py --device emulator-5558 --skip-build
```

The verifier refuses physical devices, replaces only the isolated prototype
package, restores emulator networking, and fails if either the standalone APK or
the initial bundletool download estimate reaches **10,000,000 bytes**. It checks
that Node's native payload is absent from base. Release artifacts and a JSON
receipt go to `dist/editor-loader-proof/` (ignored). No product signing key is
used: this prototype uses the standard Android development key for reproducible
local installations. Release optimization is enabled for both feature and base.

The device tests exercise real document-provider permission grants and saved
bytes, editing/undo/redo/search, two offline cold starts, persisted settings,
download failure and retry, cancellation, real Node execution, child processes,
and execution after editing the source. Tests operate the same release code
measured for size; no debug-only feature implementation replaces it.
The verifier then installs the standalone APK independently and repeats both
offline editor checks. Its platform-only instrumentation runner avoids test
dependencies on code removed by release shrinking. Failure injection finds the
local-testing manager actually used by the UI, including its library wrappers.

## What the evidence means

`bundletool --local-testing` installs real signed splits but simulates the Play
transport. A passing run is not evidence of a Play server download. The standalone
base APK proves offline editing and file size; a sideload installation does not
by itself establish entitlement to download features from Google Play. An actual
store test needs this application registered and installed through Play.

The device-specific download estimate, raw standalone APK, AAB upload size and
installed disk usage are different measurements. The 10 MB gate covers the first
two, not disk usage. Runtime installation downloads whole signed modules and
caches them for offline use; it does not stream partial native execution.

This is a bounded architecture proof, not the full product migration. API 26,
16 KB-page execution, app-version updates, low-storage recovery and large-file
editor performance need separate qualification before adoption. The prototype
limits each JavaScript run to 15 seconds and displays at most 32 KiB of output.

Primary reference: [Google's on-demand delivery and local testing guide](https://developer.android.com/guide/playcore/feature-delivery/on-demand).
