# Three-pillar delivery tracker

Updated: **2026-10-14**. This is the single current tracker for **Play Store**, **platform support**, and **developer SDK / developer experience (DX)**. It replaces the scattered status summaries in earlier discussions; technical details remain in the linked documents. The original proposal, [tripple_plaj.md](tripple_plaj.md), and the original execution checklist are historical scope references, not proof of delivery.

**Overall: none of the three pillars is production-complete.** Some substantial, explicitly scoped capabilities are implemented and physically verified. Missing integration is recorded as missing—not hidden behind passing component tests or an access blocker.

## Focus at a glance

Current steering (2026-10-14): complete the build/test checkpoint and prioritize
the full-app packaging, bundling and configuration pass. iOS signing/provisioning
and Simulator/emulation work are deferred. The standalone xtool foundation is
merged into xtool main at `9fb63e9`; Building's Swift project integration remains
pending. The packaging checkpoint passes debug/release APK and AAB builds, release
vital lint, runtime artifact verification and 24 emulator tests. The fresh debug APK
is 416.77 MiB with the complete development runtime. Packaging ownership, precise
proof boundaries and deferred work are in [packaging.md](packaging.md).

| Pillar | Current focus | Current position |
| --- | --- | --- |
| Play Store | A trustworthy signed-app-to-internal-track-to-Play-install flow | API/UI implemented; no live publishing acceptance |
| Platform support | Real projects working from import and editing through dependencies, build, run and debugging | Hono and bundled tools proven on Pixel; integration/compatibility gaps remain |
| Developer SDK / DX | Durable local incidents connected to diagnosis, reproduction and safe repair | Runtime/PTY-to-inbox path verified on Android emulator, including real Hono failure; runtime AI repair and physical repeat remain open |

**Delivered engineering milestone: SDK-2's consented host-runtime-to-inbox path.** Build 116 passes the real Android/Hono failure acceptance below. **Primary next engineering priority: SDK-3's runtime-specific, source-bound reproduction/diagnosis path**, plus the remaining SDK-2 physical/generated-app acceptance. Do not force Node incidents through the Android signed-APK repair model or treat the manual Hono correction as AI repair.

**Next platform priority: PLATFORM-4 and PLATFORM-5.** Prove the Gradle bridge on Android and finish the complete editor/terminal/original-folder workflow. Preserve the existing Hono tests as regressions rather than adding unrelated runtimes to inflate coverage.

**Next publishing milestone: PLAY-4.** Complete a real internal-track release and install. Account readiness is PLAY-1; do not spend another iteration polishing mock publishing while implying that live acceptance has happened.

## Non-negotiable requirements

- **Node/npm development tools ship in every IDE APK.** No core-only/lightweight omission, dependency-free allowlist, or silent suppression of normal npm lifecycle scripts. The user explicitly accepts a 600 MB APK. The former assistant-originated 200 MiB target is superseded.
- The IDE development runtime and the runtime embedded in generated apps are **different deliverables**. Success or restrictions in one must not be presented as the other.
- Tests must exercise real behavior. Keep upstream tests unchanged; record production fixes and any separate test correction explicitly. A version probe or test count alone is not acceptance.
- Capture, relay, cloud-AI sharing, patch application, installation and live publishing have distinct authorization boundaries. Local project execution is same-UID trusted execution, **not a sandbox**.
- Production `ai.building` is not the development test target. Device work uses `com.tyron.code.dev` and explicitly identified disposable fixtures.
- One tracker, normal tests and existing artifacts. No extra reporting service, dashboard, tracking framework or generated status machinery.

## How status is recorded

For each item, record **implementation**, **proof**, **what is missing**, and **the next acceptance check**. A checked box means only the named acceptance check passed—not that its pillar is complete.

- **Missing / partial:** code or integration still needs work. This is engineering work, not an external blocker.
- **Implemented; unverified:** the path exists but the required end-to-end evidence is absent.
- **Verified — named scope:** identify host, emulator, physical device, fixture or live service, and the artifact where applicable.
- **Blocked — stated prerequisite:** say exactly what access, permission, hardware or supported platform capability is absent. A fallback does not pass the original gate.

Source delivery is separate: **local changes**, **committed/pushed**, **built**, and **installed** are not synonyms. No percentage-complete estimates or aggregate test counts substituting for feature acceptance.

## Prior delivery snapshot

This snapshot predates the later wave records and packaging checkpoint. Current
packaging/build evidence is recorded in [packaging.md](packaging.md); the physical
Pixel and live Play acceptance below are not replaced by emulator checks.

