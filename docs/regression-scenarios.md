# Executable local regression scenarios

Quality & Reliability Lab has a bounded **executor**, separate from archive classification and project scanning. A scenario is a reviewed local JSON file. It starts an explicitly selected Runtime target, executes assertions, stops its owned process/preview, and stores evidence. Finding a keyword in an issue or scanning a project never starts a scenario or generates a script.

## What runs, and what does not

Supported targets are the Runtime controller's `node:<relative-file>`, `python:<relative-file>`, `npm:<script-name>`, and `static:<relative-file>` targets. A `command` step waits for a real process exit and checks its **separate** stdout/stderr. A `start` step starts a server/preview; `http` checks its owned loopback origin; `stop` terminates it. Repeating `start` after `stop` performs an actual process restart, not a simulated label change.

This capability does **not** rotate, background, relaunch or inspect arbitrary installed Android apps. It does not automate keyboards, accessibility, root, permissions, connectivity toggles, or real provider accounts. Native Android lint and test tasks belong to the separate Gradle test-task capability. Static HTML HTTP assertions do not execute browser JavaScript or verify rendering. A fixture returning 429 demonstrates a local error/recovery contract, **not** a provider outage or a fix to a real provider integration.

**Project execution is user-trusted code, not an OS sandbox.** A Node/Python file or npm script can itself access the app's files, execute other code, or make network requests. JSON cannot supply a shell command, environment, arguments, arbitrary executable, dependency install, custom header, or an absolute HTTP URL. The runner's own HTTP client is loopback-only; this does not restrict arbitrary trusted project code. Review the selected target, npm script and local `.building/runtime.json` as well as the scenario.

## Android flow

The small `RegressionScenarioPanel` is intended as a Quality-tab dialog/body, not another screen scaffold.

1. Use the current backend project, or choose **Create offline demo**. Demo creation allocates a **new app-private child workspace** and never replaces existing project files.
2. Enter a normalized project-relative `.json` path and choose **Review scenario**.
3. Inspect the step summary, limits, scenario SHA-256, and source-artifact hash. Open the JSON/source in the editor when reviewing payload and expected values; the summary deliberately does not expose them.
4. Explicitly select the target displayed by the scenario and acknowledge trusted project execution.
5. Choose **Run reviewed test**. Stop an interactive Runtime session first. Each run receives a fresh dedicated controller and an isolated internal-copy cache, so it cannot stop a shared Runtime-tab session or mix that session's output into evidence.
6. Inspect per-step PASS/FAIL/SKIPPED results, HTTP error classification, timings and process exit details. **Cancel** interrupts owned HTTP I/O and stops the owned process/preview. Closing the panel or changing projects also cancels its work.
7. Export/share the redacted evidence file explicitly. To compare, choose **Keep as before**, perform another reviewed run, then explicitly compare before/after. The exact scenario hash must match and both runs must really have completed their test assertions.

An internal workspace copy is not synced back automatically. A restart within one run retains that run's workspace. Re-running a copied project uses a fresh scenario cache. Direct/internal project targets can write in their own workspace; this is part of the explicit trusted-code approval. Demo state and evidence remain available for inspection. Scenario caches/demo workspaces are not silently deleted or synced into user projects.

## Version 1 JSON

A command example (save as `scenarios/smoke.json` in a project containing `smoke.cjs`):

```json
{
  "version": 1,
  "id": "smoke",
  "title": "Local state serialization",
  "targetId": "node:smoke.cjs",
  "artifacts": ["package.json"],
  "limits": {
    "runTimeoutMs": 60000,
    "stepTimeoutMs": 10000,
    "maxOutputBytes": 8192
  },
  "steps": [
    {
      "id": "check",
      "type": "command",
      "expect": {
        "exitCode": 0,
        "stdout": { "contains": ["serialization passed"] },
        "stderr": { "equals": "" }
      }
    }
  ]
}
```

All top-level and nested schema fields are allowlisted. Unknown fields, duplicate JSON keys (including escaped duplicates), malformed UTF-8, quoted integers, unsupported versions, and excessive nesting are rejected. IDs are unique ASCII letters/numbers/dash/underscore. A scenario requires at least one actual command or HTTP assertion.

