# Google Play delivery for runtimes and SDKs

Investigated 2026-10-14. Recommendation: use **on-demand Play Feature Delivery**
for executable development tools, consider **Play Asset Delivery** for genuinely
non-executable SDK data, and reserve **AI packs** for actual model weights. This
is a delivery design, not an implemented Play download service or a Play approval.

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

`AndroidRuntimeHost` currently caches the base native directory and materializes
one complete runtime prefix. Splitting packages requires a fresh installed-path
resolver and per-component verified manifests, rather than changing only Gradle.
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
Our next prototype must exercise real `ProcessBuilder` execution and child tools,
not only `System.loadLibrary`. That execution behavior remains unproven for the
proposed split development runtime.

For PAD, query locations at use time and handle missing or updating packs. Treat
pack files as immutable; they can move or be invalidated by updates. This is not
an arbitrary URL/CDN API: content is published with the app's bundle and version.

Keep a bundled/offline distribution for sideloading. Google's delivery APIs depend
on Google Play, even though AABs themselves are an open format. See the
[App Bundle FAQ](https://developer.android.com/guide/app-bundle/faq).

## Next proof before migration

Build one small Node feature with its complete native dependency closure, using
the existing packaging machinery. Verify base-only launch/editing, explicit
install, real Node/npm execution, process teardown, offline reuse, update and
re-enable behavior. Test cancellation and insufficient storage, and check
shared-dependency removal. Start with bundletool local testing, then an internal
Play test track; the former does not establish real Play download behavior.

Moving distribution to Play addresses where the shipped runtime comes from; it
does not certify all subsequent project dependency execution. The
[Device and Network Abuse policy](https://support.google.com/googleplay/android-developer/answer/16559646)
restricts executable-code downloads outside Play and separately addresses
interpreted code. Keep the existing native-tool download distribution gates;
qualify developer workflows without promising blanket store approval.

No asset/AI packs have been created, no runtime bytes have been moved, and nothing
has been uploaded to Play as part of this investigation.
