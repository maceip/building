# Developer workbench

> Historical first implementation snapshot. The [three-pillar execution plan](three-pillar-execution-plan.md) records the subsequent native SDK, generated-app engines, optional delivery and independent acceptance boundaries. Runtime packaging defaults and verification status below describe the earlier build, not the current complete-product gate.

The Android workbench groups three expansion areas under **Settings & Tools → Developer workbench**, also available from the editor's More menu/command palette. It is Android-first; the protocol/analysis/runtime orchestration library is a pure JVM module, `:developer-workbench`.

The original `tripple_plaj.md` was preserved. Research corrected its API/policy/runtime assumptions before implementation. Vivari was evaluated but was not required or selected.

## Three areas

### 1. Google Play publishing

Use native Google authorization or a session-only access token, build/select a signed AAB, inspect actual metadata/certificates, review listing/images/track changes, upload in a resumable edit, validate, and explicitly confirm commit. Monitor the separate Google release lifecycle. Internal sharing and Data Safety have their own confirmations because they are not ordinary edit transactions.

The implementation never creates a service-account key, stores a publisher token in a project/preferences, silently changes release status, or claims a validated/committed edit is approved/live. Google account verification, app/initial-release setup, signing-key custody, policy answers, applicable production-access application, and Google's review remain external gates. Native OAuth requires the installed IDE package/signing certificate to be configured with appropriate Cloud/Play permissions.

Details and official references: [publishing research](play-publishing-research.md).

### 2. General-purpose local runtime

Node, npm, Python and dash are delivered in the APK with pinned native dependencies. The runtime supports workspace/target selection, package scripts, arguments/environment, persistent run configuration, stdin/EOF, separate bounded stdout/stderr, cancellation, and loopback previews. Samples work without fetching runtime binaries.

Developer builds expose explicit npm installation with lifecycle scripts disabled. The default release configuration rejects arbitrary dependency downloads rather than claiming a generic native-code downloader is Play compliant. It is not Bun/Rust/Docker, full Linux/PTY, or guaranteed Vite/Next.js compatibility. Code runs under the IDE's UID: trust is required and this is not a security sandbox.

For external/shared storage, an internal copy supplies filesystem semantics. Saves copy inward on a new run; only **new output files** copy back with create-only semantics. Changes to existing source files remain conflicts for manual merge. Copy-mode live HMR is not implemented.

A separate static-web bridge reviews an explicit built output directory, creates a new native Android project, and uses the existing on-device APK/AAB pipeline. The wrapper is offline static content, with no Internet permission, JavaScript bridge, or arbitrary file access. It does not embed a Node/Python server or make SSR applications magically static.

The previous automatic 323 MiB Gradle prefix download at app launch was removed. Debug/developer distributions have an explicit toolchain setup action; default release builds disable that native download. Existing app-wide plugin/build behavior still requires independent distribution-policy review.

Details: [runtime platform](runtime-platform.md), [static web packaging](web-to-android.md).

### 3. App Quality & Reliability

The supplied archive motivated state/data correctness, UI/input regressions, lifecycle/recovery, and integration diagnostics—not another generic AI coding panel.

- Import bounded local GitHub JSON/ZIP exports; retain separate issues/PRs, canonical IDs, dates, repository coverage and confidence/evidence.
- Analyze redacted app-log snapshots or pasted logs into exception fingerprints.
- Inspect source/configuration with bounded, read-only checks and file/line evidence.
- Run actual explicit Gradle build/test/lint/bundle goals without accidentally installing/launching. Unsupported native test execution reports unsupported instead of pretending assembly is a test.
- Review and execute JSON regression scenarios against actual local runtime targets with exit/stream/loopback assertions, restart/persistence/recovery checks, cancellation, source hashes and stored evidence. Compare before/after only when the scenario identity matches and real execution completed.

The shipped classifier is a conservative offline heuristic, **not the same process as the analyst review** and not a root-cause oracle. Against 663 analyst-coded records it classified 284 (42.84% coverage), agreed on 188 of those (66.20%), and abstained on the rest. Specialized-case detection recall was 51.15%; repository scope can be explicitly marked and the UI warns that automatic filtering is incomplete. These measurements are not an accuracy guarantee or ground-truth claim.

Details: [issue research](third-pillar-evidence.md), [quality Lab](quality-lab.md), [executable scenarios](regression-scenarios.md).

## Research corpus

The attachment contained **663 unique records: 625 open issues and 38 PRs across nine repositories**. Only 383 records were created since its stated 2026-09-02 cutoff; some date back to 2018. Specialized kernel/root/profile support inflated raw platform-compatibility counts. Full and sensitivity-filtered results, PR outcome limitations, multilingual classification methodology, and representative URLs are documented separately.

The original archive, detailed issue bodies and per-record research labels are not added to the repository or embedded in the APK. The local application imports user-selected data without uploading it to an AI service.

## Verification

Host-side consolidated verification recorded:

- **134 workbench tests passed**, zero failures/errors/skips, including the opt-in original-corpus evaluation.
- **32 Gradle-build tests passed**.
- **8 focused backend delegation tests passed**.
- **6 navigation/settings integration tests passed**.
- Five runtime-pack boundary tests passed; all pinned archives/source recipes checked, native ELF load alignment audited.

Android execution uses an **API 36 ARM64 emulator with 4096-byte pages**. The initially connected physical Pixel 11 Pro XL was API 37/4096-byte pages, but disconnected before the upgraded APK's acceptance runs. Emulator results must not be represented as physical-Pixel or 16-KiB-kernel verification.

**74 Android instrumentation tests passed:** a consolidated 71-test workbench run plus three projectless-navigation regressions. No failures or skips were reported. Verified behaviors include native Node/npm/Python/dash execution, real offline npm package installation/use, package shebangs, subprocess teardown and blocked-input cancellation; real Node state persistence across restart and 429/recovery scenarios; signed publisher protocol/preflight using only loopback HTTP; publisher UI/account/lifecycle protections; original-archive 663/625/38 accounting; log redaction/grouping and read-only manifest checks; bounded SAF import helpers; static web project generation and actual native APK assembly.

Manual UI checks additionally verified Settings → Workbench without an open project, all three tabs, offline sample creation, explicit workspace trust, npm startup, embedded live Node HTTP content, and Stop. The projectless regression caught and fixed an eager `backend.project` access that otherwise threw during composition and stranded navigation.

The app's native Android pipeline also compiled and signed an actual generated wrapper APK on Android. That APK was independently signature-checked, installed, launched, and visually inspected. Its local JavaScript counter changed on tap and retained its value after a forced cold restart. It was a debug-signed local test app, not a submitted Play release.

Exact logs and generated APKs reside under the ignored root `build/` directory. No real Google Play write, account setup, publication, or tester enrollment was performed. The production `ai.building` installation on the physical phone was not replaced.

## Distribution gates

- Public binary redistribution of the newly bundled runtimes still needs exact corresponding-source and complete-license-notice auditing, especially copyleft components. Recipe references are provenance, not a blanket compliance certificate.
- APK-native delivery does not certify the whole IDE for Play policy approval.
- A real Play publish requires the user's configured account, initial app setup and upload key, and an explicit live publishing decision.
- Runtime execution is not a sandbox; do not run untrusted projects alongside credentials or valuable app-private data.

This snapshot was implemented on `feature/developer-platform-expansion`. The execution plan records the later repository checkpoint and remaining work; use Git history for commit/push provenance.
