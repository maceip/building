# Editor-first delivery: assessment only

2026-10-16. The user has authorized Swift/xtool delivery but requires a check-in
before coding an editor-only base or another sub-10 MB architecture. No such
architecture change is included in the runtime delivery work.

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

## Required dependency changes, pending approval

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
- Measure an isolated editor-only release build with shrinking enabled. Verify
  open/edit/save and cold startup without any optional modules or network. Then
  exercise download, cancellation/retry, restart and offline reuse.

A sub-10 MB editor is technically plausible, but the current editor's size has
not been measured in isolation. It is a target to prove with an approved spike,
not a claimed result or a promised size for the full current IDE. Runtime
packaging work does not enable global R8 or change the application's initial UI.
