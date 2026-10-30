# Dual-Track Compiler & Live GUI Execution Test Infrastructure (`TEST_INFRA.md`)

## 1. Test Philosophy & Core Principles

The Dual-Track Test Suite verifies bidirectional cross-platform on-device compilation and live interactive GUI execution in CodeAssist:
1. **Android on iOS (`:ide-ios`, `:android-apk`, `:jvm-vm`, `:interp-compose`)**: Clones, compiles, packages APKs on-device without child processes, interprets Dalvik Activity lifecycles in `DexVm`, and renders live Jetpack Compose surfaces with pointer event forwarding to `RunFrameUi`.
2. **iOS on Android (`:ide-android`, `:ide-core`, `GuiBridgeRuntime`, Swiftc Toolchain)**: Clones, compiles multi-file Swift / SwiftUI projects with bundled native `swiftc` (Swift 6.4.1) against `SwiftUICore` shims on Android Bionic, executing guest binaries via bidirectional stdio ViewHost JSON protocol with `@State` reactivity.

### Guiding Tenets
- **Opaque-Box Requirement Derivation**: Tests assert against observable outputs (exit codes, PNG magic bytes, inked pixel counts, Dalvik log outputs, ViewNode JSON structures, and `@State` transitions) rather than implementation internals.
- **Zero Fabrication & Zero Mock UI**: No synthetic cards (`_IdeRepoPreviewView`), no dead clickables, and no fake status fallbacks masking compilation errors. Real compiler invocations and real bytecode/binary execution drive every test.
- **Strict Self-Containment**: Enforces zero external `com.termux` runtime dependencies and zero `proot` (`ptrace` userland translation) invocations.
- **Deterministic & Isolated**: Every test runs in isolated temporary directories, initializes its own state, and cleans up completely after execution.

---

## 2. Test Suite Architecture

```
/Users/mac/building/
├── tests/
│   └── e2e/
│       ├── run_all.sh                 # Master bash invocation script
│       ├── runner.py                  # Python test orchestrator & reporter
│       ├── test_tier1_features.py     # Tier 1: Feature isolation checks
│       ├── test_tier2_boundaries.py   # Tier 2: Boundary & stress checks
│       ├── test_tier3_combinations.py # Tier 3: Pairwise matrix checks
│       └── test_tier4_real_world.py   # Tier 4: Real-world apps & zero-fabrication
├── run/
│   ├── android-apk/src/commonTest/kotlin/dev/ide/android/apk/e2e/
│   │   └── AndroidApkE2ETest.kt       # Tier 1-4 tests for Manifest, ApkBuilder, DexVm
│   └── interp-compose/src/desktopTest/kotlin/dev/ide/interp/compose/
│       └── VmPreviewE2ETest.kt        # Tier 1-4 tests for Skia PNG rendering & pointer events
└── app/
    └── ide-core/src/test/kotlin/dev/ide/core/runtime/e2e/
        └── SwiftCupertinoE2ETest.kt   # Tier 1-4 tests for Swiftc, ViewHost JSON, @State transitions
```

---

## 3. Runner Commands

### 3.1 Primary Master Runner
Executes all 4 Tiers across both pipelines with formatted reporting:
```bash
./tests/e2e/run_all.sh
```
Or directly via Python:
```bash
python3 tests/e2e/runner.py
```

### 3.2 Granular Target Runner Commands

#### Android APK & Dalvik Lifecycle (`:android-apk`)
```bash
./gradlew :android-apk:jvmTest --tests "dev.ide.android.apk.e2e.AndroidApkE2ETest"
```

#### Compose Preview & Touch Pointer Pipeline (`:interp-compose`)
```bash
./gradlew :interp-compose:desktopTest --tests "dev.ide.interp.compose.VmPreviewE2ETest"
```

#### Swiftc & ViewHost JSON Bridge Pipeline (`:ide-core`)
```bash
./gradlew :ide-core:test --tests "dev.ide.core.runtime.e2e.SwiftCupertinoE2ETest"
```

---

## 4. Four-Tier Test Inventory

### Tier 1: Feature Coverage (Core Isolation)
Verifies each feature in isolation against documented interface contracts:

