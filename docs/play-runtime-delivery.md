# Google Play delivery for runtimes and SDKs

Investigated 2026-10-14; Node test implementation added 2026-10-15. Use **on-demand Play Feature Delivery**
for executable development tools, consider **Play Asset Delivery** for genuinely
non-executable SDK data, and reserve **AI packs** for actual model weights. This
includes local testing and an internal-track release, not a public production rollout.

## Swift and xtool delivery (2026-10-16)

Play builds now also stage checksum-pinned Android Swift/xtool inputs from
`XTOOL_ROOT` (default: the sibling `xtool` checkout). The input lock is
`tools/runtime-pack/xtool-inputs.lock.json`. Missing or changed inputs fail the
build rather than silently shipping an incomplete compiler. The 36 shared Termux
packages, including LLVM/Clang/LLD, must match the base pack's pinned hashes.
Apple SDKs are not distributor inputs.

`developer_runtime_xtool` carries the additional host executables/libraries;
`developer_runtime_swift_support` carries compiler modules, headers, static
libraries, scripts and other support data. Two on-demand modules keep each below
Google Play's 500 MB compressed-download limit. **Download Swift and xtool** in
Settings → SDK Manager requests both in one transaction. Neither partial nor
outdated installation becomes usable. Node remains independently optional.

The Swift adapter preserves compiler/driver selection and relocated resources.
Manifest-verified SplitCompat ELF entrypoints use Android's system linker while
retaining their prefix names. Swift libraries follow shared base libraries in the
search path; xtool's compatibility libraries come last. Compiler-only NDK stubs
are data and never enter the runtime library search path.
SwiftPM's generated manifest programs and SDK-supplied Android host tools also
use the linker when executed from private app storage. The adapter covers both
`execve` and `posix_spawn`, preserves Swift's command-line arguments, and resolves
the exec interceptors before compiler threads fork. Apple-target Clang calls
omit the Android sysroot and library defaults used for native Android builds.

The dedicated local test workflow now also downloads Swift before Node, compiles
and runs a Swift program and a SwiftPM executable, and repeats after a cold start.
Use `--apple-sdk /path/to/darwin.xtoolsdk` to additionally stream a developer-owned
SDK into **only the test application's private data** and exercise unsigned iOS
compilation, a source edit/rebuild, and Xcode workspace export. The SDK never
enters the AAB or the Play upload. These tests do not establish signing, iPhone
installation, iOS simulation, or real Play transport.

### Swift delivery verified 2026-10-16

On the dedicated ARM64/API-36 emulator (`emulator-5558`, 4 KB pages), the final
packaged build passed all eight ordered clean-install checks: base-only use,
delivery failure/retry and cancellation, Swift installation without Node, Swift
and SwiftPM compilation/execution, cold restart, independent Node installation
and child processes, and unsigned iOS compile/edit/rebuild/workspace export.
Two additional cold-start Swift/Node checks passed with airplane mode enabled,
Wi-Fi/data disabled and no active default network; networking was restored.

The exported `PhoneHello-unsigned.ipa` passed ARM64/iPhoneOS, resource and edited
text verification. SHA-256:
`594febff567ac6fa3df525d349fb85f32706ff1932bafb6df41ecf17d2d09e80`.
This does not prove signing or iPhone installation. Logs and the IPA are in
`tools/runtime-pack/build/play-test/`.

The runtime-pack suite ran 42 cases (zero failures, two skips), and the host
runtime suite passed 43 cases. Signed release 111 also passed bundle validation,
native alignment checks, manifest/hash verification and the no-duplicate-native
audit. For the reference device, base is **360.15 MB**, Node adds **22.94 MB**,
and Swift/xtool adds **566.34 MB** across its two optional modules (decimal MB,
compressed-download estimates). The Apple SDK is additional user-supplied data.
Real Play delivery and 16 KB-page execution remain separate checks.

## Node test build

`-PIDE_RUNTIME_DELIVERY=play` partitions the existing locked developer runtime into
the base app and `:developer_runtime_node`. The feature contains Node, npm, pnpm,
their scripts and Node development headers. Native support libraries, Python,
shell and compiler tools stay in base with a single copy of the license/source
inventory. Default `bundled` distribution continues to carry everything for
offline sideloading. This flag is separate from `IDE_APP_ENGINE_DELIVERY`, which
controls engines embedded into generated apps.

Settings → SDK Manager shows an explicit **Download Node.js** action, download
progress, cancellation, failure/retry, Play confirmation when needed, and an
installed-pack verification action. The same component appears in workbench
runtime availability. Enable switches retain their existing meaning; installation
does not change them or run project code.

The runtime host resolves installed split libraries from the class loader, checks
all native and data hashes, composes a versioned prefix from the available packs,
and invalidates a prepared base-only installation when Node arrives. A missing or
outdated Node pack leaves Python/shell available. SplitCompat is initialized at
application startup and on activities so installed packs survive a cold start.

