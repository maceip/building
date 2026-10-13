# App Quality & Reliability Lab

The `:developer-workbench` JVM library implements offline issue/PR archive triage, local log recurrence analysis, and read-only project configuration preflight. It needs no LLM, API key, shell process, network call, or remote source upload. The Android host owns picking/copying files, background execution, navigation, sharing, and any separately authorized build/lint/test tasks.

This is an evidence tool, not an automated repair system, exhaustive Android lint, security certification, or a claim that tests passed. All suggested verification is explicitly **not executed**.

## Callable API

Package `dev.ide.workbench.quality`:

```kotlin
class QualityService(
    config: QualityConfig = QualityConfig(),
    clock: Clock = Clock.systemUTC(),
) {
    fun importArchive(path: Path): QualityReport
    fun analyzeLogs(text: String): QualityReport
    fun scanProject(root: Path): QualityReport
    fun export(
        report: QualityReport,
        destination: Path,
        format: ExportFormat = ExportFormat.fromPath(destination),
    ): ExportResult
}
```

All methods are synchronous, bounded, and isolated per call. UI callers must run them on `Dispatchers.IO` or a worker executor, not the UI thread. `ProjectQualityScanner(config, clock).scan(root)` is also public. `QualityException.code` distinguishes invalid input, unsupported format, configured limit, unsafe path, I/O failure, and an existing export destination. Errors do not echo issue bodies, parser excerpts, tokens, or logs. JVM and Android ZIP providers can reject an unsafe member at different layers; `INVALID_INPUT` and `UNSAFE_PATH` both represent safe refusal, not extraction.

`QualityReport` exposes:

- `kind`, `title`, `generatedAt`, and `schemaVersion`.
- `records`, primary-theme and recurring stack `groups`, and optional `statistics`.
- Scanner `findings`, `scanSummary`, or `logSummary`.
- `checklists`, `warnings`, and `caveats` that must remain visible alongside results.

A `QualityFinding` has `ruleId`, `severity`, `status` (`OBSERVED` or `REVIEW_NEEDED`), absolute `file`, one-based `line`, `reason`, scrubbed `evidence`, and a manual `remediation`. File paths remain navigable in memory; common private paths are redacted in exports. There is no aggregate pass/fail score. `HIGH` heuristic confidence is a rule-strength label, not a calibrated correctness probability.

## Archive inputs and provenance

Supported JSON:

```json
{
  "generated": "2026-10-12T12:00:00Z",
  "period": "since 2026-09-02T00:00:00Z",
  "repos": {
    "example/app": {
      "issues": [{"number": 1, "title": "Backup restore fails", "state": "OPEN"}],
      "prs": [{"number": 2, "title": "Fix backup restore", "state": "closed", "body": "Fixes #1"}]
    }
  }
}
```

- Raw UTF-8 JSON or a ZIP containing JSON files/directories. ZIP members are read in memory, never extracted. Unexpected non-JSON members, path traversal, absolute/drive paths, encryption, malformed streams, and oversized expansion are rejected.
- GitHub REST arrays or `{ "items": [...] }`. Repository identity comes from an explicit repository field or canonical GitHub URL; `ArchiveImportConfig.defaultRepository` is only a fallback for entries without identity. `{ "repo": "owner/repo", "issues": [], "prs": [] }` is accepted too.
- `period` can be `since <date/timestamp>` or `{start/from/since, end/to/until}`. Date-only end values include the full UTC day. An explicitly invalid end is not replaced by the generated time. `recentWindow` overrides declared windows; conflicting/missing member windows are not silently combined. `recentOnly` requires a usable window.
- Canonical IDs case-fold GitHub repository names (`owner/repo#number`) and citations are reconstructed as HTTPS GitHub issue/PR URLs. Invalid or inconsistent identity/field types are skipped and counted. Labels do not infer missing IDs.
- IDs are deduplicated deterministically; richer PR endpoint copies take precedence over issue-list copies. A REST issue carrying `pull_request` counts as a PR, never both. Explicit `merged`/valid `merged_at` data, including nested REST `pull_request` metadata, is honored. **Closed without merge evidence stays `UNKNOWN`.** Neither state nor merge data establishes a tested fix.
- Explicit references only: canonical GitHub URLs, `owner/repo#123`, and `#123`. Similar symptoms do not create issue–PR links. A reference is not a verified fix relationship; links/attachments are never fetched.

`ArchiveStatistics` keeps input, invalid, duplicate, and truncation counts separate. It provides full, recent, included, per-repository, theme, issue-state, PR-state, and PR-merge counts. Full history is not relabeled as “39 days” based on a filename. Recent membership uses `created_at`, not update activity. Missing dates and out-of-window records are explicit.