- **Branch:** `feature/developer-platform-expansion`.
- **Last committed/pushed checkpoint:** `1e90f501ae0a1477ca15856498a41abee192f2b1` — repair guards and launcher artwork. Mandatory full-runtime, Hono, terminal, linked-folder, Node/Gradle, backup and the new runtime-incident additions remain **local/uncommitted**. No commit/push was requested for this milestone.
- **Latest built/installed development build:** **3.28.6 (116)**, package `com.tyron.code.dev`, installed and tested on **emulator-5554, Android API36 arm64, 4-KiB pages**. This is not a Pixel delivery claim.
- **APK:** `app/ide-android/build/outputs/apk/debug/ide-android-debug.apk`, **438,876,658 bytes / 418.545 MiB**.
- **APK SHA-256:** `7dfad9c035f9ecd0cc32f3184fc42e6bc7a2413f3620faf96738dd1a4215d83f`.
- **Test APK SHA-256:** `ae9f0ec69b00411a336ce116402b7e168c6ba663a358254b9d67335f7fb92e57` (36,370,199 bytes).
- **Runtime pack:** unchanged `termux-arm64-2705683d8ec6379a`; 49 pinned packages plus pinned pnpm, 255 native payloads and 17,183 data-file hashes. Full Node/npm, Python, headers, compiler tools, CMake/Ninja and PTY remain bundled.
- **Build 116 Android evidence:** **59/59** tests, zero failures/errors/skips: six runtime-to-inbox cases, 13 real terminal cases, 15 observer contracts, eight VT parser cases, five Binder cases, two Android storage cases, nine runtime regressions and one real Hono failure/correction flow. The Hono flow executes the **same 34 tests twice**: ten expected failures with the planted HTTP-status bug, then 34 passes after source-only correction. Nested JavaScript cases are not additional Android tests.
- **Host evidence:** **110 workbench tests + two repair-origin tests**, zero failures/errors/skips in the final scoped run; includes 31 new durable-capture/origin cases. Separate engine/packager checks pass, but are not physical generated-app acceptance.
- **Independent final verification:** read-only observer matched installed app/test APK hashes and signatures, all 59 distinct Android results, all 112 scoped host cases, device-original Hono reports/receipts, the exact saved/visible incident and every runtime file/native hash. Manifest/prefix/native payloads are unchanged from build 114. Physical build 116 delivery remains blocked by connectivity.
- **Latest physical Pixel delivery remains 3.28.4 (114):** SHA-256 `d96f55158991aeea9bc474e56cff73e45870f9d374a3588b155fea9133ace8f8`, 55 Android passes including 503 upstream/34 application Hono cases. Exact prior app/test APKs were preserved under ignored `build/acceptance-artifacts/114/`; those physical results do not cover the new bridge.
- **Last production APK check:** `ai.building` remained SHA-256 `135ab0bbf49b04397275df42e0d39f0efdb6a528915b78378ef18f9160e99336`, version 1 / 1.0.0, with unchanged install/update times. This checks APK identity, not all app data or backend state.
- **Availability at this update:** ADB lists only the emulator; mDNS found no Pixel and its last authorized endpoint timed out. Build 116 was not installed on the phone. Production `ai.building` was not a target of this work; its physical APK could not be rechecked while disconnected.

## 1. Play Store

**Outcome:** select an authorized app and artifact, build/sign, prepare the release, confirm the exact write, publish to internal testing, and install the resulting version through Google Play. Manual Google requirements remain explicit.

- [ ] **PLAY-1 — Real account and application authorization.**
  **Implementation:** native Google authorization, Publisher scope, account selection and short-lived in-memory token handling exist.
  **Proof:** local/Android safeguards, not a live authorized account flow.
  **Missing / blocker:** approved test account, package, OAuth registration, Play permissions, signing identity and setup.
  **Next acceptance:** authorize the intended account and read the intended application's tracks without a live write; show exactly which prerequisites remain.

- [ ] **PLAY-2 — Signed artifact and transactional release preparation.**
  **Implementation:** signed-AAB preflight, metadata/image checks, resumable upload, edit validation, track preservation, bound confirmations and ambiguous-write handling exist. Internal App Sharing and Data Safety are separately confirmed effects.
  **Proof:** real local HTTP exchanges and Android protocol fixtures pass; the signed test fixture is not proof of a Play-valid release.
  **Next acceptance:** prepare and validate the actual approved release artifact; interruption/expiry must not cause a blind duplicate upload or an uncertain commit. Account setup and policy declarations must not be bypassed.

- [ ] **PLAY-3 — Manually usable Console boundary.**
  **Implementation:** manual embedded view, restricted navigation and explicit browser fallback exist.
  **Proof:** authenticated embedded Console access is **not demonstrated**. An API token does not sign the WebView into Console; Google embedded OAuth restrictions still apply.
  **Next acceptance:** test supported authenticated manual use. If it is blocked, record that explicitly and label the browser handoff as a fallback, not successful embedding.

- [ ] **PLAY-4 — Live internal release and Play installation.**
  **Implementation:** client/UI path exists; no live Google write has been performed.
  **Blocker:** explicit approval of account, package, artifact and internal track, plus the required account/app setup.
  **Next acceptance:** observe the new version in real Console, install/update it through Google Play on the Pixel, verify version and launch, and retain the production before/after check. API acceptance alone does not pass.

- [ ] **PLAY-5 — Distribution and policy qualification.**
  **Missing:** live feature delivery, complete applicable binary/source/notices obligations and qualification of the whole IDE's executable-code behavior. Collected recipes or an aligned ELF do not settle these issues.
  **Next acceptance:** check the exact release artifact, its corresponding source/notices and required Google policy steps. Do not silently remove the user's Node tools to make this gate easier.

Details: [play-publishing-research.md](play-publishing-research.md), [android-production.md](android-production.md).

## 2. Platform support

**Outcome:** a developer can open a real project, edit it, install its dependencies, build it, run it interactively and recover from failures—with the required tools actually installed.

- [x] **PLATFORM-1 — Always-bundled development runtime on the Pixel.**
  **Verified:** build 114 includes actual Node 24.18, npm 11.20, pnpm 11.9, Python, dash, headers, Clang/LLVM, make, CMake/Ninja and PTY support. Internet installs and ordinary lifecycle hooks ran in the actual app. Native addons compiled and loaded.
  **Maintenance gate:** repeat artifact inventory and functional checks after runtime/packaging changes. Current native development pack is arm64; this is not a promise of every ABI, desktop binary or npm package.

