# Three-pillar execution plan and verification gates

Date: 2026-10-13. This is the execution document requested for the repository snapshot. It preserves the approved goals and distinguishes implementation from independent acceptance. The original proposal is `tripple_plaj.md`; it is not a verified compatibility or policy specification.

**Priority:** make the native reliability SDK the third pillar. The Quality Lab is its interface, not a substitute for collecting and reproducing actual Android failures.

## Verification contract

An independent observer checks actual artifacts and behavior, not the implementing agent's explanation. Each complete gate has one status: **PASS**, **FAIL**, or **BLOCKED**, plus a test result or direct observation. Leave a checkbox unchecked when only part of its gate passed.

Use ordinary tests, APK/AAB inspection, ADB, screenshots and Play Console. Do not add a reporting framework, tracking service or verification-only product code. An emulator, scripted model or local HTTP fixture cannot satisfy a gate requiring a physical device, live provider or real account. Keep source and test assertions unchanged during fail-before/pass-after runs.

## Current snapshot

Implemented code includes the consented native SDK, authenticated durable local inbox, relay and backup libraries, native/Gradle injection, incident-to-AI tooling, native failure fixtures, embedded Node/Python libraries, optional delivery packaging, semantic grouping, and manual Console entry.

Independently verified parts include:

- A native-built app on the physical Pixel captured a real Java crash, retained the original build/source identity, and delivered it to the IDE under a different UID and signing certificate.
- Real network faults and same-device backup restoration/rollback passed. Broken and fixed SQLite migrations failed and passed the same frozen preservation tests across actual APK replacement.
- Host-built projects emitted by the production app-engine packager passed 11 Python and 16 Node physical execution checks with the IDE stopped and no Internet permission. This is not proof of the IDE's complete on-device packaging UI.
- A real on-demand Python AAB validated, with engine native libraries/assets outside the base module. Real Play-delivered installation remains unverified.
- The fresh core debug APK was 196,801,028 bytes (187.684 MiB), SHA-256 `b3e10ff6e9cafe83b3a5ebe747a6b04a15013e10b2f8a83b247bcc0ab2800fa0`. All four ABIs and all referenced contents were retained. Fresh AGP packaging removed unused incremental ZIP padding; incremental artifacts had exceeded 200 MiB. Re-measure every final artifact.

No complete production gate below is declared passed merely because these parts passed. Current limitations include missing original-workspace automatic apply, missing automatic native-device verification in the AI loop, a locked physical device for UI tests, missing live account/relay/provider access, no second physical backup device and no real 16-KiB device. Node Android is explicitly unqualified upstream. Collected source archives do not grant new licensing rights.

## Execution order

1. Finish remaining code-level gaps without expanding scope: safe application of verified repairs, native verification integration, and generator/delivery integration.
2. Re-run relevant local and physical checks after each change; bind results to exact artifact hashes.
3. Complete access-dependent gates only after the owner supplies an unlocked authorized device, accounts/endpoints, consent and additional hardware. Never substitute an easier test and mark the original gate passed.
4. Commit the source snapshot and this document, push the feature branch without force, then continue the remaining work. Production publication requires its separate explicit authorization.

## 1. Embedded Play Console and Google sign-in

**Implementation:** a manually operated embedded Console, supported native/browser authorization, and an explicit browser fallback. No credential/cookie copying, user-agent spoofing, injected form automation or review bypass. An API token does not establish a Console browser session.

**Remaining:** test authenticated manual Console access through supported Google behavior. If Google blocks embedded sign-in, label embedding blocked and keep the browser handoff visibly distinct.

- [ ] **External gate:** observer sees a genuine authenticated Console operating inside the embedded view, or records BLOCKED. A page load or external browser is not a pass.

**Current status: BLOCKED.** The manual entry and navigation safety controls exist. Authenticated embedding has not been demonstrated; physical UI testing is constrained by keyguard.

## 2. Durable local-first reliability SDK

**Implementation:** extend the small Java/Kotlin runtime with bounded persistent crash/event storage, lifecycle breadcrumbs, limited previous-exit metadata, networking-client hooks, build/source identity, explicit pairing, caller validation, revocation and durable acknowledgments. Exclude credentials, bodies and personal payloads by default. Native and Gradle builds opt in explicitly. Keep production collection separate from debug fault controls and preserve Android's crash handler.

**Remaining:** finish acceptance across both native-built and dynamically instrumented Gradle-built applications, including spoof rejection and crash/replay. A dead app must restart before it can deliver its private journal; opening the IDE alone cannot read another app's private storage.

- [ ] **External gate:** physical fixture crashes while IDE delivery is unavailable; after restart its original incident appears once under the correct build, spoofed senders are rejected and planted secrets are absent. Repeat for native and Gradle injection.

**Current status: BLOCKED as a complete gate.** Native-build crash persistence and cross-UID/cross-signature delivery passed. Host security tests and actual AGP integration tests passed separately; that is not the full physical Gradle crash-delivery run.

## 3. Optional developer-owned relay

**Implementation:** the same consented events use a separate bounded encrypted outbox, authenticated HTTPS, deduplication and acknowledged retries. Local-only operation remains independent. Remote collection and cloud-AI sharing have separate consent. Revocation stops future capture/delivery for that destination without deleting unrelated local history.

- [ ] **External gate:** zero outbound telemetry when disabled; a second device sends to the developer's real endpoint after an outage without duplicate incidents; invalid credentials/certificates fail closed.

**Current status: BLOCKED.** Library and local tests pass. Real endpoint credentials, deployment authorization and remote-device acceptance are missing.

## 4. Automatic incident-to-AI repair