SplitCompat's extracted native libraries are read-only app files, which cannot
be launched directly with Android `execve`. The base therefore carries a small
`libdevruntime-node.so` entrypoint that invokes Android's system linker on the
verified Node payload. The existing prefix helper recognizes the loaded Node
image and reports that stable entrypoint for `/proc/self/exe`; otherwise Node's
`process.execPath` points at the linker and breaks child/fork execution. This
does not change Node's native bytes, chmod the delivered library, add a second
Node runtime, or affect executable-path reporting in Python/compiler processes.
See [Android's execution restriction](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission)
and the [system linker's executable loading support](https://android.googlesource.com/platform/bionic/+/refs/heads/main/linker/linker_main.cpp).

Reproduce with an ARM64 test device and an official
[bundletool-all JAR](https://github.com/google/bundletool/releases):

```sh
python3 tools/runtime-pack/play_test.py \
  --device emulator-5556 --bundletool /path/to/bundletool-all-1.18.3.jar --test
```

The helper builds `ai.building.pack.dev`, validates the actual AAB, creates an
APK set with `--local-testing`, installs only base initially, stages the feature,
and exercises the download through `SplitInstallManager`. It never uploads to
Play or replaces `ai.building` / `ai.building.dev`. Use `--reset-test-app` only to
erase this dedicated test app and repeat the base-only phase; use `--skip-build`
to reuse the current generated artifacts. Outputs and test logs are under
`tools/runtime-pack/build/play-test/`.

Local testing simulates Play transport; the signed split files, integrity checks,
native processes and package managers are real. An internal Play track remains
necessary to establish actual store delivery.

For the signed store candidate, use the [internal testing preparation workflow](play-internal-testing.md).
It builds the release application ID with Play delivery, verifies the upload
signature and actual bundle, and stages Console-ready files separately from this
debug/local-simulation build.

### Verified 2026-10-15

ARM64 emulator `emulator-5556`, Android API 36, 4096-byte pages:

| Check | Result |
| --- | --- |
| Play-mode AAB + instrumentation APK build | Passed |
| Default bundled APK build and full runtime artifact audit | Passed |
| Base-only Python execution with Node/npm absent | Passed |
| Simulated network failure, cancellation, then successful install | Passed |
| Existing prepared host recognizes the newly delivered Node pack | Passed |
| Read-only delivered Node, native child processes, crypto, npm and pnpm | Passed |
| Cold process restart | Passed |
| Offline runtime/terminal suite with airplane mode enabled | 28 passed, including a compiled N-API addon, workers, HTTP preview, process teardown, Python, shell and settings |
| Actual Settings download button, progress and installed verification | Passed; execution rechecked after this UI installation |
| PackageManager-installed feature splits after reinstall | Passed; Node/npm/pnpm/children run through the normal installer-owned native path too |
| Host runtime suite | 259 cases, zero failures, 2 skipped |
| Host runtime-pack + packaging checks | 40 cases, zero failures, 2 skipped |

Actual AAB stored payload: **358.65 MiB base**, **21.97 MiB Node feature**.
These are compressed AAB entries, not installed storage. The feature contains
one Node ELF and 2,562 data files; no native bytes are duplicated across modules.
The added base launcher is 6.6 KiB uncompressed.

Local outputs: `tools/runtime-pack/build/play-test/building-node-play-debug.aab`,
`building-node-local.apks`, `building-pack-tests.apk`, individual phase logs,
`offline-runtime-suite.log`, and Settings screenshots. AAB SHA-256:
`fe3c31f28824db53eff25f0d1bae0b026ef61b490915b569e6407b1aed701c2c`.

Real Google Play transport, low-storage behavior, 16 KB-page devices, older API
levels, and updates to a different payload/app version remain separate acceptance
work. The verified reinstall used the same app version. Pack removal and other
runtime families are not implemented in this first Node test.

## Which service fits?

| Service | Appropriate payload here | Constraints |
| --- | --- | --- |
| Play Feature Delivery | Node/npm/pnpm, Python, shell, LLVM/Clang/LLD, Swift/SourceKit/xtool, compiler JAR/DEX and their support files | Delivers signed feature APKs containing code and resources. Native execution from a newly installed split must be qualified for our process launcher. |
| Play Asset Delivery (PAD) | Documentation, source archives, indexes and other non-executed SDK data after classifying individual files | Assets cannot contain executable code. Moving the existing mixed runtime ZIP into an asset pack would not meet this boundary. |
| Play for On-device AI (beta) | Future local inference models | Model-only packs; Java/Kotlin and native libraries belong in the base or a feature module. It is not general-purpose storage for compilers or SDK archives. |

Sources: [feature delivery](https://developer.android.com/guide/playcore/feature-delivery),
[asset delivery](https://developer.android.com/guide/playcore/asset-delivery),
[AI packs](https://developer.android.com/google/play/on-device-ai).

PAD's documentation includes integration for apps as well as games. Neither
Google Play Games Services nor a game-category change is needed for this design.
The important distinction is the payload type, not the marketing name.

The current [Play Console size table](https://support.google.com/googleplay/android-developer/answer/9859372)
lists compressed-download limits of **500 MB per base/feature module**, **1.5 GB
per asset pack**, **4 GB for all modules plus install-time packs**, and **30 GB for
on-demand/fast-follow asset packs**. Some Android guides still mention 200 MB;
use the dedicated limits table and check the actual uploaded bundle. These are
download limits, not raw AAB or installed-directory sizes. The AI guide separately
documents 1.5 GB per AI pack and 4 GB cumulative; do not assume PAD's larger
allowance applies to AI packs.

## Current repository evidence

Measured release APK from `2a65f63a`, SHA-256
`370e98f27a5607449ab250a40e3bba747a02263694d73fa9b3394a306c2607aa`:

| Contents | Stored/compressed MiB | Uncompressed MiB |
| --- | ---: | ---: |
| Development runtime native entries | 161.44 | 475.54 |
| Development runtime assets and notices | 53.75 | 59.80 |
| Everything else | 162.50 | 301.02 |

The 215.19 MiB runtime total is a potential partitioning opportunity, **not a
promised base-size reduction**. Shared dependencies, module metadata and APK
compression will change the result. The data ZIP is already compressed; its
expanded directory will be larger than the archive's uncompressed ZIP-entry size.

Native contributions include Clang 41.68 MiB, libLLVM 40.54 MiB, LLVM tools
23.05 MiB, ICU 14.32 MiB, Node 14.12 MiB, CMake 12.21 MiB and Python 3.72 MiB
(stored bytes, excluding each package's support files and dependencies).

There is already `android/runtime-engine/ide-feature`, an on-demand module selected
by `IDE_APP_ENGINE_DELIVERY=play`. It delivers the **embedded engine for generated
apps**, not the IDE's developer runtime. Its manifest, native extraction settings,
installed-context refresh and bundle verifier are reusable patterns. The default
IDE still carries its developer tools in the base APK; this prototype does not
prove Play delivery of the full development toolchain.

The original `AndroidRuntimeHost` cached the base native directory and materialized
one complete runtime prefix. The Node prototype now uses a fresh installed-path
resolver and per-component verified manifests.
Keep the existing content hashes and package ownership so shared native files
have one owner. Swift/iOS integration must reuse LLVM as documented in
[packaging](packaging.md#xtool-integration-boundary); the standalone xtool APK is
not a second payload to embed. Keep the Apple SDK user-supplied in this plan.

## Proposed component and Settings contract

1. Keep the editor, project storage, settings and static preview in the base app.
2. Define shared native support, Node/package managers, Python, native build tools,
   and later Swift/iOS components. Determine exact dependency closures from the
   existing locks/manifests. Native npm builds require Python and compiler tools;
   Swift also needs LLVM. Do not duplicate those dependencies per component.
3. In the existing Settings hub, keep **enabled** separate from **installed**.
   Turning off a runtime prevents new IDE sessions while preserving active work
   and data. A future download action shows required shared components and bytes;
   readiness requires both verified installation and an enabled preference.
4. Provide distinct download/cancel/retry and remove-download actions. Track
   unavailable, downloading, verifying, ready and failed states. Never remove
   projects, environment files or dependencies created by users as a side effect
   of turning off a switch. Retain shared components while another runtime uses
   them. Restore preferences on a new device, then re-query installed payloads.
5. Use on-demand delivery for optional tools. Fast-follow would download them
   automatically, undermining the user's choice to leave them disabled.

Feature-module removal is deferred: a removal request is not proof that disk
space is already reclaimed. Refresh installed context/native paths after a split
arrives; native loading has special considerations documented in
[on-demand delivery](https://developer.android.com/guide/playcore/feature-delivery/on-demand).
The Node test above exercises real `ProcessBuilder` and PTY execution, child
processes and compiled addons. Other runtime families still need their own
execution and dependency qualification.

For PAD, query locations at use time and handle missing or updating packs. Treat
pack files as immutable; they can move or be invalidated by updates. This is not
an arbitrary URL/CDN API: content is published with the app's bundle and version.

Keep a bundled/offline distribution for sideloading. Google's delivery APIs depend
on Google Play, even though AABs themselves are an open format. See the
[App Bundle FAQ](https://developer.android.com/guide/app-bundle/faq).

## Remaining proof before broader migration

Take this Node build through an internal Play test track, then qualify other
runtime families using the same real-process checks. Exercise insufficient
storage, updates to new app/payload versions, and shared-dependency removal.
The local transport and same-version reinstall checks above do not establish
those behaviors or actual store delivery.

Moving distribution to Play addresses where the shipped runtime comes from; it
does not certify all subsequent project dependency execution. The
[Device and Network Abuse policy](https://support.google.com/googleplay/android-developer/answer/16559646)
restricts executable-code downloads outside Play and separately addresses
interpreted code. Keep the existing native-tool download distribution gates;
qualify developer workflows without promising blanket store approval.

No asset/AI packs have been created. Version 1.0.1 (110) was published to the
internal track with the Node feature; no tester list has been selected and actual
store installation/download remains unverified. Swift/xtool now uses the same
delivery mechanism; consult the release receipt and device logs for its validation.
