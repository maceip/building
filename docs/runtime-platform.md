# Android developer runtime

## Choice and boundaries

The Android workbench uses a small **APK-delivered Android/Bionic runtime pack** and a generic process controller. It does not embed Vivari, pretend to be the JVM `ProgramInterpreter`, or introduce a second virtual filesystem. Node, npm, dash and Python are separate native/interpreted upstream programs, not API shims.

The previous design in `docs/tripple_plaj.md` Part 2 is not an implementation contract. Its Vivari toolchain bridge, zero-copy filesystem opcodes, size estimates and broad language parity claims are not established by the checked sources. The existing Gradle prefix was the simpler technical starting point, but its runtime downloads are not a suitable delivery mechanism for a Play-target runtime.

Current scope:

- Detect `package.json` scripts, `.js` / `.mjs` / `.cjs`, `.py`, `.sh`, and static HTML within three directory levels.
- Explicit Run, Stop, stdin lines, stdin EOF, bounded stdout/stderr, exit code, per-project configuration, and loopback preview URL.
- Run Node and Python from the installed pack; POSIX shell is **dash**, not Bash. Android system tools on PATH are not a complete GNU/Termux userspace.
- Serve static HTML/CSS/JS with a small read-only HTTP server. No native runtime provisioning is needed for static previews.
- Explicit npm dependency installation has two distribution modes below. There is no automatic install when opening or running a project.
- No claim of native Bun, arbitrary npm native addons, manylinux wheels, Rust/Cargo, proc macros, Vite/Next.js, full Bash, Docker or a general Linux environment. A runtime version probe is not ecosystem acceptance.

## Sources and integration

- `services/developer-workbench/src/main/kotlin/dev/ide/workbench/runtime/RuntimeModels.kt`: host/controller data contracts.
- `RuntimeController.kt`: application/backend-scoped lifecycle, separate stdout/stderr, cancellation and explicit dependency action.
- `RuntimeProjects.kt`: read-only target detection, `.building/runtime.json`, non-overwriting samples.
- `RuntimeWorkspace.kt`: optional private disk copy for external-storage projects and explicit output synchronization.
- `StaticPreviewServer.kt`: bounded read-only HTTP on `127.0.0.1`, Host/Origin checks and project-root/path guards.
- `app/ide-android/src/main/kotlin/dev/ide/android/workbench/runtime/AndroidRuntimeHost.kt`: offline provisioning, environment and Android process groups.
- `tools/runtime-pack/runtime-launcher.c`: APK-native session/process-group leader; `runtime-env.c` supplies the small POSIX env subset needed by npm shebangs without dropping child interception.
- `tools/runtime-pack/build.py`, `packages.lock.json`, `recipes.lock.json`: build-host acquisition, checksums, safe extraction, native alignment checks and provenance.
- `app/ide-android/runtime-pack.gradle.kts`: staging task, applied by the Android app build. Native/asset source directories are wired in the app's own Android DSL.

The older generic build SPI (`build-system/build-api/.../Build.kt`) has `BuildSystem.runTasks/actionFor` and `RunTaskProvider`. `ProgramRun.kt` supplies `ProgramIo`, but its interpreter request is JVM classpath/main-class specific. The workbench therefore has its own neutral `RuntimeHost`/`RuntimeChild`; it does not route Node through bytecode interpretation.

The controller's `run()` launches asynchronous IO work. `stop()` waits for termination; `close()` performs nonblocking cleanup using a cleanup scope independent of the caller's cancelled lifecycle. The app must close/stop on project change and explicit close. Scenario tests should use their own controller and temporary workspace, not interrupt an interactive session. Separate controllers should not concurrently mutate the same internal-copy workspace.

A run is **user-trusted code with the application's UID and permissions**, not a sandbox. The JVM permission broker does not mediate these native processes. Environment variables are constructed from an allowlist and do not inherit provider credentials, but this does not prevent a malicious project from reading app-private files. Do not run untrusted downloaded projects without informed consent. No privileged JavaScript bridge belongs on preview content.

## APK delivery, provenance and licenses

The staging task selects 27 checksum-pinned Termux packages, not the existing 77-package, 323.34 MiB Gradle bootstrap. Current runtime packages include Node LTS 24.18.0-1, npm 11.20.0, CPython 3.14.6-1, pip 26.2.1 and dash 0.5.13.5. Exact archive paths, lengths and hashes are in the package lock / referenced bootstrap catalog and copied to the generated manifest.