- [x] **PLATFORM-2 — Real Hono project acceptance.**
  **Verified:** vendored `honojs/node-server` 2.1.5 at `4b47a0d8a5c12ed69a9f8493df69918f62fb61c0`; fresh-registry installation, production build, publint/pnpm, typecheck and **503 unchanged upstream tests** pass on the Pixel. The separate repository-owned Hono dashboard passes **34 tests** for persistent CRUD, auth, validation, uploads, streaming and recovery.
  **Maintenance gate:** keep pinned upstream inputs and before/after source hashes. Record package/framework failures, not just green counts. These are real-project coverage, not universal Node compatibility.

- [x] **PLATFORM-3 — Real interactive terminal.**
  **Verified:** 15 physical Android terminal cases cover VT parsing, actual TTYs, Node REPL editing, control keys, resize, shell/npm execution, IME/rendering, process cleanup and bounded clipboard handling.
  **Limits:** ordinary script runs still use pipes; Terminal is a separate action. Older systems without pidfds show a job-cleanup limitation. Full Linux userspace, Bash, arbitrary fullscreen applications and background-survival guarantees are not established.

- [ ] **PLATFORM-4 — Node project and Gradle workflow integration.**
  **Implementation:** normal Node recognition/import/editor tree, host-authorized in-place project roots, a current-project **Run Node project** route, safe script defaults and Node/npm/pnpm bridges for trusted Gradle commands exist.
  **Proof:** 11 Node integration tests, six bridge-selection tests and real host Gradle Exec/native-addon tests pass. Emulator UI opened and built a real Hono project through the PTY.
  **Missing:** the Android Gradle bridge has not passed its real installed-Gradle acceptance; the phone lacked that toolchain. Generic JVM/Android Run is not Node execution; JavaScript/TypeScript language intelligence is not provided by this importer.
  **Next acceptance:** with the approved on-device Gradle toolchain, run real Gradle Node/npm commands and a native dependency while retaining Java/runtime environment separation; verify open/edit/save/run uses the same root from the visible UI.

- [ ] **PLATFORM-5 — Linked external folders and complete user workflow.**
  **Implementation:** persistent SAF grant, shared internal editor/runtime working root and explicit bidirectional sync to the original folder. Baselines, backups, no-delete behavior and conflicts protect original files.
  **Proof:** all 28 physical sync/provider contract tests pass. The emulator UI opened Node projects, used the actual terminal for npm install/build/start, and served Hono; browser form handlers created a persisted task and received SSE.
  **Missing / limits:** arbitrary real document providers and the complete mobile click/accessibility flow are not certified. Provider rename support and Android 11+ are required for the implemented safe replacement/pull paths. SAF is not direct POSIX access or cross-process compare-and-swap; save/close buffers before sync.
  **Next acceptance:** edit an actual linked Hono folder, save back through a real provider, reopen it, then reproduce conflicts, permission revocation and interrupted sync without losing either version.

- [ ] **PLATFORM-6 — Node/Python embedded in generated apps.**
  **Implementation:** separate app-owned engine libraries, host APIs and private runtime services exist; optional application-engine delivery is distinct from mandatory IDE tools. Explicit optional SDK/listener inputs now generate a real debug-only listener registration; capture also requires an already initialized, consented app SDK. Release source/dependencies retain the plain engine client.
  **Proof:** earlier generated Python and Node apps ran real logic offline on the physical Pixel with the IDE stopped; 11 Python and 16 Node checks passed on those artifacts. New host checks compile actual SDK/listener jars and both generated variants, including release compilation without SDK classes. Current phone UI/CLI does not yet supply the optional inputs; this is an implemented generation API, not phone end-to-end failure reporting.
  **Missing / limits:** full on-phone generation/package/update flow and broader modules/ABIs remain unverified. The embedded Node engine still restricts addons, child processes, workers and inspector; IDE runtime success does not remove those restrictions.
  **Next acceptance:** generate, build, install, update and run each app entirely through the phone workflow with its exact selected engine and failure reporting.

- [ ] **PLATFORM-7 — Compatibility and maintenance.**
  **Proof:** staged ELF alignment checks and the tested 4-KiB Pixel configuration, not real 16-KiB hardware.
  **Missing:** explicit supported ABI/module/package matrix, real 16-KiB acceptance, broader Python/pip/package compatibility and versioned-runtime storage maintenance. Repeated test installations accumulated old runtime prefixes; deleting test-owned copies was a manual test cleanup, not shipped automatic cleanup.
  **Next acceptance:** test each advertised configuration and maintain reproducible sources/patches/notices; unsupported cases remain clearly labeled. Never replace failed compatibility with a smaller feature set without discussing it.

Details: [runtime-platform.md](runtime-platform.md), [upstream Hono](../samples/hono-node-server/BUILDING.md), [Hono dashboard](../samples/hono-workbench/README.md).

## 3. Developer SDK / DX

**Outcome:** a real failure produces a consented, durable, correctly attributed local incident; the developer can reproduce it and, when opted in, use AI to patch and verify it safely. Diagnostics, failure testing and portable data recovery must connect to actual apps—not only isolated library tests.