### Classification and sensitivity

The primary taxonomy follows the research coding guide: `platform_compatibility`, `runtime_lifecycle`, `external_services`, `data_state`, `ui_ux`, `delivery_build`, `performance`, `security_privacy`, `product_capability`, and `unclassified`.

Classification is deterministic local English/Chinese pattern matching. Title phrases carry more weight; specific body symptoms can override generic crash titles. Boilerplate headings, empty templates, environment-only fields, HTML comments, and code fences do not drive theme classification. Competing/insufficient evidence abstains. Positive compatibility reports and dependency-maintenance PRs are distinct from user bugs. Author hypotheses/mechanism descriptions remain distinct from independently confirmed causes, which this archive does not establish.

Specialized root/kernel/profile/attestation/tooling detection is separate from semantic classification and checks bounded raw text too. Detected records are hidden by default, counted in sensitivity slices, and have operational title/body/evidence withheld even when included. **The detector is not exhaustive.** Terse records may depend on repository context not present in their text. Review repository slices and optionally configure explicit user-reviewed scope:

```kotlin
val quality = QualityService(
    QualityConfig(
        archive = ArchiveImportConfig(
            deviceSecurityRepositories = setOf("chosen/specialized-repository"),
            includeDeviceSecuritySpecific = false,
        ),
    ),
)
```

No repository identities or analyst record labels are hardcoded into the product. This option is scope filtering, not evidence that every record in a configured repository is a security defect. No bypass/exploit instructions or automated remediation are provided.

## Log recurrence and privacy

`analyzeLogs` recognizes JVM/Android exceptions and stack frames. A fingerprint hashes exception class plus up to eight normalized frame methods, ignoring messages, line numbers, timestamps, and addresses. The deepest reported `Caused by` stack is used without claiming its cause has been verified. Exception-type-only groups have low confidence. Archive bodies with repeated signatures receive separate evidence groups; these overlap theme groups and must not be summed as independent record counts.

Native tombstones, obfuscation, arbitrary multiline formats, and non-JVM logs may require manual review. No matching exception does not mean a healthy application. No pasted command is executed.

Before excerpts are cropped, redaction processes full context. It recognizes common authorization/cookie/secret fields, private keys, GitHub/GitLab/AWS/Google/Slack-style tokens, JWTs, emails, IPs, private file paths, and non-citation URLs. The email scrubber scans at actual `@` characters rather than backtracking over long text. Final export applies redaction again, including when a caller constructs a report DTO manually. Canonical GitHub citations and repository names remain visible. **Redaction is best effort, not a PII detector or a guarantee that sharing is safe.** Review output before sharing.

## Project scanner

The scanner uses a bounded, read-only walk of a selected real directory. It does not follow symlinks, reads only regular candidate files, checks root containment, and always excludes `.git`, `.gradle`, `build`, and `node_modules` (plus configurable exclusions). Depth/entry/file/total-byte/finding limits produce explicit partial-coverage warnings. Use a stable project snapshot; this is not a transactional filesystem security boundary against a concurrently hostile writer.

Supported observations/review checks:

- Android manifest literal cleartext traffic and debuggability declarations, debug-source distinction, backup/transfer policy references, sensitive permission declarations, exported components, explicit signature-permission guards, and launcher intent-filter context.
- Network security XML cleartext exceptions/user trust anchors, debug-overrides distinction, broad root/external backup includes, and policy references not observed in scope.
- DTD/entity declarations are refused before parsing. External entity resolution is disabled/rejected; malformed or excessive XML is marked uninspected, not clean.
- Literal changing Gradle dependencies/version-catalog values, explicit release debuggability blocks, HTTP build URLs, wrapper checksum presence, npm dependency ranges, adjacent lockfile presence, conventional test paths, and test configuration hints. Build comments are ignored; package scripts are never executed.

Configuration evidence is not runtime evidence. The scanner does not evaluate Gradle, merge manifests, resolve placeholders/resources/dependencies, infer sensitive data from a component name, prove test wiring/fresh lockfiles, check vulnerabilities, or verify actual permission enforcement. Device-transfer behavior, release variants, library manifests, defaults, and runtime privacy flows need separate verification. Missing-test/lockfile statements mean “not observed in this scope,” not a proof of absence.

## Default safety limits