| ID | Test Name | Target Pipeline | Description | Authoritative Expected Output |
|---|---|---|---|---|
| T1.1 | `tier1_manifestParser_extractsPackageAndLauncherActivity` | Android-on-iOS | Parses `package` name and `LAUNCHER` Activity from manifest XML | `package == "com.example.calculator"`, `launchActivity == "...ui.MainActivity"` |
| T1.2 | `tier1_apkBuilder_derivesSpecFromProjectFiles` | Android-on-iOS | Derives `AndroidProjectSpec` combining manifest, build.gradle, and strings.xml | Matching `packageName`, `appLabel == "My Custom Title"`, discovered component classes |
| T1.3 | `tier1_apkBuilder_createsValidZipWithManifestAndDex` | Android-on-iOS | Assembles ZIP containing `AndroidManifest.xml` and Dalvik `classes.dex` | Zip contains valid entries; `DexFile.parse` succeeds on DEX header |
| T1.4 | `tier1_dexVm_launchesActivityAndLogsOnCreate` | Android-on-iOS | Executes Activity lifecycle in Dalvik VM | `result.ok == true`, transcript contains `"onCreate returned"` |
| T1.5 | `tier1_vmPreviewEntry_encodesAndRendersPngFrame` | Android-on-iOS | Encodes lowered `@Preview` and renders Skia PNG | Bytes match PNG magic header `0x89 0x50 0x4E 0x47`, size > 200 bytes |
| T1.6 | `tier1_vmPreviewEntry_sessionLifecycleOpenPointerFrameClose` | Android-on-iOS | Opens live session, dispatches pointer event (Press/Move/Release), closes session | Session ID > 0, initial frame non-empty, post-tap frame non-empty |
| T1.7 | `tier1_vmPreviewEntry_renderSummaryInkedPixels` | Android-on-iOS | Calculates inked pixel count across rendered frame | Summary contains `WxH`, `inked > 50` |
| T1.8 | `tier1_viewNodeJson_decodesStandardSwiftUiTree` | iOS-on-Android | Decodes ViewNode JSON stream emitted by Swift guest | `type == "VStack"`, `id == "root"`, children contains `Text` and `Button` with `onTap` |
| T1.9 | `tier1_viewHostProtocol_encodesStandardCallbackPayloads` | iOS-on-Android | Formats host stdin callback payloads (`void`, `bool`, `int`, `string`) | Valid JSON lines conforming to ViewHost protocol |

### Tier 2: Boundary & Corner Cases (Resilience & Stress)
Verifies error recovery, extreme dimensions, non-standard naming, and malformed inputs:

| ID | Test Name | Target Pipeline | Description | Authoritative Expected Output |
|---|---|---|---|---|
| T2.1 | `tier2_emptyProject_handlesZeroKotlinFilesGracefully` | Android-on-iOS | Assembles project with zero Kotlin files | Assembles valid APK with default `MainActivity` without throwing |
| T2.2 | `tier2_corruptedDexHeader_reportsFailureWithoutCrash` | Android-on-iOS | Feeds random bytes to `DexFile.parse` | Returns clean `isFailure`, zero process abort |
| T2.3 | `tier2_largeFileSet_scalesOver60FilesWithoutError` | Android-on-iOS | Project spec containing 80 Kotlin source files | Assembles APK; `result.dexClasses >= 10`, `result.ok == true` |
| T2.4 | `tier2_nonStandardActivityNames_normalizesCorrectly` | Android-on-iOS | Normalizes leading dot (`.Main`), qualified name, inner class (`$`) | Properly qualified activity strings matching Android specification |
| T2.5 | `tier2_specialCharactersInAppLabel_escapesCorrectlyInManifest` | Android-on-iOS | XML special chars (`&`, `<`, `>`, `"`) in app title | Properly escaped XML entities (`&amp;`, `&lt;Awesome&gt;`) |
| T2.6 | `tier2_extremeResolutions_microAndUltra` | Android-on-iOS | Renders surface at 10x10 px and 1024x1024 px | Valid PNG generated at both extremes without memory error |
| T2.7 | `tier2_invalidTouchCoordinates_negativeAndOutOfBounds` | Android-on-iOS | Dispatches pointer at (-100, -150) and (99999, 99999) | Scene handles coordinates gracefully, frame renders without exception |
| T2.8 | `tier2_invalidSessionId_returnsNullFrame` | Android-on-iOS | Calls `frame()` on non-existent session ID | Returns `[null, "no preview session...", false]` |
| T2.9 | `tier2_rapidSessionOpenCloseStress` | Android-on-iOS | Rapidly opens and closes 15 consecutive preview sessions | Zero resource leak or descriptor exhaustion |
| T2.10 | `tier2_malformedJsonLines_handledGracefullyWithoutCrashing` | iOS-on-Android | Feeds truncated and non-JSON lines to parser | Gracefully rejected without thread panic |
| T2.11 | `tier2_deeplyNestedViewNodeHierarchy_scalesOver15Levels` | iOS-on-Android | ViewNode tree nested 20 levels deep | Encodes and decodes successfully without StackOverflowError |
| T2.12 | `tier2_unicodeAndSpecialCharactersInLabelsAndProps` | iOS-on-Android | Labels containing emojis, quotes, backslashes | Preserves exact UTF-8 character encoding |