Metadata source:

- https://packages.termux.dev/apt/termux-main/dists/stable/Release
- https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages.gz
- Metadata SHA256 `a356d6bf1aa0a291f684e213c8820375a2437bc9d9569ab9fd59fb8e3054ab5f`, matched against the HTTPS Release file dated 12 October 2026. This is HTTPS and checksum validation, **not an independently verified OpenPGP signature**.
- Pinned source recipe tree https://github.com/termux/termux-packages/tree/3885d8e1577097760b3bc4ee8d247ef367545d8e/packages

Every native ELF is staged as a uniquely named `libdevruntime_*.so` in `jniLibs/arm64-v8a`, including CPython extension modules. Android's installer extracts them into `nativeLibraryDir`; the private prefix contains symlinks to those files. The ZIP contains only interpreted scripts, standard library data, configuration and notices. Bundled scripts whose shebang embeds `/data/data/com.termux/files/usr/` are normalized to `/usr/` so termux-exec maps them into the active private prefix; the manifest records original and staged hashes plus the transformation. No package pre/postinstall scripts execute and no apt database is installed. `.a`, `.o`, headers and development-only data are omitted.

APK assets include `developer-runtime/manifest.json`, `prefix.zip`, and `notices/packages.json`, hash-pinned source recipes, GPL text and distribution notice. Upstream LICENSE/copyright files already present in package contents are retained, including Node/V8, npm's vendored modules and CPython. Licenses are **not uniform**: Node MIT, npm Artistic-2.0 plus vendored notices, Python PSF/custom notices, dash BSD, gdbm/readline GPL-3, CA data MPL-2, and other dependencies retain their own terms.

**Release gate:** source recipes are not full corresponding source. A distributor still must supply exact matching sources, patches/build configuration and complete upstream notices as required by each license. The generated provenance makes that audit possible; it is not a claim that a repository URL alone meets GPL source-distribution obligations. Perform this audit before public binary redistribution.

Build-host commands:

```sh
python3 tools/runtime-pack/test_build.py
python3 tools/runtime-pack/build.py --output app/ide-android/build/developer-runtime-pack
python3 tools/runtime-pack/build.py --output app/ide-android/build/developer-runtime-pack --offline
```

An installed NDK is required for the tiny launcher. The pack task reuses the existing native-helper NDK and never downloads an NDK itself. First staging downloads only the pinned package/recipe inputs; subsequent staging works offline. Host caches are in ignored `tools/runtime-pack/build/`. No device network is used by provisioning.

Observed staged size before APK compression: 123 native files totaling 109,038,544 bytes, plus an 11,692,930-byte data ZIP (after 85 recorded script-header normalizations) and manifest/notices/launcher. These are measured generator outputs, not an APK delta or installed-size promise.

## Play-target versus developer dependency mode

The policy boundary is independent of technical execution:

- Google Play's Device and Network Abuse policy prohibits downloading executable code outside Play, with an interpreter/VM exception exemplified by JavaScript in a WebView/browser. Interpreted JS/Python/Lua loaded at runtime must still prevent policy violations. There is no blanket IDE exemption.
- APK delivery of the interpreter and native libraries avoids an ambient `.deb` downloader for this runtime. **Hash checking does not make remote native downloads policy compliant.**
- In the default/Play-target mode, `allowNativeDependencyDownloads=false`. The explicit npm action is offline and currently accepts dependency-free projects only. Arbitrary registry tarballs can contain ELF, `.node` or `.so` payloads even with lifecycle scripts disabled; no unimplemented general pure-JS auditing is claimed.
- In a developer/sideload build, `BuildConfig.NATIVE_TOOLCHAIN_DOWNLOADS_ENABLED` permits the explicit npm install action. It warns that untrusted and possibly native packages will be downloaded, uses the HTTPS npm registry/strict TLS, and disables automatic lifecycle scripts. It does **not** claim Play approval or Linux/Bionic compatibility. A user can subsequently Run their trusted scripts, including package code; this is not an enforcement sandbox.
- Pip is bundled for runtime availability but there is no generic pip-install UI or native-wheel compatibility promise.
- Existing Gradle/toolchain/plugin execution paths require independent app-wide review. Making this runtime APK-delivered does **not** certify the whole APK as Google Play compliant. The legacy automatic Gradle prefix bootstrap should remain disabled; any developer-only setup must be explicit and distribution-gated.