### Step types

- `command`: `{ "id": "smoke", "type": "command", "timeoutMs": 10000, "expect": { "exitCode": 0, "stdout": { "equals": "ok\n" }, "stderr": { "contains": ["diagnostic"] } } }`
  - Starts the explicitly approved target and waits for its real exit; no server may be active. Static targets cannot be command targets. Exit code is required and can be −255 through 255. Text predicates are exact literals, never regex or executable expressions. Missing distinct streams are SKIPPED, never passed. Truncated or over-limit output fails the bounded operation.
- `start`: `{ "id": "start", "type": "start", "ready": { "path": "/health", "status": 200 } }`
  - Starts the same selected target. Only this reviewed, idempotent readiness GET is retried (at most 40 requests, still bounded by the step/run timeout). A busy host or changed session ID fails closed. Readiness by itself is not counted as an executed regression assertion.
- `http`: `{ "id": "state", "type": "http", "method": "GET", "path": "/state", "expect": { "status": 200, "body": { "contains": ["retained"] }, "json": [{ "pointer": "/value", "equals": "retained" }], "maxResponseTimeMs": 2000 } }`
  - Requires an active owned preview. Methods are GET, HEAD, POST, PUT, PATCH and DELETE. POST/PUT/PATCH/DELETE may contain `jsonBody` (maximum 16 KiB); requests without a body use an empty body where required. Assertions cover status, literal body text, typed JSON equality at RFC 6901 pointers, and elapsed time through complete bounded response-body read. A missing JSON path differs from an explicit JSON `null`. Redirect statuses cannot be expected. Business requests are never automatically retried, including mutations.
- `stop`: `{ "id": "stop", "type": "stop" }`
  - Stops the owned process and its preview. A subsequent start is a new session. Final cleanup also stops a server if an explicit stop is omitted or execution is cancelled.

`timeoutMs` is optional on every step. `stdout`, `stderr` and `body` support `equals` and/or up to eight nonempty `contains` literals. `json` supports up to 16 distinct pointers. HTTP status is required (100–599 excluding redirects); response-time limits must fit the step timeout. JSON values/pointers and stream expectations are never evaluated as code.

### Hard bounds

| Resource | Bound |
| --- | --- |
| Local scenario bytes | 64 KiB, strict UTF-8 |
| JSON nesting | 16 levels |
| Steps | 1–32 |
| Overall execution | 100–180,000 ms; default 120,000 ms |
| Each step | 100–60,000 ms and no more than overall bound; default 10,000 ms |
| Owned cleanup | Up to 10,000 ms additional; unsuccessful cleanup makes the run ineligible for comparison |
| Each HTTP response / process stream | Configurable 256–65,536 bytes; default 32,768 bytes |
| Request JSON body | 16 KiB |
| Extra source artifacts | 16 paths, 2 MiB each, 8 MiB total including entry/config |
| Evidence JSON | 512 KiB per run |

The Runtime controller independently bounds captured streams. The runner checks its stricter scenario byte bound while waiting for command exit and around HTTP/lifecycle operations. A burst can reach the controller's bounded capture before a polling check; it is never silently accepted as complete. Overall execution uses coroutine timeouts and cancellable HTTP. Trusted adapter implementations must honor cancellation and implement bounded process-group termination; this is a cooperative host contract, not a mechanism for forcibly interrupting arbitrary uncooperative Kotlin host code.

### Loopback scope

The JSON supplies only origin-relative ASCII paths. No `http://`, scheme-relative `//`, credentials, percent-encoded paths, traversal, backslashes or fragments are permitted. Simple ASCII queries are supported; bodies are the way to pass JSON data. This deliberately conservative path syntax is not a general URL templating language.

