# Third expansion pillar: App Quality & Reliability

Research date: 2026-10-12. This complements the publishing and general-purpose runtime areas in `tripple_plaj.md`; it does not treat that draft's implementation assumptions as verified requirements.

## Recommendation

Make the third pillar **App Quality & Reliability**: collect evidence, organize recurring problems, reproduce bounded scenarios, and verify a change. A successful compilation is not evidence that an app preserves state, survives interruptions, or works with a keyboard, provider, or device configuration.

Do not market this as autonomous repair. The existing IDE already has AI editing, diagnostics, quick fixes, dependencies, and run tasks. The missing value is an evidence-linked verification loop rather than another generic chat button.

## What was actually supplied

The attachment `github_issues_prs_39days_final_simp (1).zip` contains one JSON export with **663 unique records: 625 issues and 38 PRs, from nine repositories**. The recorded cutoff is `2026-09-02T00:00:00Z`; only **356 issues and 27 PRs** were created on or after it. Creation dates extend back to 2018. The filename is not a reliable date filter.

| Repository | Issues | PRs |
|---|---:|---:|
| HuangZhuoRui/LocationSpoofer | 18 | 20 |
| ZalithLauncher/ZalithLauncher2 | 10 | 0 |
| signalapp/Signal-Android | 126 | 10 |
| deniscerri/ytdlnis | 15 | 0 |
| tiann/KernelSU | 0 | 8 |
| YuKongA/ghostlock-app | 161 | 0 |
| thunderbird/thunderbird-android | 15 | 0 |
| breezy-weather/breezy-weather | 11 | 0 |
| maxrave-dev/SimpMusic | 269 | 0 |

All issues are open in this export. PRs have open/closed state but **no reliable merge status, diffs, complete discussion, or independently verified outcomes**. Repository selection and pagination are uneven. SimpMusic, ghostlock-app, and Signal account for 556 of the 625 issues. Some records concern desktop builds, forks, proposed changes, or app-specific feature requests. These counts are not Android ecosystem prevalence, unique users, or a trend estimate.

The original archive and per-record labels stay outside the repository; no source archive, personal logs, or bulk issue bodies are bundled into the app.

## Method

Each available title and body was reviewed and assigned one mutually exclusive primary theme plus up to two secondary themes. Separate fields record intent, short problem family, symptom versus described mechanism, confidence, transferability, a specialized device-security flag, and an exact evidence excerpt. Canonical `owner/repo#number` identifiers deduplicate the export. Quotes, field schemas, and record coverage were validated programmatically for all 663 labels.

The labels are qualitative analyst judgments, not ground truth. A reported mechanism is not confirmed merely because a detailed explanation appears in an issue. Environment-template fields, copied dependency changelogs, and generic crash words must not outweigh the actual described behavior. For example, ytdlnis #1408 says selection count resets to zero even though its title says “crashing.” LocationSpoofer #74 is positive compatibility feedback, not a failing device.

Sensitivity comparisons use the full export, the stated creation-date cutoff, and an exclusion of specialized kernel/root/profile/detection support. Issues and PRs are never added together as if both were independent complaints. Repository-balanced proportions were also calculated; they reinforce UI/state as priorities but remain descriptions of this selected sample.

## Problem groups and sensitivity

| Primary issue theme | Full export (625) | Excluding specialized device-security support (468) | Recent, excluding specialized support (202) |
|---|---:|---:|---:|
| Data and state | 103 | 102 | 42 |
| UI, input, accessibility and usability | 93 | 93 | 53 |
| Runtime and lifecycle | 105 | 89 | 25 |
| External services and integration | 74 | 74 | 23 |
| Performance | 22 | 22 | 6 |
| Product-specific capability | 25 | 23 | 13 |
| Platform compatibility | 155 | 18 | 9 |
| Build, install and delivery | 16 | 16 | 9 |
| Security and privacy | 16 | 15 | 11 |
| Insufficient/other evidence | 16 | 16 | 11 |

The largest raw category, platform compatibility, is dominated by one specialized device-profile repository. Building a root/device-bypass feature from that raw count would be the wrong conclusion. There are **157 specialized issues and 17 specialized PRs** in the flagged slice. Three of the specialized PRs describe portable quality techniques, but their specialized feature value remains separate.

Among all issues, 379 were coded as portable app-quality evidence and eight as developer-tooling evidence. That is a judgment about possible transfer, not proof that one IDE feature will solve them all.

## Concrete evidence for the third pillar

### State survives transitions

