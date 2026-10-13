# Static web output → native Android project

The workbench's **Offline web → Android / Package / build** entry packages an **already-built static site** into a separate native CodeAssist Android project. It does not run npm, pip, shell commands, a web build, a server, a downloader, signing, or publishing while packaging.

## Supported boundary

Choose the source workspace explicitly, then a workspace-relative static output directory containing a non-empty `index.html`, such as `dist`, `build`, `public`, `static`, or `out`. `.` is allowed for an already-static workspace (including the runtime's static sample). The source-folder picker on Android may import a copy through SAF; the dialog shows which local path is actually read.

- Supported: local HTML/CSS/JavaScript, bundled fonts/images/data, directory `index.html` pages, and an optional SPA document fallback. Frameworks work only after a genuine static production export with all runtime assets included.
- Not supported in the final APK: Next.js SSR/server actions/API routes, Python/Flask/Django, Node servers, local dev-server URLs, remote APIs/CDNs, OAuth services, camera/microphone/geolocation/native plugins, or filesystem access. A framework's `public` folder is not automatically its complete built site.
- The wrapper has **no Internet permission**. Remote dependencies will not load. JavaScript requiring `eval`, remote workers/frames, native bridges, or unrestricted browser capabilities needs adaptation, not a claim of automatic support.
- Packaging does not establish app quality or Google Play eligibility. Review Play policies, content rights, target-SDK requirements, testing, privacy disclosures, and minimum-functionality expectations independently.

## Workflow

1. Build/export the site yourself with the runtime or other tooling. Stop concurrent writes to the chosen output.
2. Open the package dialog. Confirm the source workspace and explicit static output; choose a **new** project folder, output parent, package name, app label, version code and version name.
3. Choose whether missing extensionless page routes may use `index.html` (SPA). `/api`, `/assets`, `/_next`, `/static`, missing files with extensions, and non-document requests do not use that fallback. Existing directory indexes take precedence.
4. External HTTPS links are blocked by default. The opt-in allows only a top-level user gesture followed by a native confirmation displaying the URL; it does not grant network access to the WebView.
5. Review the exact included assets, sizes, SHA-256 values, excluded paths/reasons, output path and aggregate asset-list hash. The asset list is paginated inside a single bounded dialog, not a nested scrolling runtime pane.
6. Create the project. Packaging reads the source but does not write its files, change the active IDE project, flush its editor buffers, copy its credentials, or rewrite an existing Android project. The copy is based on the on-disk static output, not unsaved editor text.
7. Choose **Open generated project in IDE** to explicitly switch projects. The source project remains available unchanged.
8. Choose **Build / run debug APK** for the existing native debug build (on device the run task also invokes the existing install/launch flow). Read the normal IDE build console for completion/errors.
9. For an AAB, create/import your own upload keystore in the IDE's signing settings, refresh keys in the dialog and explicitly assign one to `app` / `release`. Then choose **Build signed release AAB**. No production key is fabricated and no upload is initiated.

Existing output files can be exported through the host's standard Save As flow. Existence alone is not a claim that the latest build succeeded or that an artifact has the right signing certificate.

## Actual on-device bridge

This is a native-project generator, not a Gradle-only export or a web-runtime promise. It writes the format already consumed by `ModelPersistence`, `AndroidFacetCodec`, and the native Android build system:

```text
<chosen-output-parent>/<new-project-name>/
  .platform/workspace.json       # workspace schema 1; buildSystem = native
  .platform/libraries.json       # empty dependency list
  .platform/sdks.json            # empty: host seeds its installed/bundled SDK on open
  .platform/webapp-package.json  # generation/source metadata; not shipped as a web asset
  app/module.toml               # module schema 2; android-app; Java 8
  app/proguard-rules.pro
  app/src/main/AndroidManifest.xml
  app/src/main/java/<package>/MainActivity.java
  app/src/main/java/<package>/BundlePath.java
  app/src/main/res/...
  app/src/main/assets/webapp-manifest.json
  app/src/main/assets/web/index.html
  app/src/main/assets/web/...
```

The module has the normal `main`, `debug`, and `release` source sets with explicit Java, Android resources, and **ASSETS** roots. It uses minSdk 26 and compile/target SDK 36, matching the currently bundled platform. It has no Maven dependencies, Gradle wrapper/distribution, npm dependencies, copied SDK paths or signing secrets. `IdeServices.openAt` seeds the host SDK; the existing on-device aapt2/compiler/dexer/packager/signer build it. Packaging does not require a new Gradle distribution.

Real native task IDs and output paths:

| Action | Task ID | Output relative to the new project |
| --- | --- | --- |
| Assemble debug APK | `assemble:app:debug` | `app/build/outputs/apk/debug/app-debug.apk` |
| Device debug build/install/run | `androidRun:app:debug` | same APK |
| Release Android App Bundle | `bundle:app:release` | `app/build/outputs/bundle/release/app-release.aab` |

The panel discovers the offered tasks through `backend.build.runTasks()` and dispatches through `backend.build.runTask(...)`; it does not launch a separate Gradle process. A missing native task/toolchain is reported, not replaced by a silent download.

**Signing caveat:** the existing general IDE build pipeline falls back to its debug keystore if a release signing reference is absent/unresolved. The packaging panel therefore requires an explicit currently registered, readable, unexpired non-debug key and rechecks the assignment before dispatch. It cannot change the semantics of the general Run picker. Always inspect the produced AAB's actual certificate before upload; a filename or `release` task name is not proof of production signing. The publishing preflight must reject debug-signed artifacts independently.

## File safety and review

`StaticWebAppPackager.review` is read-only. `create(review)` re-scans and compares the reviewed list/hashes/exclusions, then checks every asset again while copying.

Default bounds:

- 10,000 candidate files and 20,000 visited entries;
- 32 MiB per included/inspected file, 128 MiB total inspected bytes;
- 24 path components and 512 UTF-8 bytes per asset path;
- bounded streaming reads, including credential-content checks.

Traversal, absolute static-root paths, dependency/hidden/credential roots, malformed/non-portable URL names, ambiguous case/Unicode filenames, source/output overlap, and stale reviews are rejected. The source workspace itself is canonicalized; links **inside** the chosen root are never followed. Linked static-root components are rejected. Symbolic links, special files, and hard links where the filesystem exposes link counts are excluded.

Excluded categories include hidden files/directories (`.git`, `.env`, etc.), `node_modules`, caches/credential directories, common private keys/keystores, service-account files, package-manager metadata, source maps, and known server/source files. Streaming checks also detect recognizable private-key/service-account/token markers. **This is not a complete secret scanner.** Arbitrary credentials can be embedded in otherwise ordinary JS/JSON/text; inspect the public build and review every file you intend to ship. Exclusions can also break a site that references excluded material; test the installed app.

The destination's exact new name is reserved using exclusive directory creation. Even an existing empty directory or dangling link is refused; nothing is replaced or merged. Files are created exclusively; the workspace marker is written last. Failure/cancellation attempts to remove only files/directories created by that invocation, never recursively deletes the source or a pre-existing project. A process death can leave an incomplete new directory; it is not an excuse to overwrite that directory on retry. Choose another destination or inspect/remove the incomplete one yourself.

`webapp-manifest.json` contains each relative asset path, byte length and SHA-256, plus an aggregate SHA-256 over the canonical ordered `files` JSON array. It contains no absolute source paths. `.platform/webapp-package.json` keeps the source/output bookkeeping for the IDE. The manifest is an audit inventory, not a detached signature; the final APK/AAB is authenticated by Android signing.

## Wrapper security

The generated wrapper uses a dependency-free `WebViewClient` HTTPS asset handler, equivalent in role to `WebViewAssetLoader` but not requiring an AndroidX download:

- One declared local origin: `https://appassets.androidplatform.net`. Launch intents never supply a page URL.
- A manifest allowlist plus a strict, single-decode path policy; no traversal, alternate origin/port, user-info, encoded separators, backslashes, dot/hidden segments or double-encoded paths.
- Every intercepted request gets a local response, including a closed 403/404/405 response for denied requests; there is no `null` network fallback.
- JavaScript and DOM storage are enabled for the app's own bundle. No `addJavascriptInterface`, file chooser, file/content access, universal file-URL access, mixed content, pop-up windows, geolocation, camera/microphone permissions, or WebView debugging.
- No `INTERNET`/storage permission, cleartext traffic or backup. Cookies are disabled. SSL errors are cancelled.
- CSP confines subresources/connects/workers to the own origin and denies embedded frames/objects/form submission; `nosniff`, no-referrer and same-origin resource-policy headers are applied to served content. Production inline JS/CSS can run; arbitrary JS `eval` cannot.
- Service-worker requests use the same asset handler with network/file/content access disabled. Service workers are allowed for static/offline behavior, not as an embedded Node/Python server.
- Optional external HTTPS navigation requires a main-frame user gesture and a native URL confirmation. Other schemes, programmatic external navigations and redirects without a gesture are denied.

## Public integration API

```kotlin
val packager = StaticWebAppPackager()
val review = packager.review(
    WebAppPackageRequest(
        workspaceRoot = sourceWorkspace,
        staticRoot = "dist", // explicitly chosen, containing index.html
        outputParent = existingProjectsDirectory,
        projectName = "MyWebAndroid", // new directory, never reused
        applicationId = "com.example.myapp",
        appLabel = "My app",
        versionCode = 1,
        versionName = "1.0",
    ),
)
// Show review.assets, review.excluded, review.outputDirectory and review.assetManifestSha256.
val result = packager.create(review) // blocking IO; use an IO dispatcher, not the UI thread
// Explicit later action only:
backend.projects.openProject(result.projectRoot.toString())
```

The Android entry point is `dev.ide.android.workbench.webapp.WebAppPackagePanel(backend, fileActions, modifier)`. It renders only a short button row until opened, so a host can put it above a weighted runtime body. `StaticWebAppPackager.generatedProject(root)` recovers bounded local metadata when the generated project is reopened.

## Verification

Focused host verification, without an app build or downloads:

```sh
./gradlew :developer-workbench:test --tests 'dev.ide.workbench.webapp.*' --offline
```

Tests exercise real `ModelPersistence.load`, executable generated URL-policy code, Java 8 compilation of the complete wrapper against the IDE's real bundled `android.jar`, XML parsing, traversal/symlink/hard-link exclusions, secrets, limits, missing/empty/linked index, identity validation, stale review, no clobber, binary copying, source preservation, and the hash inventory.

`NativeWebAppProjectTest` also emits a unique fixture under `services/developer-workbench/build/webapp-device-fixture/run-*/projects/OfflineFixture` and prints `WEBAPP_DEVICE_FIXTURE=...`. It includes a local click counter, CSS, an SPA route, a service worker, an opted-in external HTTPS link and an excluded `.env`. Its JVM assertions alone do not establish APK/WebView behavior.

Recorded Android acceptance: three packaging tests and `WebAppBuildAcceptanceTest#buildGeneratedDebugApk` passed on the API 36 ARM64 emulator (4 KiB pages). The latter generated a fresh project, used the real on-device `assemble:app:debug` path, checked compiled resources/DEX/assets and package/version, and exported a signed `dev.fixture.offline` version 17 APK. The coordinator independently checked its signature with `apksigner`, installed/launched it, observed the bundled HTML/JavaScript, incremented its counter, and confirmed `Clicks: 1` after a forced cold restart. Evidence: `build/workbench-runtime-webbuild-device-tests-4.log`, `build/offline-web-acceptance.apk`, and `build/offline-web-persisted.png`.

This was an emulator-local debug APK, not a release AAB or Play submission. Actual 16 KiB kernels, physical Pixel execution, live external-link/service-worker/SPA behavior beyond the exercised counter page, user release-key custody, and Play acceptance are not established by these results.