A base URL can come only from the currently owned runtime session. It must be plain HTTP on literal `127.0.0.1`, literal `::1`, or exact `localhost`, on an explicit port 1024–65535. `localhost` is pinned to `127.0.0.1`; no network DNS lookup is used. Other loopback spellings, IPv4-mapped hosts, private/LAN/link-local hosts, public hosts, URL userinfo, queries/fragments on the preview URL, and origin changes are refused. The client uses no proxy, cookies or credentials, disables redirects and automatic retries, requests uncompressed bodies, caps body reads and closes all calls/connections after execution. Even a redirect to another loopback service is blocked.

A trusted target could deliberately print the address of another loopback service. This is another reason the target and its config require explicit trust: the runtime URL is scoped to a session, but the runner does not claim cryptographic ownership of arbitrary code's listening socket.

## Offline state/error fixture

`RegressionScenarioSamples.seedOfflineNode(parent: Path): Path` creates a fresh `regression-node-*` child containing:

- `server.cjs`: dependency-free built-in Node HTTP/fs fixture, bound to `127.0.0.1:0`.
- `smoke.cjs`: real Node assertions and separate stdout/stderr.
- `package.json`: offline start/test scripts, no dependencies.
- `scenarios/state-roundtrip.json`: real server start → POST state update → GET retained state → process stop → new process start → GET persisted state → POST fixture-error arm → GET structured 429 → GET recovered 200 → stop.
- `scenarios/command-smoke.json`: real command exit/stdout/stderr assertions.

The fixture persists a small JSON state file using a temporary file followed by rename. The simulated provider endpoint is local and explicitly labelled `fixture`; it exposes `error.code = RATE_LIMITED` and `error.retryable = true` exactly once, then recovers. There is no request to a real provider, no token, no dependency download, and no changes to a user's existing app.

`RegressionScenarioSamples.nodeFiles()` exposes the exact seed contents for test/device integrations without coupling the general Runtime samples back into the scenario package. Tests seed new temporary directories rather than editing existing workspaces.

## Evidence and comparison

Each approved execution reserves `.building/scenario-evidence/<UTC timestamp>-<UUID>.json` before starting code. Evidence directories/files cannot be symlinks, existing files are not overwritten, and checkpoint evidence is written after each step. Final status includes PASS/FAIL/SKIPPED assertions, whether a step actually executed, UTC timestamps, elapsed milliseconds, process session IDs/exit codes/byte counts, HTTP status/classification/byte count/timing, the exact scenario SHA-256, and source artifact hashes.

The default source hash covers the entry file (or npm `package.json`), `.building/runtime.json` when present, and explicitly listed `artifacts`. **It is not a full-workspace/import/dependency hash.** Review can add important imported source/config files. All reviewed bytes are reloaded and rehashed before execution; a changed scenario or artifact requires fresh review. Paths must be local regular files under the selected project, with no symlink component or traversal. These checks are not an OS sandbox or protection from a trusted process deliberately racing filesystem changes.

Raw stdout/stderr, request/response bodies, environment, headers, expected text/JSON values, JSON pointers and arbitrary exception messages are **not stored**. Text/JSON evidence records type/byte count/match result with values redacted. This avoids depending on secret-shaped regexes to recognize arbitrary user data. Source/target/step identifiers and relative artifact filenames are metadata; do not put secrets into identifiers or file names. Exporting evidence is explicit, and nothing is uploaded automatically.

HTTP outcomes distinguish authentication (401/403), rate limiting (429), client/server errors, transport failure, timeout, blocked redirect/URL and oversized response. Receiving an expected local 429 can correctly pass its assertions while still preserving `RATE_LIMITED` as the observed network outcome. A nested JSON `error` object is recorded as structured error presence, not raw provider text.

`testActuallyRan` means at least one command/HTTP test assertion was evaluated; lifecycle/readiness steps do not qualify. `comparisonEligible` additionally requires stored evidence, complete execution/confirmed cleanup, two eligible PASS/FAIL runs, and no skipped steps/assertions. A cancelled run retains partial evidence and throws `ScenarioRunCancelledException` with that report. Timeouts and safety failures mark unmet assertions SKIPPED, not passed. Checkpoints are not completed runs and cannot prove a fix.