- [Signal #15060](https://github.com/signalapp/Signal-Android/issues/15060): a notification/bubble transition changes conversation while retaining draft text, with a wrong-recipient report. The relevant invariant is recipient binding, not just an attractive screenshot.
- [Signal #14875](https://github.com/signalapp/Signal-Android/issues/14875): backup creation fails on a nullable legacy attachment field, described with `requireNonNullBlob()`. It supports legacy fixtures and backup round-trip tests; the mechanism is reporter-described.
- [Breezy Weather #2610](https://github.com/breezy-weather/breezy-weather/issues/2610): app language resets after reboot.
- [SimpMusic #2608](https://github.com/maxrave-dev/SimpMusic/issues/2608): local and remote playlists disappear after an update.
- [Signal PR #15022](https://github.com/signalapp/Signal-Android/pull/15022) explicitly links issue #15021 and describes a three-case regression with a failing case when the proposed fix is reverted. This is an author-reported validation pattern, not a independently verified merged fix.

### Failures need a stage and recovery behavior

- [SimpMusic #2429](https://github.com/maxrave-dev/SimpMusic/issues/2429): playback does not resume when connectivity returns.
- [ytdlnis #1377](https://github.com/deniscerri/ytdlnis/issues/1377): a metadata replacement operation reports `re.error: bad escape \\S at position 69`.
- [ytdlnis #1374](https://github.com/deniscerri/ytdlnis/issues/1374): an update stalls after download completion, at installer handoff rather than the download stage.
- [Signal PR #15036](https://github.com/signalapp/Signal-Android/pull/15036) links #12743 and proposes separating restore I/O failures from incorrect-passphrase errors. It explicitly does not recover corrupted data and says tests were not run.
- [Signal PR #15044](https://github.com/signalapp/Signal-Android/pull/15044) addresses one contact-identity cause but explicitly does not close all causes in #13827. Similar symptoms must not be collapsed into a single asserted cause.

### UI, input and privacy depend on entry point and configuration

- [Signal #15058](https://github.com/signalapp/Signal-Android/issues/15058): the selector becomes barely usable on smaller screens and with the keyboard open.
- [ZalithLauncher2 #2056](https://github.com/ZalithLauncher/ZalithLauncher2/issues/2056): keyboard disappearance while deleting text.
- [Breezy Weather #2989](https://github.com/breezy-weather/breezy-weather/issues/2989): chart clipping at an exact boundary value.
- [Signal PR #15048](https://github.com/signalapp/Signal-Android/pull/15048) links #15035 and describes content-based keyboard input handling with malformed-input tests and explicit coverage limits.
- [Signal PR #15037](https://github.com/signalapp/Signal-Android/pull/15037) links #14450: a registration activity omits the application's screenshot-security setting.

## Product scope

The implementation should provide connected, executable capabilities:

1. **Evidence inbox:** locally import GitHub issue/PR JSON or ZIP; group observations into themes with IDs, confidence, excerpts, explicit links, sampling warnings, and sensitive-support filters. Classifier output is a candidate grouping, never a confirmed diagnosis.
2. **Failure evidence:** redact pasted or captured debug logs, group actual exception frames into stable fingerprints, and export reviewable local reports. Do not infer one cause merely from similar wording.
3. **Project preflight:** bounded, read-only source/configuration checks with file/line evidence and a distinction between observed configuration and review-needed warnings. These are not a replacement for Android Lint or runtime tests.
4. **Real verification:** expose real Gradle lint/unit-test/build/bundle tasks without accidentally launching/installing. Unsupported native test execution must say so.
5. **Executable regression scenarios:** reviewed local runtime targets plus controlled loopback assertions for persistence/restart/error/recovery; save actual assertion outcomes and source hashes. Suggested OEM/rotation/keyboard/device matrices remain unexecuted suggestions unless an actual runner and device evidence exist.

Performance profiling, general Android UI replay, database inspection, and universal automated repair are not claimed as implemented merely because they are valuable research directions.

## Automatic grouping design

Normalize records and deduplicate identifiers first. Preserve the original issue/PR distinction, dates, and repository. Remove untouched template headings and copied boilerplate from scoring; use multilingual symptom patterns, title/body context, and abstention. Keep broad themes separate from specific exception fingerprints and explicitly linked proposed remedies. Attach confidence and exact source evidence to each result.

The shipped offline heuristic requires no AI account or external data upload. It should be evaluated separately against the analyst labels rather than silently reusing those labels or claiming semantic-model accuracy. Any future model-assisted grouping must keep low-confidence review, stable IDs, explicit data-sharing consent, and tests for template bias, multilingual input, duplicate reports, and oversized archives.