- [ ] **SDK-1 — Durable app SDK and authenticated local delivery.**
  **Implementation:** explicit collection consent, bounded durable journal, normal crash-handler delegation, lifecycle/network hooks, limited previous-exit metadata, build/source identity, signed pairing, caller validation, revocation and durable receipts exist in native/Gradle paths.
  **Proof:** native-built fixture crash persistence and cross-UID/cross-certificate delivery passed on Pixel. Security and actual AGP integration tests passed separately.
  **Missing:** full physical crash/replay/spoof/redaction acceptance repeated for dynamically instrumented Gradle builds.
  **Next acceptance:** crash with IDE delivery unavailable, restart the target, receive the original event once with correct provenance, reject spoofed senders and planted secrets. Opening the IDE alone cannot read a dead app's private journal.

- [ ] **SDK-2 — Runtime-to-SDK observability — HOST PATH VERIFIED; FULL ITEM PARTIAL.**
  **Implemented:** the production runtime controller and PTY route structured session outcomes through a device-private, default-off, per-workspace consent registry and bounded durable outbox into the existing local incident inbox. Host-runtime origin cannot impersonate a signed SDK application. Records contain installed IDE/pack identity, random workspace/session identity, a versioned before-run source hash when available, stage/category/exit/duration—not paths, commands, environment, source contents, terminal output or exception messages. Success and explicit Stop/cancellation create no incident; nonzero exit is not called a crash. Revocation invalidates active grants and queued work, including disable/re-enable races. Strict storage refresh recovers valid snapshots after uncertain writes without clearing data.
  **Verified — build 116 emulator/host:** all 59 Android cases pass, including genuine missing-executable launch failure, an actual program exiting 127, terminal-owned shell failure, default-off behavior, live revocation, source/privacy checks and disk reconstruction after a committed inbox/lost receipt. The actual Hono app installs from a fresh public-registry cache, fails its unchanged suite on a seeded 201→200 source bug, saves exactly one incident, and passes all 34 tests after restoring only source. The prior incident survives reopen. Real UI navigation after app restart displays the matching Hono record, its source/session/pack identity and no AI-repair action. Final scoped host regressions and independent static review pass.
  **Failure found and fixed:** build 115 had 57/58 passes because the process-group launcher returned 127 when its target was missing. Build 116 preflights missing/nonregular/nonexecutable targets and retains an independent exit-127 regression. No test was weakened and no stderr parsing was added. Review also fixed eager inbox initialization blocking the outbox, ambiguous consent status, recovery after transient writes and uncaught inbox read errors.
  **Still missing / next acceptance:** repeat on the physical Pixel; finish full on-phone generated-engine SDK wiring and failure reporting; add any advertised runtime timeout/crash protocol with real evidence. Current observations cannot distinguish an uncaught project exception, test assertion or expected nonzero code, nor identify individual terminal commands. Pre-run source identity does not attest later edits, dependency state or internal-copy content. Runtime-specific AI reproduction/repair remains SDK-3—not an authorization inherited from capture. This broader checkbox stays open.

- [ ] **SDK-3 — Incident-to-AI diagnosis and verified repair.**
  **Implementation:** authenticated incident/build lookup, provider-bound sharing consent, isolated editable copy, immutable reproducer/functional tasks, bounded attempts and retained diff exist.
  **Proof:** scripted-model safeguards and actual task-process tests, not a complete real-provider phone loop.
  **Missing:** runtime-specific source/execution provenance, separately approved immutable reproduction/verification tasks and consented provider handoff; unattended/live-provider acceptance tied to actual runtime/native incidents; a supported native-device verifier. The new host-runtime origin is explicitly excluded from the existing Android signed-build adapter. Hono's manual fixture correction proves capture/reproduction, not AI repair.
  **Next acceptance:** introduce a known bug, capture it, observe the opted-in provider diagnose/patch the copy, and have the same untouched reproducer fail before/pass after while independent functionality passes. Revocation/cancellation must not modify original files.

- [ ] **SDK-4 — Safe original-workspace application and device actions.**
  **Implementation:** guarded single-existing-file apply for supported quiescent in-process hosts exists. Exact plan/input/source/editor checks and actual worker-completion guards prevent unsafe writes. Android remote/external-Gradle hosts retain verified copies when shutdown cannot be attested.
  **Proof:** scoped host tests, physical atomic-rename primitive tests and independent static review; not general multi-file/live-editor application.
  **Missing:** real remote-build shutdown acknowledgment, transactional multi-file/live-buffer editing, and independently authorized artifact/install/launch completion.
  **Next acceptance:** no partial original patch, lost edit or false installation-complete result during cancellation, project switches, concurrent saves or process failure. Native cross-app instrumentation is restricted on stock Android; installer consent does not grant shell/system privileges. A supported external executor remains separate from the ordinary phone app.

- [ ] **SDK-5 — Native failure/recovery matrix.**
  **Implementation:** native fixtures cover networking, state, lifecycle and signed upgrades/migrations.
  **Proof:** physical network failures/recovery and seeded-broken versus fixed migrations passed. Full recreation/background/kill/interrupted-work matrix is not complete.
  **Next acceptance:** run unchanged native tests for each seeded failure and correction without clearing data or weakening assertions; label injected faults and external ADB tooling accurately.

- [ ] **SDK-6 — Cooperating-app portable backup/restore.**
  **Implementation:** selected-data hooks, consistent snapshots, schema versions, authenticated encrypted archives, recovery keys/passphrases, staged restore and document-stream support. A cooperating debug sample has real export/restore UI and replacement consent; unselected data is preserved.
  **Proof:** 59 SDK JVM tests, including 16 backup tests; five Android emulator document/restore tests pass. The sample is not arbitrary-app private-data access.
  **Missing / blocker:** complete system-picker transfer and older-schema recovery between two physical devices; only one physical target has been available.
  **Next acceptance:** transfer populated records/settings/attachments to a fresh second device; wrong key, corruption and interruption leave its previous data intact.