Official resources:

- https://support.google.com/googleplay/android-developer/answer/9888379?hl=en
- https://developer.android.com/privacy-and-security/risks/dynamic-code-loading
- https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission

## ABI, relocation and filesystem semantics

This pack supports arm64-v8a only. Static web preview does not depend on that pack. All shipped native PT_LOAD alignments are checked to be at least 16 KiB by staging; that does not replace testing on an actual 16 KiB Android kernel, checking APK/AAB packaging, or reviewing page-size assumptions inside upstream code.

The inspected Pixel 11 Pro XL baseline was Android 17/API37 with **4096-byte pages**. Model name is not evidence of 16 KiB operation. Official guidance: https://developer.android.com/guide/practices/page-sizes . Query actual state with `adb shell getconf PAGE_SIZE`.

Termux packages have compiled-in prefix assumptions. This implementation relocates absolute archive symlinks, supplies an explicit prefix/library/certificate/home environment, `PYTHONHOME`, and the existing child-only `libsdk-exec-prefix.so` / `libtermux-exec.so` interception. The native prefix is deliberately short: `<filesDir>/dr/p/<16-hex-pack-id>/usr`, with the full pack identity in its completion stamp. Actual emulator tracing found termux-exec 2.5 rejects a `TERMUX__PREFIX` of 90 bytes or more and silently falls back to Termux's original prefix; a length guard now rejects unsupported long app/storage paths explicitly. Home, caches and optional workspace copies remain under `<filesDir>/developer-runtime`. Old versioned prefixes are not automatically deleted. It does not rewrite arbitrary filesystem paths or intercept direct system calls. Shell, npm lifecycle subprocesses, Python imports/subprocess and certificate paths need device verification independently.

Internal projects run directly. External/shared-storage projects default to an isolated internal disk copy because npm/pnpm depend on links and executable semantics that external Android storage does not reliably provide. Before Run, source files copy inward; `node_modules`, virtual environments, `.git`, `.building` and Python cache directories stay out of that sync. A run never silently overwrites the source workspace. **Sync new outputs** after Stop creates only files that do not already exist in source. Bytes are staged first; commit rechecks existence and uses a create-only hard link (or a non-replacing copy on external filesystems). Existing source files are never overwritten, even when their baseline hash still matches: runtime modifications require manual merging and are reported as conflicts. There is no shared editor write lease, so a hash-check-plus-replace would be unsafe. Deletions never propagate outward. This copy mode rejects project symlinks, caps source data at 128 MiB / 20,000 files, and is not live HMR synchronization. Rerun after editor saves. Imported dependencies should be installed explicitly in the runtime copy.

The shell/console uses pipes, not a PTY; terminal job control, full-screen terminal apps and REPL parity are out of scope. Stop sends TERM then KILL to the session process group. This does not contain malicious programs that detach into a new session or otherwise evade group ownership. Android memory/background/phantom-process restrictions still apply; a process group does not exempt it from those policies. Long-running background service survival is not established by the host tests.

## Why not Vivari or a minimal JS/Wasm engine by default?

Read-only research found useful but materially different systems:

- `/Users/mac/vivari` is an MIT fork of https://github.com/maceip/vivari with real vendored Node JS library code on custom bindings; Bun is an API shim, not native Bun. Local modified files were present, so not all behavior can be attributed to a clean published revision.
- Its `vm/shared/java/run/vivari/vm/MainActivity.java` needs a feature-tested WebView cross-origin-isolation allowlist; `vm/shared/app.gradle` uses AndroidX WebKit 1.18.0-alpha02. `LocalServer.java` hosts assets/egress. This is much more than an ordinary optional web preview.
- `packages/kernel-host/toolchain-bridge.js` explicitly describes an experimental, unverified native/ART bridge. `OP_MOUNT_IMPORT`, `OP_EXPORT_CHANGED` and `/__vm/toolchain` in the proposal were not present in the inspected implementation.
- `vm/shared/java/run/vivari/vm/LoopbackPreviewServer.java` is native-host buffered HTTP, rejects upgrades/chunked requests and is not an external HMR/WebSocket solution.
- `/Users/mac/vivari-perf/docs/runtime-shell.md` reports only 4/19 agent shell workflows passing; it is not a POSIX/Bash replacement.
- `/Users/mac/vivari/vm/results/native-coverage/python-webserver/report.md` proves four Python HTTP cases in system WebView on an ARM64 emulator, while native QuickJS/WAMR Python fails. The Pixel memory report explicitly says original acceptance was not met and does not prove general runtime readiness.
- `/Users/mac/vivari-perf/docs/runtime-performance.md` limits the experimental Rust Wasm compiler: no Cargo acquisition, build scripts, proc macros or Android memory acceptance. Compressed compiler assets were about 43.8 MB and expanded assets about 159 MB, not a general Rust toolchain promise.
- QuickJS (MIT, https://bellard.org/quickjs/) is a small JS engine, not Node/npm/Python/WASI by itself. WAMR (Apache-2.0 with LLVM exception, https://github.com/bytecodealliance/wasm-micro-runtime) supplies Wasm execution, not an npm/Python package platform. Combining them still requires workers, host IO, bindings, package resolution and compatibility maintenance; the local WAMR Python failure is concrete evidence against treating it as the easy fallback.
- Plain WebView JavaScript/Wasm and `WebViewAssetLoader` are good for constrained snippets/static previews, not Node/Python/shell parity: https://developer.android.com/develop/ui/views/layout/webapps/load-local-content .

Vivari remains an optional future WebView-only runtime backend if a validated browser package ecosystem is needed. It is not necessary for the current native process/preview seams.

## Validation ledger

Completed on the host:

- Five Python staging-boundary tests (prefix traversal, malformed ar input, arm64/16 KiB check, offline hash verification, selected closure).
- All **11 runtime JVM tests passed**, with zero failures, errors or skipped tests: detection/config, actual shell input/exit/stderr, cancellation/rerun, output bounds, static HTTP/close/path guards, explicit copy sync, dependency restrictions, non-overwriting samples, create-only sync under a racing editor save, failed-copy workspace ownership, and prefix-length guards. The coordinator's final XML confirms this count; `build/workbench-final-jvm-tests.log` records the consolidated run, with all **134 workbench tests passing**.
- APK-runtime staging and repeat staging with `--offline`; every selected package and source recipe hash checked.

**All eight runtime device tests passed on emulator-5556, Android API36, ARM64, actual 4096-byte pages.** Evidence: `build/workbench-runtime-webbuild-device-tests-4.log` (`OK (9 tests)`: eight runtime tests plus the separately implemented web-to-Android signed APK build). Runtime acceptance covers:

- Real Node/npm/Python/dash version probes, including Python SSL, sqlite3, ctypes and readline imports.
- `npm start` Node HTTP response, file output and descendant/port teardown.
- Direct `npm`/`npx` shell invocation and an actual `node_modules/.bin` symlink to a `#!/usr/bin/env node` project executable.
- Real offline `.tgz` dependency installation, lockfile/package use, and suppressed `postinstall` hook. This is local-package acceptance, not internet registry or arbitrary framework coverage.
- Python `subprocess` through `sys.executable`, interactive stdin, SQLite and persisted JSON output.
- External-workspace private copy and explicit new-output sync.
- Static HTML HTTP preview without a privileged bridge.
- Stop while a child never reads its full stdin pipe.

Earlier failed runs are retained: unsupported ART `Files.readString/writeString` calls, lexical `/data/data` versus `/data/user/0` alias comparison, and termux-exec's 90-byte prefix buffer were found on-device and fixed. The final run uses explicit UTF-8 byte IO, canonical root comparisons, recorded script-header normalization and a short validated native prefix.

The physical Pixel disconnected before runtime acceptance. **No physical Pixel or actual 16 KiB-kernel acceptance is claimed.** Native PT_LOAD alignment checks passed, but they do not replace those device tests. Separate user-facing UI/navigation and generated-APK install checks belong to the coordinator's integration evidence, not to these runtime tests.

Focused commands:

```sh
./gradlew :developer-workbench:test --tests 'dev.ide.workbench.runtime.*'
./gradlew :ide-android:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.workbench.runtime.RuntimeDeviceTest
```

Run device instrumentation only on the explicitly selected test device/app, not the user's production app. The tests create disposable child folders and never install network dependencies into user projects.
