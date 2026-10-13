Research & Architecture Report: Zero-Touch Play Store Deployment & General-Purpose Runtime Platform (maceip/vivari)
Part 1: Zero-Intervention APK / AAB Submission to the Google Play Store
1.1 Why Google AI Studio’s “Publish to Play Store” Demo Is Gated / Incomplete

In Google AI Studio’s Android Build Mode (aistudio.google.com), Google demoed a “Publish to Play Store” button intended to take an AI-generated Kotlin/Compose app straight to Google Play. In practice, that feature remains gated or limited to internal tester links because Google Play enforces three structural gates that the public Google Play Developer API (androidpublisher v3) cannot bypass on its own:

Gate	What Blocks Pure API Automation	Why AI Studio & CLI Tools Hit a Wall
Gate 1: Initial App Shell Creation (HTTP 404)	Every androidpublisher v3 endpoint (/v3/applications/{packageName}/...) requires {packageName} to already exist in Play Console. There is no public applications.create REST endpoint.	A brand-new applicationId cannot be registered via REST API. Someone (or an automated browser session) must complete the initial Play Console “Create app” form and enroll in Play App Signing before the first .aab upload succeeds.
Gate 2: Mandatory “App Content” Policy Declarations (~10 Forms)	Play Console blocks promoting any release to testing/production until Policy → App content is 100% green. Only Data Safety has a public CSV upload API (POST /v3/applications/{packageName}/dataSafety).	IARC Content Rating, Target Audience / Children, Ads Declaration, App Access (login credentials), Government / Financial / Health apps, Privacy Policy URL, and Foreground Service justifications have no REST API endpoints—they only exist as Play Console web forms.
Gate 3: Post-Nov-2023 Personal Account Verification (12 Testers × 14 Days)	Personal Play Developer accounts created after Nov 13, 2023 cannot publish to production until 12 testers opt in to a Closed Testing track continuously for 14 days.	Even after Gates 1 & 2 are cleared, calling tracks.update(track = "production") fails on personal accounts until the 14-day Closed Testing gate completes. (Organization accounts with a D-U-N-S number are exempt.)

IMPORTANT

Key Insight: A true “zero-intervention” experience on Android cannot rely solely on androidpublisher v3 REST calls—it must combine automated on-device asset/compliance synthesis, the androidpublisher v3 REST API, and an embedded Play Console Co-Pilot WebView that auto-fills the one-time non-API forms in ~30 seconds.

1.2 Official androidpublisher v3 API Matrix

All transactional edits run through POST /androidpublisher/v3/applications/{packageName}/edits → mutations → POST .../edits/{editId}:validate → POST .../edits/{editId}:commit:

Capability	Public REST Endpoint?	Endpoint / Mechanism	Notes for building
Instant Play Store Install Link (0-Review)	YES	POST /upload/androidpublisher/v3/applications/{packageName}/internalappsharingartifacts/bundle	No editId commit, no versionCode bump, no policy forms required. Returns an immediate https://play.google.com/apps/test/... link within ~10 seconds.
Upload Signed .aab	YES	POST /upload/androidpublisher/v3/applications/{packageName}/edits/{editId}/bundles?uploadType=resumable	Requires Play App Signing enabled on {packageName} and a strictly increasing versionCode.
Upload R8 mapping.txt & Native Symbols	YES	POST /upload/.../edits/{editId}/apks/{versionCode}/deobfuscationFiles/proguard	Clears Play Console’s missing deobfuscation file warning automatically.
Assign Track (internal, alpha, beta, production)	YES	PUT /v3/applications/{packageName}/edits/{editId}/tracks/{track}	If Gate 2 (App Content) is not yet completed in Play Console, committing with status: "completed" fails, but status: "draft" succeeds!
Store Listing Copy (title, shortDescription, fullDescription)	YES	PUT /v3/applications/{packageName}/edits/{editId}/listings/{language}	Strict limits: title 
≤
30
≤30, shortDescription 
≤
80
≤80, fullDescription 
≤
4000
≤4000 chars.
Store Graphics (Icon, Feature Graphic, Screenshots)	YES	POST /upload/.../edits/{editId}/listings/{language}/images/{imageType}	icon (512×512 PNG), featureGraphic (1024×500 24-bit PNG/JPEG, no alpha), phoneScreenshots (2–8 PNGs, 1080×1920).
Data Safety Section	YES	POST /v3/applications/{packageName}/dataSafety	Accepts { "safetyLabels": "<CSV>" } outside of an editId transaction.
Create App Shell + 9 App Content Questionnaires	NO	Play Console Web UI (play.google.com/console)	Must be automated via building’s Guided Play Console Co-Pilot WebView or a shared Managed Publisher account.
1.3 What Already Exists in building (Ready to Wire)