### Tier 3: Cross-Feature Combinations (Pairwise & Matrix Interactions)
Verifies multi-feature interactions, theming matrices, and geometry combinations:

| ID | Test Name | Target Pipeline | Description | Authoritative Expected Output |
|---|---|---|---|---|
| T3.1 | `tier3_customApplicationWithGradleNamespaceAndMultipleScreens` | Android-on-iOS | Custom `Application` + `SplashActivity` + 4 screens + Gradle namespace override | Launch activity displayed; custom Application initialized in Dalvik logs |
| T3.2 | `tier3_multiDensityAndFontScaleVariations` | Android-on-iOS | Density scale matrix (1.0x mdpi, 2.0x xhdpi, 3.0x iPhone XS Max) | Distinct render signatures reflecting pixel rasterization scaling |
| T3.3 | `tier3_darkThemeAndBackgroundColor` | Android-on-iOS | Light mode vs Dark mode with custom `#1E1E1E` background | Both produce valid PNGs matching respective luminance |
| T3.4 | `tier3_formWithMixedInputControlsButtonToggleTextField` | iOS-on-Android | Single `Form` with Toggle (`isOn`), TextField (`text`), and Button (`onTap`) | Correct typed callback IDs preserved across heterogeneous widgets |

### Tier 4: Real-World Application Scenarios (Full Realistic App Execution)
Verifies full end-to-end applications and runtime integrity:

| ID | Test Name | Target Pipeline | Description | Authoritative Expected Output |
|---|---|---|---|---|
| T4.1 | `tier4_realWorldScenario_bitChordMusicApp` | Android-on-iOS | Full multi-file BitChord project spec, APK assembly, and DexVm run | `result.ok == true`, `>= 4` DEX classes, `onCreate returned` in transcript |
| T4.2 | `tier4_realWorldScenario_viviMusicPlayer` | Android-on-iOS | Full multi-file vivi-music Material3 app spec and DexVm run | Custom `ViviApp` initialized, `MainActivity` displayed, `result.ok == true` |
| T4.3 | `tier4_interactiveButtonSceneWithStateReactivity` | Android-on-iOS | Interactive Compose scene with pointer tap dispatch | Initial frame > 500B, post-tap frame > 500B |
| T4.4 | `tier4_realWorldScenario_swiftUiCalculatorCompilationAndStateExecution` | iOS-on-Android | Multi-file SwiftUI Calculator app with `CalculatorBrain.swift` + `CalculatorView.swift`, compiled with host `swiftc` | `Result: 0` in stdout; sending button tap callback over stdin advances state to `Result: 7` |
| T4.5 | `test_zero_fabrication_audit` | Both | Source audit verifying absence of `ProotCommandBuilder` and `com.termux.RUN_COMMAND` | Zero occurrences in codebase |

---

## 5. Continuous Verification & Reporting

The test infrastructure integrates with the project's build automation and CI:
- On code changes to `:android-apk`, `:interp-compose`, or `:ide-core`, run the corresponding targeted test task.
- Before committing or handing off milestones, run `./tests/e2e/run_all.sh` to ensure zero regressions across all 4 tiers.