| Input/work | Default bound |
| --- | --- |
| Archive file bytes (raw JSON or ZIP) | 8 MiB |
| Expanded ZIP/JSON bytes | 16 MiB cumulative |
| ZIP entries / compression ratio | 32 / 200:1 |
| Input records before deduplication | 10,000 |
| Retained title / body / total text | 600 / 24,000 / 6,000,000 characters |
| JSON depth / approximate tokens | 64 / 400,000 |
| Log input / lines / line length / events | 1,000,000 chars / 20,000 / 4,096 chars / 2,000 |
| Project entries / depth | 20,000 / 20 |
| Project file / total inspected bytes | 512 KiB / 12 MiB |
| Findings | 500 |
| Export bytes | 24 MiB |

Config constructors validate hard ceilings. Long title/body/log lines are truncated with explicit warnings; structural/aggregate import limits reject the operation rather than silently treating a partial archive as complete. Scanner coverage can be partial and is labeled. Export creates only a new explicitly selected `.md`/`.json` destination (or explicit format), requires its parent to exist, and refuses overwrite/symlink destinations. It creates no automatic source patches or report directories.

## Verification

Run the module tests without an Android build:

```sh
CI_CORE_ONLY=true ./gradlew :developer-workbench:test --tests 'dev.ide.workbench.quality.*' --offline
```

Synthetic tests cover raw/ZIP schema, duplicate IDs, issue-list PR counts, unknown merge outcomes, full/recent metadata, malformed JSON/ZIP/UTF-8 and budgets, traversal/bombs, template bias, Chinese titles, misleading crash titles, sensitivity filtering, fingerprint/redaction behavior, safe exports, XML security, symlink boundaries, scanner positive/negative checks, and read-only behavior.

An opt-in external corpus audit is skipped when either environment variable is absent:

```sh
QUALITY_EVALUATION_ARCHIVE='/path/to/private-archive.zip' \
QUALITY_EVALUATION_LABELS='/path/to/reviewed-labels.json' \
CI_CORE_ONLY=true ./gradlew :developer-workbench:test \
  --tests 'dev.ide.workbench.quality.QualityCorpusEvaluationTest' --offline --rerun-tasks
```

`services/developer-workbench/build/quality-evaluation.json` contains counts, coverage/abstention, primary-theme agreement, high-confidence agreement, confusion, per-repository summaries, and specialized-device precision/recall. It contains no private titles/bodies, label corpus, or absolute input paths. This is a selected/skewed-sample audit, not held-out ecosystem validation. Human coding is independently reviewed judgment, not verified causes or objective ground truth. Do not substitute heuristic counts for the manually reviewed research findings.

### Measured archive audit (2026-10-12)

The supplied private archive imported **663 unique records: 625 issues and 38 PRs across nine repositories**, with zero invalid or duplicate records. Observed creation dates span **2018-04-14 through 2026-10-12**. The declared recent window contains **383 records (356 issues, 27 PRs)**, not the full archive. Repository volume is skewed; these are sample counts, not ecosystem prevalence.

The bounded pattern classifier was compared with all 663 independently reviewed primary-theme labels, without shipping those labels or tuning to canonical record IDs:

| Metric | Measured result |
| --- | --- |
| Classified coverage | 284/663 (42.84%) |
| Unclassified/abstained | 379/663 (57.16%) |
| Primary-theme agreement, all records | 209/663 (31.52%) |
| Primary-theme agreement, classified only | 188/284 (66.20%) |
| Primary-theme agreement, heuristic HIGH | 74/99 (74.75%) |
| Specialized-device detector precision | 89/(89+12) (88.12%) |
| Specialized-device detector recall | 89/(89+85) (51.15%) |

This demonstrates useful but incomplete triage, **not analyst-equivalent classification**. In particular, automatic specialized-device hiding misses many contextual/terse cases; use explicit reviewed repository scope when that matters. Confidence labels are not calibrated probabilities. Changes to patterns require rerunning the opt-in audit rather than reusing these numbers. Repository/platform mixtures and selection bias prevent broader prevalence or trend conclusions.

The private corpus and analyst labels are not bundled in the repository. Tests embed small synthetic data only. Host/module tests are distinct from Android device instrumentation and from tests of the user's scanned project.

Final recorded validation: **59 quality JVM tests passed** (58 synthetic plus the opt-in corpus evaluation), included in the 134-test workbench suite. On API 36 ARM64 Android, five quality acceptance tests passed, including the externally supplied original archive, redacted log grouping/export, read-only SAX manifest scanning and safe ZIP refusal. Five UI safety contracts and eleven bounded-import helper tests also passed on ART. These results validate those operations, not the quality of a scanned app or universal secret detection. Exact counts and evidence are summarized in [developer-workbench.md](developer-workbench.md).