**Implementation:** match a received incident to its known build/source revision, require project/provider-bound opt-in, edit an isolated copy with the existing agent, bound attempts, preserve the diff and run immutable reproducer plus independent functional checks. No manual stack-trace copying or automatic production deployment.

**Code work still required:** apply a verified patch safely to the original editor/workspace; integrate an actual native-device verification executor with explicit installation permissions. The current verified-copy outcome must not be called an applied repair. Trusted local Gradle execution is same-UID execution, not an OS sandbox.

- [ ] **External gate:** a real authorized model receives a seeded crash, produces a patch, and the same untouched reproducer fails before and passes afterward while the independent functional assertion remains passing. Disabled/cancelled automation leaves the original unchanged.

**Current status: BLOCKED by code gaps and external provider acceptance.** Scripted-model and real-process safeguard tests do not satisfy this gate.

## 5. Native Android failure testing

**Implementation:** ordinary Android instrumentation and debug-only test hooks exercise activity recreation/background return, process loss, interrupted work, actual networking clients and signed upgrades/database migrations. Provide precise assertions for drafts/selections, preserved records, bounded retries and useful error behavior. Label injected faults as injected, not real carrier failures.

- [ ] **External gate:** on the physical Pixel, the unchanged fixture suite covers recreation, kill/relaunch, network failure/recovery and v1-to-v2 migration. Each seeded broken behavior fails; the corrected behavior passes without clearing data or weakening assertions.

**Current status: BLOCKED as a complete matrix.** Physical networking and fail-before/pass-after migrations passed. Activity/UI-controlled lifecycle cases remain unverified behind the device lock.

## 6. Cooperating-app cross-device backup/restore

**Implementation:** export only participating apps' selected user data through consistent snapshot hooks. Use versioned schemas, authenticated encrypted archives, portable user-controlled recovery, full prevalidation and staged transactional restore. Wrong keys, corruption and interrupted migration must preserve live destination data. Do not inspect arbitrary apps' private storage or include credentials/signing keys by default.

- [ ] **External gate:** Device A exports populated records, attachments and settings; a fresh installation on Device B restores them, including an old-schema archive. Wrong-key/corrupt/interrupted restores preserve existing destination data.

**Current status: BLOCKED.** Same-device distinct-dataset restore/rollback passed. Two physical devices and old-schema transfer acceptance remain required.

## 7. Node/Python inside generated apps

**Implementation:** separate selected native engine libraries, Java/Kotlin host APIs and a private runtime process/service; scripts and runtime assets arrive in the signed APK/AAB. Do not copy the IDE's Termux prefix into generated apps. Expose bounded I/O, lifecycle, cancellation, failure and explicit restart. Optional SDK listener integration remains explicit and consented.

- [ ] **External gate:** generated Node and Python apps run real application logic on the Pixel with the IDE stopped and no networking, retain state through supported restart/update, stop cleanly and report failures. No IDE-hosted server dependency.

**Current status: BLOCKED for full production acceptance.** Physical generated Python/Node execution and crash-recovery checks passed. IDE on-device packaging UI, broader API/ABI coverage and optional SDK-listener physical integration remain unverified. Node's Android port remains explicitly unqualified; tested restrictions are not full Node/npm compatibility.

## 8. Optional runtime weight and distribution obligations

**Implementation:** a payload-free core, explicit direct/full builds and selected on-demand Play features. Generated apps carry only their selected engine. Collect exact upstream sources, patches, build inputs and notices; validate signing, bundle structure and native alignment. No unrestricted off-Play native downloader as fallback.

- [ ] **External gate:** same-configuration core debug APK is at most 200 MiB, contains no Node/Python payload and still builds native Android apps. An explicitly selected signed engine installs and runs offline. Sources/notices match shipped binaries; production claims include a real 16-KiB device.

**Current status: BLOCKED as a complete distribution gate.** Fresh-core size/content and on-demand AAB structure passed. Real Play delivery, 16-KiB execution and licensing/release rights remain unresolved. Engine source bundles do not replace the IDE's complete source release or authorize a proprietary-linking exception.

## 9. Evidence-backed semantic grouping

**Implementation:** reuse configured AI providers with exact payload preview and per-run consent. Keep local heuristic grouping labeled as such. Enforce output schema and exact citations, preserve issues versus PRs and explicit repository scope, and never infer confirmed causes from plausible wording.

- [ ] **External gate:** independently reviewed, frozen, repository/language-stratified holdout reaches at least 80% primary-label agreement at at least 80% coverage. Keep related duplicates out of opposing splits; do not tune on holdout labels. Show subgroup misses rather than hiding them through abstention.

**Current status: BLOCKED.** Consent/egress/schema safeguards pass synthetic tests. The private corpus has not been uploaded and a consented real-provider holdout result is absent.

## 10. Live publishing and physical Play installation

**Implementation:** use the user's configured test application, upload key and authorized account. Review exact package/artifact/track before live writes. Submit only to the internal track, handle supported Console steps honestly, and leave production unchanged.

- [ ] **External gate:** observer sees the new internal version in the real Console, installs/updates through Play on the physical Pixel, verifies package/version/launch, and checks production before/after. API commit acceptance alone is not publication.

**Current status: BLOCKED.** No live Play writes were performed. Publisher account/app/signing setup and explicit live-write authorization remain required.

## Communication and final acceptance

Send short milestone updates and real blockers only; no repeated subagent acknowledgments or speculative completion claims. Final handoff separates implemented code, verified behavior, failed checks and missing access. Preserve raw test records and existing artifacts, not a new reporting subsystem.

- [ ] **External gate:** every completion claim matches a passed check on the exact final artifact. Whole gates remain unchecked when only subchecks passed.

The next implementation priority is safe, reviewed application of a verified repair to the original workspace, followed by native-device verification integration. Access-dependent tests stay blocked until their prerequisites are actually available.
