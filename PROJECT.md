# Project: Cross-Platform Compiler & GUI Execution Pipelines

## Architecture
This project implements, hardens, and verifies bidirectional cross-platform on-device compilation and interactive GUI execution:
1. **Android on iOS (`:ide-ios`, `:android-apk`, `:jvm-vm`, `:kotlin-analysis-ios`)**:
   - Compiles and packages Android projects on-device into APK without child processes via `ApkBuilder`.
   - Executes Dalvik lifecycle in `DexVm` and renders live interactive Jetpack Compose GUI in parallel via `:jvm-vm` with native iOS Skia (`SkikoNativeBindings`) to `RunFrameUi`.
   - Forwards touch pointer events from `RunScreen` through `IosPreviewRenderer` to `VmPreviewEntry`'s `ImageComposeScene`.
2. **iOS on Android (`:ide-android`, `:exec-service`, `GuiBridgeRuntime`, `Swiftc Toolchain`, `xtool`)**:
   - 100% self-contained on-device native ARM64 Swift 6.4.1 toolchain with `libSwiftUI.a` (6.1MB).
   - Direct execution on Android Bionic via `libsdk-exec-prefix.so` and `/system/bin/linker64` with ZERO PRoot and ZERO Termux app dependency.
   - Spawns compiled guest binary with bidirectional stdio JSON ViewHost protocol: stdout emits `ViewNode` trees rendered as Cupertino widgets in `BridgeGuiRenderer.jvmShared.kt`; user taps send JSON event callbacks to child stdin updating `@State`.
3. **Physical Hardware Verification**:
   - **Android Device (`D6OZUCKVEIQ4LVLV`)**: Xiaomi 2506BPN68G, HyperOS 3.0 / Android 17, `ai.building.dev`. Verified via `android_ui.py` (`uiautomator dump`), `screencap -p`, and Google Files sideload.
   - **iPhone Device (`00008020-001325320A08002E`)**: iPhone XS Max, iOS 18.7.10, `com.ryanmacarthur.foundry`. Verified via `ios_usb_ui.py` over `/var/run/usbmuxd` (port 8100 server) and `devicectl`.

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | Baseline Compilation Fixes | Fix parameter mismatches in `IosBackend.kt` and missing imports in `AndroidTasks.kt` | M1 / M2 | survey |
| 2 | Activity `setContent` Extraction | Extract root `@Composable` from `MainActivity.onCreate` `setContent` block in `IosKotlinAnalysis.kt` | M1 | survey |
| 3 | Full Project Source Discovery | Remove 35-file truncation in `IosKotlinAnalysis.kt` and include `domain/`, `model/`, `data/` source packages | M1 | survey |
| 4 | Preview Resource & Context Provider | Implement `PreviewResourceResolver` and provide `LocalContext` & `LocalInspectionMode` in `VmPreviewEntry.open` | M1 | survey |
| 5 | Eliminate iOS Bridge Fallback Mock | Remove synthetic Cupertino summary card fallbacks (`buildInteractiveBridgeWindow`) for Android apps | M1 | survey |
| 6 | Event-Driven Preview & Resizing | Throttle PNG writing to avoid saturating preview thread; add `resize` method to `VmPreviewEntry` for `setRunSurfaceSize` | M1 | survey |
| 7 | Recursive Shadow Workspace | Recursively copy `Models/`, `Views/`, `Styles/`, `Components/` subdirectories in `prepareCupertinoShadow` | M2 | survey |
| 8 | Preserve `@main` on `App` | Retain `@main struct ...: App` and execute via `libSwiftUI.a`'s `App.main()` without synthetic dummy view fallback | M2 | survey |
| 9 | Robust Declaration Parser & Stubs | Fix comment/string-unaware brace parser in `dropUnsupportedDeclarations`; provide stubs for `UIViewRepresentable` and `: ToggleStyle` | M2 | survey |
| 10 | iOS Framework Shims | Add stubs for `AudioToolbox`, `AVFoundation`, `PhotosUI`, `WebKit` in prelude to avoid dropping files | M2 | survey |
| 11 | Eliminate Synthetic Card `_IdeRepoPreviewView` | Remove synthetic dummy cards and ensure genuine Swift compilation of cloned app views | M2 | survey |
| 12 | Asset Catalog & Local Image Support | Copy `.xcassets` into shadow workspace and support local asset loading in `PlatformImage.android.kt` | M2 | survey |
| 13 | Package Name Resolution | Support `ai.building.dev` dynamically in `ensureSwiftUiBridgeSdk` | M2 | survey |
| 14 | Zero-Containment Sandbox Cleanup | Replace `popen("unzip")` in `GuestAppRunner.kt:179` and dead `runProcess` in `IosRuntimeRunner.kt:242` with pure `IosZipExtractor` | M3 | survey |
| 15 | 4-Tier E2E Test Suite | Build comprehensive opaque-box test suite (Tiers 1-4) derived from user requirements, publishing `TEST_READY.md` | E2E Testing Track | survey |
| 16 | Physical Android E2E Deployment | Build, install, clone real SwiftUI repo, run, verify interactive Cupertino GUI and touch state transition on `D6OZUCKVEIQ4LVLV` | M3 | survey |
| 17 | Physical iPhone E2E Deployment | Build, install, clone real Android repo, run, verify interactive Compose GUI and touch forwarding on `00008020-001325320A08002E` | M3 | survey |
| 18 | Task Completion Protocol | Dedicated task branch verification, git commit, push, test merge-tree against `origin/main`, report | M3 | AGENTS.md |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| E2E | E2E Testing Track | Design & implement 4-tier opaque-box test suite; publish `TEST_READY.md` | none | DONE |
| M1 | Android on iOS Pipeline | Fix `:ide-ios`, `:android-apk`, `:jvm-vm`, `:kotlin-analysis-ios` for multi-file Compose & touch | none | DONE |
| M2 | iOS on Android Pipeline | Fix `:ide-android`, `GuiBridgeRuntime`, `Swiftc Toolchain`, `xtool` for multi-file Swift/SwiftUI | none | DONE |
| M3 | Self-Containment & Physical Deploy | Replace `popen` sandbox calls, build APK & iOS app, deploy and verify on `D6OZUCKVEIQ4LVLV` & `00008020-001325320A08002E` | M1, M2 | IN_PROGRESS |
| M_FINAL | 100% E2E Pass & Coverage Hardening | Pass 100% of E2E test suite (Tiers 1-4) followed by Phase 2 adversarial coverage hardening (Tier 5) | E2E, M1, M2, M3 | PLANNED |