- [ ] **SDK-7 — Optional developer-owned relay.**
  **Implementation:** separate opt-in, bounded encrypted outbox, HTTPS/authentication, acknowledgment/deduplication and revocation exist.
  **Proof:** local/library tests only.
  **Blocker:** authorized developer endpoint/credentials and remote-device access.
  **Next acceptance:** no outbound telemetry when disabled; outage/retry delivers once to the real endpoint, invalid credentials/certificates fail closed, and deletion/disconnect works. Cloud-AI consent remains separate.

- [ ] **SDK-8 — Evidence-backed triage.**
  **Implementation:** offline heuristic and consented structured provider/citation workflow exist.
  **Proof:** privacy/egress/schema tests; no qualified independent corpus result.
  **Missing / blocker:** frozen independently reviewed repository/language holdout and explicit provider/payload approval for private evidence.
  **Next acceptance:** at least 80% primary-label agreement at 80% coverage, with source citations and subgroup misses. Analyst-reviewed labels must not be presented as shipped-classifier performance. This is subordinate to actual incident capture and repair, not a substitute for them.

Details: [quality-lab.md](quality-lab.md), [third-pillar-evidence.md](third-pillar-evidence.md), [regression-scenarios.md](regression-scenarios.md).

## Delivery and evidence that cross all pillars

- [ ] **DELIVERY-1 — Repository matches tested delivery.** The latest runtime/integration code is installed but uncommitted. Next: review the current diff and checkpoint/push when requested; record the commit against the rebuilt APK. Documentation changes in this turn do not authorize a push.
- [x] **DELIVERY-2 — Preserve named build 114/116 acceptance evidence.** Exact prior build 114 APKs and final build 116 app/test APKs, host JUnit XML, Hono before/after reports/receipts and visible-inbox evidence are retained under ignored `build/acceptance-artifacts/114/` and `/116/`. Commands/output and hashes are anchored below. This is local evidence preservation, not committed-source delivery, a full repository test pass or release qualification. Repeat for the next accepted artifact.
- [ ] **DELIVERY-3 — Release qualification.** Whole-IDE license/source requirements, Google policy qualification, credentials and actual distribution require their own checks. Recipe/source archives are evidence inputs, not a new licensing exception.

### Current evidence anchors

- Build 114 physical acceptance: 55 Android passes, independently counted, in the existing session output `call_S9IyGjlif0VJ9tGKXmc1tpeF-stdout.log`.
- Upstream Hono device report: `build/hono-pixel-62f79774-fccf-4f9c-a460-aa2c6a95f505-upstream-junit.xml` — 503 cases, zero failures/errors/skips.
- Owned Hono device report: `build/hono-pixel-139cb3fd-0bda-4a2e-a1a8-b6a51ec46f6b-application-junit.xml` — 34 cases, zero failures/errors/skips. The added drain regression was corrected to use event ordering instead of desktop timing assumptions; the original 33 assertions stayed intact.
- Real Pixel CMake/Ninja addon build: existing session output `call_xOEPfOZW6QdyKSvLujqb4PUw-stdout.log`, ending `CMAKE_NINJA_NATIVE_ADDON=42`.
- Normal-import and bridge tests: `NodeProjectIntegrationTest`, `NodeProjectBridgeTest`, `GradleNodeExecTest`, `GradleScriptEnvironmentTest`, `GradleBuildSystemTest`.
- Build 116 final Android run: `call_MSybs6ZkqDBIWanHGdXBMhm4-stdout.log` — 59 distinct terminal passes, zero failures/errors/skips. Build 115's retained `call_WcXehNb201aVEfrCxdFpD1DJ-stdout.log` records the original missing-executable failure.
- Build 116 Hono evidence: `build/hono-304ba08e-d6bd-4fc5-9a39-ab8f115c3b36-116-before-junit.xml` (34 cases, ten seeded failures, health case passes), the matching `-116-junit.xml` (same 34 identities, all pass), and matching before/verify/ci receipts. Test/harness hashes are identical across correction; execution IDs and source hashes differ.
- Visible restarted-app inbox: `build/building-sdk2-inbox.xml` and `build/building-sdk2-inbox-116.png`; Hono session `2f8c71b6-61b1-4f95-9e2f-f4268b63e421`, workspace `069fd2f6-7de7-4caa-80c7-8efef45db150`, exact build 116 metadata.
- Final scoped host run: `call_Xrx2aV7d63E6bLCSKFyzexya-stdout.log`, current workbench/core JUnit XML (110 + 2 cases). Separate `tools/runtime-engine/run_host_tests.py --android-jar app/ide-android/src/main/sdk/android.jar` passes 31 engine/listener/packager/Python checks; its eight packager cases overlap the workbench run and must not be summed as unique coverage.

Build outputs/logs are local test artifacts, not committed project tracking files. Preserve relevant raw evidence for releases; missing old artifacts must be labeled unavailable rather than reconstructed into an invented pass.

## Update discipline

