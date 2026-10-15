# App packaging and configuration

The IDE APK includes the complete Node/npm/pnpm, Python, shell and native build
toolchain. These tools remain available offline after APK installation. Optional
engines embedded in generated applications are a separate deliverable, configured
through `IDE_APP_ENGINE_DELIVERY=none|direct|play` and `IDE_APP_ENGINE=node|python`.
Those settings do not remove the IDE development runtime.

Users can enable or disable Node/package-manager, Python, shell/terminal and static
preview launches in **Settings > Runtimes**. Choices use the existing app preference
store and apply to new sessions. Existing work and installed payloads are retained;
these switches do not reduce the APK size. Shell/Python dependencies used by other
enabled tools remain available. See [Play runtime delivery](play-runtime-delivery.md)
for the investigation and proposed on-demand component design.

## Inspect the artifact

```sh
./gradlew :ide-android:assembleDebug :ide-android:assembleDebugAndroidTest
python3 tools/packaging/audit.py \
  app/ide-android/build/outputs/apk/debug/ide-android-debug.apk \
  --report build/reports/packaging/debug.json
```

The same command accepts an AAB and verifies its base-module development runtime.
It checks every declared native/data file against its size and SHA-256, the native
launcher, ARM64 ELF segment alignment, runtime links, mandatory tool entry points,
and the absence of orphaned native payloads. The APK CI workflow runs this check.
The optional generated-app engine's split has its own verifier:
`tools/runtime-engine/verify_bundle.py`.

The inventory distinguishes stored/compressed bytes, uncompressed bytes and
archive overhead (headers, signatures, alignment and incremental free space).
For a fair size comparison, regenerate the APK archive itself; deleting only the
generated APK output preserves compilation caches and avoids incremental ZIP holes.
Neither number is an installed-storage estimate: APK retention, extracted native
libraries, versioned runtime prefixes, project dependencies and caches also use
space. An artifact check does not certify a physical device or Play delivery.

## Ownership and duplication

- `tools/runtime-pack/packages.lock.json` selects shared packages from the Gradle
  bootstrap catalog and pins additional runtime packages. Acquire each package
  once; preserve its headers, scripts, notices and build inputs.
- Native runtime payloads are named by their full content SHA-256. Identical bytes
  share one APK entry, while every original logical path and package owner remains
  in the manifest. Different bytes never get merged merely because filenames match.
- `:lang-kotlin` supplies the single `kotlin-stdlib.jar` classpath resource. Both
  production compilation and instrumentation fixtures use it. The debug APK no
  longer needs an additional asset copy.
- VM, Compose and Moshi instrumentation fixtures depend on
  `preDebugAndroidTestBuild`. Ordinary app builds do not compile or resolve these
  test-only fixtures. The compiler test shares the VM test's Android Compose
  bytecode jar; the retired desktop forwarding JAR contained no compiler API classes.
- Native libraries remain compressed and installer-extracted
  (`useLegacyPackaging=true`), because tool execution requires `nativeLibraryDir`.
  Already compressed JAR/ZIP assets retain the existing storage policy.
- Duplicate notices are reported but retained with their respective distributions.
  R8's separate dex asset is also retained: its forked process uses that smaller
  classpath to avoid repeatedly loading the complete IDE APK.
- Release shrinking remains at its existing setting. Enabling R8 indiscriminately
  would require separate compiler, reflection and plugin-loading acceptance.

## Build configuration

Full APK+AAB packaging uses an 8 GiB Gradle heap with at most two workers;
bundletool exhausted 4 GiB during validation of this complete IDE. Debug-only CI
uses a 4 GiB override. Hosts can explicitly override
`org.gradle.jvmargs` and `org.gradle.workers.max` for their capacity.

Debug builds append `.dev` to the configured application ID unless it already ends
in `.dev`; the default install identity is `ai.building.dev`. Public release defaults
live in `config/android-release.properties`, shared by Gradle and the release helper.
Ordinary builds accept Gradle-property/environment overrides; the release helper selects
an authoritative environment and verifies its public configuration inside both APK and
AAB. Signing credentials remain private. See [production configuration](android-production.md).
Packaging verification does not publish or sign an iPhone application.

AGP's native packaging/stripping uses pinned NDK `27.3.13750724`, also installed by
the APK CI workflow. Source-built Android SDK helpers and the development runtime
continue to use their existing pinned source recipes/toolchain. Executable runtime
payloads retain `keepDebugSymbols`, so AGP cannot rewrite manifest-verified bytes.

