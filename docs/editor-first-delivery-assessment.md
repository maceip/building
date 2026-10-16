# Editor-first delivery: measured prototype

2026-10-16. Following approval of the bounded prototype, an isolated application
now lives in [`experimental/editor-loader`](../experimental/editor-loader/README.md)
in the main checkout. It reuses the product's text model and pinned Node payloads.
It does not replace `ai.building` or change the full product's dependency graph.

## Verified prototype

The optimized, non-debuggable ARM64 release passes both strict **10,000,000-byte**
gates. Measurements are decimal bytes, not installed disk usage:

| Measurement | Bytes | MB |
| --- | ---: | ---: |
| Standalone editor APK | 1,180,023 | 1.18 |
| Initial Play download estimate, reference device | 1,057,166 | 1.06 |
| Optional Node feature, additional download | 33,647,082 | 33.65 |
| Entire AAB upload | 37,055,202 | 37.06 |

The optional feature includes Node's shared native dependencies as well as its
implementation DEX. Only two small native launch adapters and Compose's small
graphics-path helper remain in base. Node's code and runtime assets are absent
before installation. This spike includes Node execution, not npm or the other
IDE/Swift engines.

Eight device checks passed on the dedicated ARM64/API-36 emulator, with 4 KB
pages. They cover real Android document-picker open/save and saved bytes;
Unicode editing, undo/redo and find; draft and settings persistence across a cold
offline restart; failed download/retry and cancellation; downloaded Node and
child-process execution; running edited source; cold offline runtime reuse;
creating a new `.js` document without changing its extension; and independently
installing the measured standalone APK and repeating both offline editor checks.
Release build, full release lint and APK v2/v3 signature verification pass.
Emulator networking was restored to its original state; the physical phone was
not used.

Delivery uses **bundletool's local Play simulation**, not a Play backend download.
The signed feature APKs and native execution are real. The standalone APK also
works as an offline editor when installed directly, but that installation does
not establish entitlement to fetch features from Play. No new Play release was
published for this prototype. The main application's initial download remains
approximately 360 MB until the remaining migration is performed.

Local artifacts, receipt, logs and screenshots: `dist/editor-loader-proof/`.
Standalone APK SHA-256:
`d9f4992a5a73478e703cb62a61f9634d9722902cc0437fd822be1e3893a704d2`.
AAB SHA-256:
`849a67c30ebb2a800434e69a043f510d6e366bbffd36ebe926007a0216a20db1`.

## Recommendation

Target an initial Google Play download below **10,000,000 bytes**, with file
opening/saving, plain text editing, undo/redo, search and download settings working
offline. Deliver the IDE engines, compilers, previews and other optional features
as signed on-demand feature APKs. They download completely and are cached locally;
native tools do not execute from a partially streamed download.

Google Play generates the device APKs from a larger AAB. The uploaded AAB does not
need to be below 10 MB. This is the most direct fit for the existing Node and
Swift/xtool delivery implementation. A sideloaded 10 MB APK cannot rely on the same
Play delivery path; a separate installation/update flow would need its own design
and testing. Do not implement a generic CDN downloader for native or DEX/JAR code
in the Play-distributed app.

Sources: [Play Feature Delivery](https://developer.android.com/guide/playcore/feature-delivery),
[on-demand modules](https://developer.android.com/guide/playcore/feature-delivery/on-demand),
[Play executable-download policy](https://support.google.com/googleplay/android-developer/answer/16559646).

## Measured baseline

Build 110's bundletool reference initial-download estimate was 360,081,915 bytes;
Play Console displayed a 360 MB base download.
The local signed AAB contains the following compressed base entries (decimal MB;
AAB entry totals differ from Play's generated APK download estimates):

| Base contents | MB |
| --- | ---: |
| Native libraries | 158.20 |
| Assets | 77.04 |
| DEX code | 69.95 |
| Root resources, including embedded compiler/processor archives | 55.69 |
| Android resources, manifests and metadata | 1.26 |

Moving only runtime binaries/assets cannot achieve 10 MB: approximately 126 MB
of compressed code and root resources would still remain. Processor archives
include Hilt, Moshi, Room and Glide; this is not primarily an image-size problem.

## Remaining full-product migration

- Keep the editor/document model, file access, preferences and delivery interface
  in base. Reuse the current Compose editor where its dependency closure permits.
- Move Kotlin/Java language engines, compiler JAR/DEX, Gradle/build tooling and SDK
  resources out of base.
- Move preview/rendering implementations and JVM/AWT execution into optional
  features. The current editor module includes preview surfaces and UI tooling,
  so simply relocating native libraries is insufficient.
- Defer other heavy IDE/agent services and their initialization until requested.
- Put shared interfaces in base and implementations in features: a base module
  cannot statically depend on an optional feature. `:ide-android` currently
  directly depends on `:ide-core`, `:gradle-build`, `:layout-preview-impl`,
  `:android-support`, `:agent-impl` and the full UI.
- Preserve the prototype's measured size gate and offline checks as the real
  product's features are migrated. Shared native dependencies will need a shared
  optional foundation rather than separate copies in each runtime feature.

The sub-10 MB plain editor plus later runtime installation is now demonstrated
locally. This does not establish that the full current IDE fits into that base:
the prototype uses the existing document/rope model and a small Compose editing
surface, not the full IDE canvas, previews and language intelligence. No global
R8 setting or production launcher was changed. Actual Play transport, older API
levels, 16 KB-page execution, app-version updates and low-storage recovery remain
separate qualification work.