1. Update the applicable item **in this document** after each meaningful implementation, failure, acceptance run or blocker change. No second tracker.
2. Every milestone update states: **what changed; what actually passed and where; what still fails/is missing; whether it is local, pushed or installed**.
3. Report a newly found limitation when discovered, not at the final handoff. Separate a genuine platform/account boundary from code we have not implemented.
4. Do not mark unfinished engineering as externally blocked. Do not let unavailable live credentials/hardware stop independent local work.
5. I own engineering, integration and evidence collection. The user retains decisions over live accounts/artifacts/destinations, cloud-data consent, scope changes and destructive operations. Never invent permission or delivery dates.
6. The Hono demonstration now proves **real platform execution, durable local incident capture, source-only manual correction and unchanged functional verification** on build 116's emulator. The **opted-in AI diagnosis/repair step is still missing**; it must not be implied by this manual correction. Play delivery remains separately authorized and unverified.

### Scope continuity

The original ten gates remain represented: Console (PLAY-3), SDK delivery (SDK-1), relay (SDK-7), AI repair (SDK-3/4), native failure tests (SDK-5), backup (SDK-6), generated runtimes (PLATFORM-6), runtime delivery/distribution (PLATFORM-1/7 and PLAY-5), semantic triage (SDK-8), and live Play install (PLAY-4). No unfinished original gate was silently dropped. The explicit mandatory-runtime/no-size-cap correction supersedes only the earlier core-only/200 MiB requirement.

## Course to production complete

Production complete means all three pillar thresholds pass on real target systems; no pillar threshold substitutes for another pillar threshold:

- **Play Store:** a signed bundle completes upload, release, and installation from a real Play track.
- **Platform support:** a real project completes import, editing, dependency resolution, build, run, and debugging on a physical Android device.
- **Developer SDK:** an incident captured inside a deployed application reaches the IDE, produces a diagnosis, and supports a verified repair.

### Starting point

- Build 116 (`3.28.6`, `com.tyron.code.dev`) passes 59/59 Android cases on emulator-5554 and 112 scoped host cases, including the SDK-2 host-runtime-to-inbox path and the seeded Hono failure/correction flow.
- PLATFORM-1, PLATFORM-2, and PLATFORM-3 are checked complete for the named Pixel scope (bundled runtime, Hono 503 + 34 tests, interactive terminal).
- Open delivery state: build 116 code remains local and uncommitted (DELIVERY-1), and build 116 has no physical Pixel installation.

### Phases

**Phase 0 — Freeze the tested source (blocks every later phase).** Review the local diff, commit and push the build 116 source, rebuild, and record commit hash against APK hash. Exit evidence: clean tree, commit identifier, rebuilt APK SHA-256.

**Phase 1 — Physical-device repeat.** Install the Phase 0 build on the Pixel. Repeat SDK-2 acceptance on physical hardware, PLATFORM-6 full phone generation/package/update/run for Node and Python generated apps, and PLATFORM-5 linked-folder save-back through a real document provider. Exit evidence: named-device test reports plus before/after source hashes.

**Phase 2 — Platform completion.** Pass PLATFORM-4 (real installed-Gradle Node/npm/native-addon commands on Android), PLATFORM-5 full workflow (edit, save-back, conflict, revocation, interrupted sync without data loss), and PLATFORM-7 (supported ABI/module matrix and real 16-KiB page-size acceptance; unsupported configurations stay labeled unsupported).

**Phase 3 — Live Play completion.** Complete PLAY-1 (authorize the intended account, read tracks without a write), PLAY-2 (validate the actual approved signed artifact with interruption/expiry handling), PLAY-4 (publish to internal testing, install through Google Play on the Pixel, verify version and launch), PLAY-3 (demonstrate authenticated Console use or record the browser handoff as fallback), and PLAY-5 (artifact, source, notices, and policy qualification on the exact release artifact). Account, artifact, and track approval remains a user decision.

**Phase 4 — SDK closed loop.** Pass SDK-1 physical crash/replay/spoof/redaction acceptance, SDK-3 (known bug → consented provider diagnosis → patch on an isolated copy → untouched reproducer fails before and passes after), SDK-4 (no partial patch or false install-complete under cancellation and concurrent saves), SDK-5 native failure matrix, SDK-6 two-device backup transfer, SDK-7 real relay endpoint delivery (only with an authorized endpoint), and SDK-8 triage at 80% agreement at 80% coverage on a frozen holdout.

**Phase 5 — Release qualification (DELIVERY-3).** License, source, notices, credential, and distribution checks on the exact Phase 3 artifact. Production complete is declared only when Phases 0–5 exit evidence exists.

### Consolidated task execution review items

Review each item R-01 to R-10 in order; an item passes only with the named evidence, never with a test count alone. R-05 can proceed in parallel with R-01 and R-02 because PLAY-1 requires only read access.

- **R-01 Source freeze:** diff reviewed, commit pushed, APK rebuilt, hashes recorded (covers DELIVERY-1).
- **R-02 Physical baseline:** build installed on Pixel; SDK-2, PLATFORM-5, and PLATFORM-6 acceptances repeated on physical hardware (covers SDK-2 remainder, PLATFORM-5, PLATFORM-6).
- **R-03 Gradle bridge:** PLATFORM-4 acceptance with the approved on-device toolchain.
- **R-04 Compatibility matrix:** PLATFORM-7 ABI, module, and 16-KiB results recorded per configuration.
- **R-05 Play authorization:** PLAY-1 read-only track verification under the intended account.
- **R-06 Live release:** PLAY-2 validation, PLAY-4 publish-and-Play-install, PLAY-3 Console disposition (covers PLAY-2, PLAY-3, PLAY-4).
- **R-07 Policy qualification:** PLAY-5 and DELIVERY-3 checks on the exact release artifact.
- **R-08 Incident integrity:** SDK-1 crash-once delivery, spoof rejection, and secret-plant rejection on physical hardware.
- **R-09 Repair loop:** SDK-3 before/after reproducer result and SDK-4 cancellation/concurrency safety, with original files unmodified on revocation.
- **R-10 Data and relay:** SDK-5 matrix, SDK-6 second-device restore, SDK-7 real-endpoint once-only delivery, SDK-8 holdout thresholds.