## Code Layout
- `app/ide-ios/src/commonMain/kotlin/dev/ide/ios/`:
  - `IosBackend.kt`: Build and preview lifecycle, runKotlinComposeBuild, touch forwarding, run console state.
  - `IosKotlinAnalysis.kt`: Source root discovery, composable entry extraction, preview lowering.
  - `IosRuntimeRunner.kt`: Runtime runner and sandbox bridge execution.
  - `GuestAppRunner.kt`: App unzip extraction and guest launch.
- `app/ide-core/src/main/kotlin/dev/ide/core/`:
  - `runtime/GuiBridgeRuntime.kt`: Swift shadow workspace, source sanitization, swiftc invocation, interactive stdio bridge.
  - `runtime/RuntimeBuildSystems.kt`: SwiftBuildSystem task definition and build graph execution.
- `android/android-support/src/main/kotlin/dev/ide/android/support/`:
  - `tasks/AndroidTasks.kt`: Android build tasks and manifest generation.
- `jvm-vm/`:
  - `dev/ide/interp/compose/VmPreviewEntry.kt`: JVM Compose preview entry point, Skia scene rendering, pointer events.
- `tests/`:
  - `e2e/`: Opaque-box E2E test suite managed by E2E Testing Track.

## Interface Contracts
### iOS Compose Preview Entry (`VmPreviewEntry`)
- `fun open(blob: ByteArray, width: Int, height: Int, density: Float, fontScale: Float, sessionId: Long, resolver: PreviewResourceResolver?): Long`
- `fun pointer(sessionId: Long, kind: Int, x: Float, y: Float)`
- `fun resize(sessionId: Long, width: Int, height: Int)`
- `fun frame(sessionId: Long): ByteArray?`
- `fun close(sessionId: Long)`

### Android Swift ViewHost JSON Bridge (`GuiBridgeRuntime` ↔ `BridgeGuiRenderer`)
- Guest stdout line: `{"type": "<Widget>", "id": "<node_id>", "props": {...}, "modifiers": [...], "children": [...]}`
- Host stdin line: `{"id": "<callback_id>", "kind": "void"|"bool"|"double"|"int"|"string", "value": ...}`