`RegressionScenarioComparison.compare(before, after, explicitlyMatchedScenarioSha256)` requires two distinct runs of the same target/scenario with the **exact same explicitly provided scenario hash**. It reports changed assertion verdicts and whether reviewed source artifacts changed. It does not claim a general regression was fixed, compare keyword scores, or accept tests that never ran. Reports are local diagnostic evidence, not signed/tamper-proof attestations.

## Integration API

```kotlin
val reviewed = RegressionScenarioParser.load(projectRoot, "scenarios/state-roundtrip.json")
// Present reviewSummary, sha256, artifacts, and require explicit target/trust selection first.
val approval = ScenarioRunApproval(
    reviewedSha256 = reviewed.sha256,
    selectedTargetId = reviewed.scenario.targetId,
    trustedProjectCodeAcknowledged = true,
)
// dedicatedController MUST be new and owned exclusively by this run; runner closes it.
val report = RegressionScenarioRunner(
    RuntimeControllerScenarioHost(dedicatedController),
).run(reviewed, approval)
```

The host seam is `RuntimeScenarioHost.start(projectRoot, targetId): RuntimeScenarioSession` plus suspending `close()`. A session provides an opaque session ID, `awaitReady()`, `awaitExit()`, current `snapshot()`, and suspending `stop()`. Missing separate stream support is explicit in nullable snapshot stdout/stderr. A host owns all its children/listeners and must stop them on close. The production adapter consumes RuntimeController's session ID, split streams and truncation flags; it rejects reused/non-idle controllers.

Android entry point:

```kotlin
@Composable
fun RegressionScenarioPanel(
    controller: RuntimeController,
    backend: IdeBackend,
    fileActions: FileActions,
    modifier: Modifier = Modifier,
)
```

The shared `controller` is only used for target availability/busy checks. The panel constructs a new dedicated Android host/controller per execution. Main navigation/build configuration is not owned by the scenario component.

## Targeted validation

```sh
./gradlew :developer-workbench:test --tests 'dev.ide.workbench.scenario.*'
```

The scenario suite covers strict parser/path/symlink/approval rules; secret-free export; missing/truncated streams; real localhost HTTP state/error sequencing through a session fake; blocked non-loopback/redirect origins; response-size/timing bounds; cancellation of genuinely pending HTTP I/O; partial evidence; cleanup and explicit same-hash comparison. `RuntimeControllerScenarioHostTest` additionally executes the seeded Node server/command and a Python command through the real RuntimeController, verifies persistence across new process IDs, checks live output cutoff and timeout termination. These tests use only fresh temporary workspaces and host-provided executables; missing Node/Python produces a real JUnit **SKIPPED** result, not a pass.

### Recorded validation results

- **Targeted JVM scenario suite: 21 passed, 0 failures, 0 errors, 0 skipped.** This includes real host Node/Python execution, not just session fakes.
- **Android ART `ScenarioAcceptanceTest`: all 3 tests passed** on an **API 36 ARM64 emulator with 4 KiB pages**, after the runtime host's canonical-root fix. The rerun verified:
  1. Real Node state persisted across process stop/restart, followed by the local structured 429 fixture and successful recovery.
  2. Actual command exit and separate stdout/stderr assertions, repeated evidence recording, and explicit same-scenario-hash comparison.
  3. Rejection of source artifacts changed after review, before execution.
- These ART results are **emulator results, not physical Pixel results**. They exercise the APK-delivered runtime and scenario runner; they do not establish Compose rendering, arbitrary installed-app lifecycle/keyboard behavior, or real provider reliability.
- The separate runtime suite subsequently passed **8 of 8** after fixing the Termux prefix-length limit, including direct `npm`/`npx` and project `.bin` shebang execution. These results are distinct from the scenario assertions.
- The final integrated `:developer-workbench:test` suite passed **134 tests**, zero failures/errors/skips, including all 21 scenario tests. The consolidated Android workbench run passed 71 tests, followed by three additional projectless-navigation tests; see [developer-workbench.md](developer-workbench.md) and the logs under `build/`.