First action: execute R-01, then R-02.

### Wave execution record (2026-10-14, local/uncommitted)

- **Wave 0 — partial, commit checkpoint pending.** Diff inventoried: 102 status entries (58 modified, remainder untracked runtime/terminal/SDK additions plus Hono acceptance-test scaffolding) on `feature/developer-platform-expansion` at `1e90f501`. Hono clarification: `samples/hono-node-server` and `samples/hono-workbench` are repository-owned test environments that exercise the real on-device Node runtime; Hono is not an application feature and no Hono code ships as an app capability. Scoped host evidence after a forced no-cache run: developer-workbench 255 tests, gradle-build 50 tests, applog-runtime 59 tests; zero failures/errors; two skips are the external-holdout gates requiring supplied data (SDK-8). One flaky failure observed and not hidden: `RuntimeControllerTest.observerMarksStopIntentBeforeTerminateCanReturnAnExitCode` timed out once at 5000 ms under full `--rerun-tasks` rebuild load, then passed isolated (26/26) and in a fresh full-suite run. Rebuilt debug APK: 438,877,966 bytes, SHA-256 `815d3f161b76df181da64bc928facb5d7707515a91957bf2b19e1c83ad2652ab` (supersedes build 116 APK `7dfad9c0…` after the Wave 1 packager change). Standing user authorization (2026-10-14): commit and push at every wave checkpoint without waiting for per-freeze approval; secret prevention belongs to the ignore/pre-commit layer, not to commit authorization. Wave 0 freeze executed under the standing authorization as grouped commits on `feature/developer-platform-expansion`.
- **Wave 1 — Defect 5 verified, no wording change required.** Current user-facing text already states same-UID trusted execution is not a sandbox: `RuntimePanel.kt`, `RuntimeController.kt`, `RuntimeModels.kt`, `EnginePackPanel.kt`, `AppEngineModels.kt`, `docs/runtime-platform.md`, `docs/developer-workbench.md`. The only "sandboxed" capability claim sits in historical `docs/tripple_plaj.md`, already labeled a historical scope reference by the tracker header.
- **Wave 1 — Defects 3/4 fixed on host.** New `AppEngineSourceCheck` runs inside `AppEnginePackager.create` before staging: Node `child_process`, `worker_threads`, `inspector`, and `.node` addon references, plus Python imports of profile-excluded modules (`ctypes`, `multiprocessing`, `sqlite3`, `tkinter`, `curses`, `bz2`, `lzma`, `zstd`, `pip`, and private variants), now fail packaging with a named unsupported-capability error and create no destination. Maintained test `node child process and python excluded module fail packaging with named capability` failed before the change and passes after; packager suite 9/9 green, bundled engine-check fixtures produce no false positives.
- **Wave 1 — Defect 9 host scope verified.** Planted-secret redaction (`PrivacyAndProtocolTest`), sender authentication/spoof rejection (`IncidentReceiverTest`, `PairingRegistryTest`), and crash/journal replay (`CrashCaptureTest`, `DiskJournalTest`) pass in the 59-test applog-runtime suite and the workbench reliability suites. Physical and dynamically-instrumented Gradle repetition remains Wave 2 scope under SDK-1.
- **Wave 2 — started; bridge test defect fixed, hardware gates open.** Running `GradleNodeBridgeDeviceTest` on emulator-5554 exposed a blocking test defect: expression-body `runBlocking` returned a non-Unit value, so JUnit4 rejected the class with `InvalidTestClassError` before any bridge code executed. Fixed to a block body (assertions unchanged, commit `fa7df546`); the test now executes and fails only at the explicit prerequisite: an already-installed Gradle distribution must be supplied on-device (`installedGradle` argument), which no attached device provides. Attached devices at Wave 2 start: emulator-5554 and emulator-5556 only; no physical Pixel. Remaining Wave 2 gates: physical Pixel baseline repeat, on-device installed-Gradle bridge acceptance (PLATFORM-4), real 16-KiB hardware (PLATFORM-7). Defect 6 assessment: host-runtime records deliberately carry no output or exception text, and canonical incident text already states "Automatic repair is not eligible"; distinguishing exception versus assertion versus expected nonzero exit requires output capture the privacy model forbids, so Wave 2 records the distinction limit as design-confirmed rather than code-changed.
- **Wave 2 — emulator device evidence (2026-10-14, emulator-5554, API 36).** Fresh connected runs against the pushed tree: RuntimeNativeAddonTest + RuntimeIncidentDeviceTest 7/7, ExternalWorkspaceSyncTest + RuntimeDeviceTest 32/32, SafExternalDocumentTreeTest 5/5, RuntimeHonoIncidentTest 1/1 and HonoProjectAcceptanceTest 2/2 with explicit `allowHonoRegistryInstall=true` consent (the no-consent run fails by design with "must not silently skip"; acceptance skips without consent are assumption-gated, not passes). Total 47 emulator device tests, zero failures. Emulator evidence does not close the physical Pixel, installed-Gradle (PLATFORM-4), or 16-KiB (PLATFORM-7) gates. PLAY-1 groundwork: read-only authorization surface verified in code (`PlayAuthorization.kt` publisher scope, native/manual token handling, OAuth registration read); live PLAY-1 requires the intended Google account sign-in, OAuth registration for the release package/SHA-1, and Play Console permissions — user-supplied prerequisites, no live call made.
- **Wave 2 — physical Pixel baseline (2026-10-14, Pixel 11 Pro XL `67080DLDV009AD`, Android SDK 37, 4-KiB pages, USB).** Fresh Gradle-installed runs against the pushed tree: RuntimeNativeAddonTest + RuntimeIncidentDeviceTest 7/7, ExternalWorkspaceSyncTest + RuntimeDeviceTest + SafExternalDocumentTreeTest 37/37, HonoProjectAcceptanceTest 2/2 (61.8 s, consent argument supplied), RuntimeHonoIncidentTest 1/1 (Gradle runner; a direct `am instrument` attempt against the stale build-114-era test APK failed class-loading and is superseded by the Gradle-installed run). Total 47 physical device tests, zero failures — SDK-2 physical repeat portion closed. PLATFORM-4 still requires the on-device installed-Gradle distribution argument; PLATFORM-7 16-KiB still requires 16-KiB hardware/mode (Pixel reports 4096-byte pages in current mode).
- **Consolidation merge (2026-10-14).** `origin/macprimegradle-ndk-7d9e` (`fdea3b32`, NDK linker/sysroot) merged into `main` as `5e745605`; conflicts in `GradleHost.kt`, `GradleToolchain.kt`, `ProjectVersions.kt`, and `on-device.init.gradle` resolved by integrating both evolutions (script-environment + reliability flow with CMake fallback retry, sysroot extras, and `minSdk` scan). One integration break fixed during the merge: the HEAD CMake `path` guard threw on property-less CMake blocks; the guard now tolerates a missing `path` property. App icons replaced at all densities with the supplied power-device artwork (`47da4bf0`, lossless WebP). `main` pushed to origin at `47da4bf0`; every origin branch is contained in `main` (`feature/developer-platform-expansion`, `macprimegradle-on-device-plan-593d`, `macprimegradle-ndk-7d9e`, plus the former `investigate_ghostty_web_terminal` head). Host evidence on the merged tree: workbench 259, gradle-build 59, applog-runtime 59 green; ide-core 847 with 6 failures — 4 `CodeActionsTest` failures proven pre-existing at pre-expansion base `eba7052d` (8 tests, 4 failures in a clean worktree) and 2 full-suite-only failures (`BuildSystemExtensionTest`, `SemanticEgressGuardTest`) that pass isolated. Pixel evidence on the merged tree: 47/47 device tests (44 connected + RuntimeHonoIncidentTest 1/1 + HonoProjectAcceptanceTest 2/2). Upstream `tyron12233/CodeAssist` main is not merged; upstream merging remains a separate decision.
- **Upstream merge and CodeActions root cause (2026-10-14).** Upstream `tyron12233/CodeAssist` (4 commits: Release 3.27.1, ResolveCache parse-retention fix, sync ANR fix, badge docs) merged into `main` as `69c72620`; the single conflict kept the release-configuration system with upstream 3.27.1/versionCode 107 recorded as the floor. Every remote branch (origin feature/plan/NDK branches and upstream) is now contained in `main`. CodeActions regression root-caused by bisect (`5ee2427d` pass, `f673915a` real-execution pass, `eba7052d` fail): the `eba7052d` branding rename changed `SampleProject` banner text `CodeAssist` → `building`, so four `CodeActionsTest` fixture replacements silently no-oped and every scenario collapsed to an empty action list. Fixtures updated to the current sample text with assertions unchanged; `CodeActionsTest` 8/8 green. The ide-core full suite (849 tests, 132 classes) additionally shows load-sensitive, suite-only failures in index-dependent tests (`XmlLayoutInspectionE2ETest`, `AndroidCustomViewCompletionTest`, `BuildSystemExtensionTest`, `SemanticEgressGuardTest` in varying subsets; every victim passes isolated at 16/16). `forkEvery = 10` in `app/ide-core/build.gradle.kts` bounds shared-JVM IntelliJ/index state: observed one clean 849/849 run and one single-victim run, so the suite gate is recorded as load-sensitive rather than deterministic. Final-tree Pixel evidence after the upstream merge: 47/47 (44 connected + RuntimeHonoIncidentTest 1/1 + HonoProjectAcceptanceTest 2/2). Remaining external gates unchanged: PLATFORM-4 on-device installed-Gradle distribution, PLATFORM-7 16-KiB page-size mode/hardware (Pixel reports 4096), PLAY-1 live account/OAuth/Console prerequisites.
- **Flaky timeout hardened (same session).** Root cause: the fake `terminate()` and the surrounding `stop()` in `observerMarksStopIntentBeforeTerminateCanReturnAnExitCode` used nested equal 5000 ms deadlines; under full-suite rebuild load the exit-delivery coroutine starved past the inner deadline. Both deadlines now use one load-tolerant 30-second budget; classification, stage, and exit-code assertions are unchanged and no production code changed. Verified: isolated class 26/26 green and two consecutive full `--rerun-tasks --no-build-cache` suite runs at 255 tests with zero failures.