building already contains ~70% of the difficult on-device infrastructure needed to build, sign, and generate Play Store assets on an Android phone without a desktop PC:

In-Process & Gradle .aab Compilation (BuildGoal.BUNDLE):
AndroidBuildSystem.kt: Compiles Baseline Profiles (CompileArtProfileTask), runs R8 (ForkedR8Shrinker, emitting layout.mappingTxt), converts resources to proto format (ConvertResourcesTask), packages the bundle (BundlePackaging.kt / Bundler.kt via BundletoolInProcess), and signs the .aab (BundleSigner.kt via ApksigBundleSigner with SHA-256 + RSA v1 JAR signatures).
GradleBuildService.kt: Maps BuildGoal.BUNDLE to :app:bundle<Variant> for full Gradle projects.
In-Process Upload Keystore Generation (Bouncy Castle):
KeystoreCrypto.kt & KeystoreRegistryOps.kt: Generates RSA-2048/4096 keypairs and 25-year self-signed X.509v3 certificates in-process on ART and binds them to [buildTypes.release] in module.toml.
512×512 Opaque Play Store Icon Generator:
AppIconGenerator.kt: Already renders ic_launcher-playstore.png as an opaque 512×512 PNG (PLAY_STORE_PIXELS = 512, opaque = true).
Headless Off-Screen Compose Surface Renderer on Android:
OffscreenComposeSurface.kt: Uses DisplayManager.createVirtualDisplay + Presentation + ImageReader to render any @Composable off-screen at arbitrary dimensions (1080×1920 for screenshots, 1024×500 for Feature Graphic) and capture PNG bytes without touching the screen!
1.4 Proposed 3-Layer Zero-Intervention Play Store Pipeline
True Zero-Intervention Mode (“Building Managed Arcade / Instant Play Testing”):
If the developer wants zero Play Console interaction (just like AI Studio’s vision), building can offer “Instant Play Preview” powered by building’s own organization Play Console account (uploading the user’s bundle as an Internal App Sharing artifact or dynamic module under a managed runner package, returning an official https://play.google.com/apps/test/... link in 15 seconds with zero forms) alongside building’s native community Store (SubmitProjectScreen.kt).
Bring-Your-Own Play Account Mode (PlayStorePublishScreen + Co-Pilot WebView):
Everything possible via API is done headlessly (dataSafety CSV, .aab, R8 mapping.txt, title/shortDescription/fullDescription, 512×512 icon, 1024×500 feature graphic, 4× 1080×1920 screenshots, track rollout, with automatic fallback from status: "completed" to status: "draft" if declarations are still pending).
For the 1-time non-API gates (initial “Create App” shell + the 9 App Content questionnaires), an embedded Android WebView opens play.google.com/console with a building Co-Pilot script (evaluateJavascript) that steps through each deep-linked declaration URL and auto-selects the radio buttons computed from static analysis of the user’s AndroidManifest.xml and dependencies—turning 45 minutes of confusing policy forms into ~60 seconds.
Solving the 12-Tester × 14-Day Personal Account Gate: Integrate a “14-Day Closed Test Guild” into building’s community Store (StoreBackend.kt) where building automatically adds the community tester pool to the developer’s alpha track and reciprocally opts developers into each other’s Closed Testing links until Production unlocks.
Part 2: General-Purpose Development Platform on Android via maceip/vivari
2.1 What maceip/vivari Already Has (And Why It Fits building Hand-in-Glove)

Inspecting your local /Users/mac/vivari and /Users/mac/vivari-perf repositories revealed three critical facts:

Vivari is a full POSIX + Node/Bun/Python/WASI operating environment in JS/Wasm:
Kernel & 53-Opcode Syscall ABI (kernel.js, syscall.js): Each guest process runs in a Web Worker with a dedicated 1 MiB SharedArrayBuffer (Atomics.wait/notify) plus a 256 KiB Debug SAB (debug.js) for hardware-trap-free Chrome DevTools Protocol (CDP) step debugging of both Node/TS and Python 3.14 (PEP 669 sys.monitoring) and instant SIGINT (Py_EmscriptenSignalBuffer at byte offset 20).
Rust/Wasm Inode VFS (lib.rs): 56 KB Wasm VFS with real inodes, hardlinks, symlinks, file descriptors, lazy zlib compression (
∼
60
%
∼60% node_modules RAM reduction), a 48 MiB HotCache, and a synthetic Ubuntu 22.04 / GLIBC 2.35 environment (/etc/os-release, /usr/bin/ldd, /etc/resolv.conf).
Real Runtimes & Compilers:
Node.js 22 (real Node lib/*.js + llhttp Wasm + Rust Wasm crypto/zlib) + real npm@10.9.2, pnpm, yarn, corepack (load-real-npm.js).
Next.js 16 RSC, Vite (React 19, Vue, Svelte 5, Solid, Lit), Express / Fastify / Hono / NestJS.
Bun (Bun.serve, Bun.build, bun:test, and bun:sqlite over @sqlite.org/sqlite-wasm + custom synchronous vivari-sync VFS) & PGlite (Postgres in Wasm).
Python 3.14 (Pyodide CPython 3.14 with ~176 ms warm linear-memory snapshot restore in vivari-perf, PEP 552 .pyc cache, WSGI/ASGI bridge for FastAPI/Flask/Django, and scientific wheels).
In-Wasm Native Toolchains: TypeScript 7 (tsgo.wasm in load-real-tsgo.js), Rust-in-Wasm (rustc.wasm + lld.wasm targeting wasm32-wasip1 in load-real-rustc.js), and NAPI-RS WASI shims (esbuild-wasm, @rolldown/binding-wasm32-wasi, @rspack/binding-wasm32-wasi, @tailwindcss/oxide-wasm32-wasi).
Vivari Already Has Two Android VM Hosts Tested on Your Pixel 11 Pro XL:
/Users/mac/vivari/vm/system-webview-android (LocalServer.java): Serves Vivari over 127.0.0.1 with COOP/COEP headers and WebViewCompat.getProfile(web).setCrossOriginIsolatedAllowlist(...) (androidx.webkit:1.18.0-alpha02), routing external network fetch() through POST /__vm/egress to bypass CORS, and exposing guest servers via LoopbackPreviewServer.java. Verified on the Pixel 11 Pro XL up to 4,096 MiB Wasm32 linear memory and 3,904 MiB JS heap (report.md).
/Users/mac/vivari/vm/wamr-android (NativeHost.java, build-txiki.sh): Standalone libtjs.so (txiki.js + QuickJS + WAMR 2.4.4, compiled with -Wl,-z,max-page-size=16384 for 16 KB page-size devices).
Vivari Already Has a ToolchainBridge Written Specifically for building (CodeAssist):
toolchain-bridge.js explicitly defines 'wasm', 'native', 'art', 'remote', and 'auto' execution modes designed to bridge Vivari's VFS with building’s native Android toolchains!
2.2 Why Combining building’s Native Bionic Prefix + vivari Solves Every Android OS Constraint

Currently, building executes Gradle and native tools via Prefix.kt, ToolchainLayout.kt, GradleToolchain.kt, and exec-prefix.c, unpacking 58 Termux aarch64 .deb archives (bootstrap-catalog.json) into <filesDir>/usr and invoking /system/bin/linker64 with LD_PRELOAD=libsdk-exec-prefix.so:libtermux-exec.so.

Challenge on Android 14/15/16/17	building Native Prefix Alone (Prefix.kt + exec-prefix.c)	maceip/vivari + building Hybrid Platform
SELinux app_data_file noexec & Play Store Policy	Requires /system/bin/linker64 + LD_PRELOAD interception on every execve(). Downloading new native binaries at runtime can trigger Play Protect / policy scrutiny if overused.	Zero execve() for Node, Bun, Python, SQLite, Postgres, Vite, Next.js, and WASI compilers. Everything runs as JS/Wasm inside WebView V8 (which has signed JIT privileges) or libtjs.so in jniLibs.
Android 12–16 Phantom Process Killer (max_phantom_processes = 32)	Spawning npm install or deep build trees (make → sh → node → git) spawns dozens of OS processes that Android kills with SIGKILL under memory pressure.	Every Vivari guest process (node, bun, python, sh pipeline) is a lightweight Worker thread inside 1 single sandboxed OS process, using 0 phantom process slots.
Glibc vs. Bionic Compatibility (npm / pip Ecosystem)	npm tools (esbuild, rolldown, rspack, lightningcss, tailwind) check ldd --version or download linux-arm64-gnu binaries that immediately crash on Bionic (libc.so.6: not found).	Vivari presents a synthetic Ubuntu 22.04 / GLIBC 2.35 VFS and transparently aliases native npm addons (toolchain-shims.js) to WASI/NAPI-RS (esbuild-wasm, @rolldown/binding-wasm32-wasi, @tailwindcss/oxide-wasm32-wasi).
16 KB Page Size (Pixel 9/10/11 Pro XL)	Every native .so and ELF in .deb packages must have 16 KB PT_LOAD alignment (p_align >= 0x4000) or dlopen/mmap fails with SIGSEGV.	WebAssembly linear memory uses architecture-independent 64 KiB pages—100% immune to 4 KB vs. 16 KB kernel page size.
Heavy Native AArch64 Compilation (Clang 21, CMake, OpenJDK 21, Kotlinc)	Fast native execution once downloaded (~400 MB of .deb packages).	Vivari’s toolchain-bridge.js delegates heavy native targets (clang++, cmake, kotlinc, javac, gradle) to building’s Prefix.kt / ForkedKotlinCompiler when native speed or arm64-v8a ELF output is needed!
2.3 Target Integration Architecture in building
Concrete Implementation Steps
Create :build-system:vivari-runtime Implementing BuildSystem & ProgramRunner:
Plugs into Build.kt and ProgramRun.kt: auto-detects package.json (Node/Bun/Vite/Next.js), pyproject.toml/requirements.txt/*.py (Python 3.14), Cargo.toml (Rust), or CMakeLists.txt (C/C++).
Uses System WebView Host (VivariWebViewHost.kt, adapted from LocalServer.java using WebViewCompat.setCrossOriginIsolatedAllowlist) as the primary engine so V8’s JS + Wasm JIT runs at full speed with zero W^X issues and isolated renderer crash recovery (onRenderProcessGone).
Two-Way Zero-Copy Workspace Sync (OP_MOUNT_IMPORT / OP_EXPORT_CHANGED):
Syncs <external-files>/codeassist/projects/<id> into /home/user/workspace inside Vivari’s Rust VFS on project open (OP_MOUNT_IMPORT = 52), streams editor buffer saves directly into the VFS, and flushes guest file writes (excluding node_modules/.venv, which stay compressed inside Vivari’s VFS/OPFS cache) back to Android disk via OP_EXPORT_CHANGED = 53.
Wire ToolchainBridge (POST /__vm/toolchain) to building’s ToolchainLayout & ART:
Connect toolchain-bridge.js directly to AndroidGradleHost.kt (clang, lld, cmake, ninja) and ForkedKotlinCompiler.kt (kotlinc, javac, d8, r8).
Tiered Runtime Packs (RuntimePackManager):
Bundled Tier 0 (~2.8 MB in APK assets): Vivari Kernel + Rust VFS/Codec/Crypto Wasm + Node.js 22 lib/ + Bun shim + npm@10.9.2 (npm-pack.bin). Every user gets instant, offline-capable Node.js, Bun, and POSIX shell with zero initial download.
On-Demand Tier 1 (Wasm packs in <filesDir>/runtimes/wasm/): pack-sqlite-wasm (~900 KB), pack-python-3.14 (~18 MB + automatic 176 ms memory snapshot), pack-tsgo (~12 MB TypeScript 7), pack-rust-wasm (~43.8 MB rustc.wasm + lld.wasm).
The Killer Bridge Connecting Area 1 & Area 2 — “Vivari Web/Full-Stack App → Native Android AAB → Play Store”:
A developer can create a React / Next.js / Vite / Svelte / Vue / Bun / Python app on their phone using Vivari, preview it live with HMR via LoopbackPreviewServer, and with one tap (“Package as Android App & Publish”), building wraps the built web bundle (or local Vivari runtime server) into a native Android WebView/Capacitor shell, compiles and signs the .aab in-process via AndroidBuildSystem + BundletoolInProcess, generates the 512×512 icon + 4 framed screenshots via OffscreenComposeSurface, and submits it to the Google Play Store via Layer A/B/C!