Runtime staging, terminal JNI and Hono sample staging are compiled `buildSrc`
plugins. Keeping these helpers out of externally applied Kotlin scripts also avoids
the [K2/UAST included-script lint bug](https://android.googlesource.com/platform/tools/base/+/44330ad8b855e38930c70ca44f65e781dd95d1ab)
while preserving release lint checks.

## xtool integration boundary

The Android build foundation is merged into the separate xtool repository's main
branch at `9fb63e9`. It has not yet been wired into Building's project import/build
UI or shipped as part of this APK. Its 1.812-GiB standalone validation APK is not
the incremental size of adding Swift/iOS development to Building.

The current locked inputs overlap in 35 packages, including identical LLVM,
Clang and LLD 21.1.8-3. Their staged native artifacts overlap by 362.12 MiB
uncompressed across 233 unique binaries. Future integration must extend the
existing runtime pack instead of embedding a second complete prefix/APK.

Reuse the current runtime's Mach-O linker tools (`ld64.lld`,
`llvm-libtool-darwin`, `dsymutil`). The older NDK sample plugin's LLVM 18 build
explicitly disables Mach-O and is not the replacement for this toolset.
Reconcile libcurl 8.22.0 versus the xtool validation lock's 8.23.0 deliberately,
and preserve Building's source-built, 16-KiB-aligned libandroid-spawn override.
Swift/SourceKit, xtool, macro support and a user-supplied Apple SDK are additional
inputs; equivalent filenames do not establish compatibility between Swift versions.

Per the user's current scope, signing/provisioning, iPhone deployment, local iOS
emulation and remote Simulator work are deferred. SourceKit semantic timeouts and
Building's Swift project integration also remain explicit follow-up work. The
proven xtool path creates/edits SwiftPM projects, builds iOS bundles/IPAs and exports
Xcode workspaces inside a separate Android app on an emulator.

## Validation for packaging changes

Keep a baseline APK and runtime manifest in ignored build output. Compare actual
packaged files and package versions after each change; don't infer savings from
source deletions. Build the debug APK, instrumentation APK, release APK and AAB.
Exercise Node/npm lifecycle hooks, Python subprocesses, a native addon build and
Kotlin compilation on an explicitly selected development device. Production
`ai.building` and unrelated emulators are not test targets.

## Verified checkpoint: 2026-10-14

On `feature/developer-platform-expansion`, the default full-runtime configuration
(`IDE_APP_ENGINE_DELIVERY=none`) builds the debug APK, instrumentation APK, release
APK and AAB. Release vital lint is enabled and passes. These are local development
artifacts; the release outputs use the default upstream application ID, are unsigned,
and are not production publication candidates.

| Artifact | Size (MiB) |
| --- | ---: |
| Baseline debug APK | 418.55 |
| Fresh debug APK | 416.77 |
| Release APK | 377.97 |
| Release AAB | 373.20 |

The debug reduction is about 1.78 MiB. No runtime package was removed: all 49 locked
packages, pnpm, 17,183 data files, 1,068 links, and all 255 logical native file
checksums/owners are unchanged. Two identical native records now share one file,
leaving 254 physical payloads. The retained standard-library resource is byte-for-byte
identical to the removed asset. The retired 4,711-byte Compose forwarding artifact
contained neither `Composable` nor `Composer`; the test now uses the existing Android
Compose bytecode fixture. Notices and separate R8 execution payloads remain.

- **Android:** all 24 selected tests passed, with no failures or skips, on the dedicated
  `emulator-5556` (API 36, ARM64, 4-KiB pages), package `com.tyron.code.dev`, version
  116 / 3.28.6. Coverage includes Node/npm lifecycle hooks, Python/subprocess/SQLite,
  native addon compilation/loading, bundled processor classpaths, direct and forked
  Kotlin compilation, Compose compilation, and KSP/Moshi processing.
- **Test correction:** the first run exposed three older compiler tests selecting
  the unavailable LightTree backend. The relevant compiler DEX is byte-identical to
  the baseline. Their options now match production's `useFirLT=false` PSI path;
  all compile/output assertions remain intact. The subsequent full 24-case run passed.
- **Host:** seven artifact-audit tests and 28 runtime-pack tests passed; two existing
  optional source-build checks were skipped. Gradle's workbench/build suites are green
  (305 cases, two existing external-data skips; test tasks were up-to-date).
- **Artifact checks:** debug APK, release APK and AAB passed SHA/size inventory,
  entry-point, link and native-alignment verification. App-only release tasks exclude
  instrumentation fixtures. This does not close physical 16-KiB or Play acceptance.
- **xtool:** the merged `9fb63e9` revision passes 27 Swift tests and lint with zero
  violations. Its separate Android foundation remains outside the Building APK.

Exact sizes, SHA-256 values and payload inventories are in ignored
`build/reports/packaging/{baseline,debug,release,bundle}.json`. Build and device logs
are `build/packaging-pass-build.log` and `build/packaging-device-tests.log`.
An incremental APK can retain several MiB of free ZIP space; compare fresh archives,
not intermediate output left after repeated asset updates.
