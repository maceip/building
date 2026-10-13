package dev.ide.workbench.quality

internal object VerificationChecklists {
    fun forThemes(themes: Collection<PrimaryTheme>): List<VerificationChecklist> = themes.distinct().sortedBy { it.ordinal }.mapNotNull { theme ->
        when (theme) {
            PrimaryTheme.RUNTIME_LIFECYCLE -> VerificationChecklist(theme, "Lifecycle and recovery", listOf(
                "Available: preserve a minimal redacted log and exact reproduction steps; compare exception/stack fingerprints before and after a change.",
                "Manual/device verification: rotate, background/foreground, recreate the activity, and restore after process death; check state and pending work without assuming a cause from a crash label.",
                "Manual/device verification: interrupt media/services/notifications with network loss and OS background restrictions; verify user-visible recovery rather than a silent stop.",
                "Not executed by this lab: device instrumentation, lifecycle stress tests, or a fail-before/pass-after regression. Record those results separately.",
            ))
            PrimaryTheme.DATA_STATE -> VerificationChecklist(theme, "Data integrity and failure injection", listOf(
                "Available: identify the reported state invariant (selected count, ordering, read state, queue uniqueness, or backup round trip) and cite the originating record.",
                "Suggested regression: write a fail-before/pass-after assertion for the invariant; test rapid competing selections and confirm latest-selection-wins without stale commits.",
                "Suggested failure injection: interrupt writes, simulate full/unwritable storage and malformed backup input, then verify rollback, preserved old data, and an actionable error category.",
                "Manual verification: round-trip a non-sensitive backup across supported versions/devices; distinguish I/O failure from passphrase/authentication failure.",
                "Not executed by this lab: database mutation, destructive corruption tests, backup restoration, or regression tests. Use disposable fixtures and preserve originals.",
            ))
            PrimaryTheme.EXTERNAL_SERVICES -> VerificationChecklist(theme, "API failure and network recovery", listOf(
                "Available: retain a redacted status/error category and explicit evidence URL; separate local symptoms from a proposed upstream cause.",
                "Suggested tests: timeout, disconnect/reconnect, malformed/empty responses, expired authentication, HTTP 401/403/429/5xx, bounded retries, cancellation, and duplicate request completion.",
                "Manual verification: check a clear error message, retry controls, offline state preservation, and recovery without duplicate writes or exposing tokens.",
                "Not executed by this lab: live provider calls, account login, network fault injection, or server-contract tests.",
            ))
            PrimaryTheme.UI_UX -> VerificationChecklist(theme, "UI sizes, input, and accessibility", listOf(
                "Available: translate the observed layout/input symptom into an explicit expected visual or state assertion.",
                "Suggested device matrix: narrow/wide screens, portrait/landscape, split-screen, unusual aspect ratios, large fonts, display scaling, long translations, and RTL.",
                "Suggested input tests: keyboard open/close, malformed/unsupported content URIs, empty sticker input, focus/back navigation, screen reader labels, and touch target sizes.",
                "Not executed by this lab: screenshot comparisons, accessibility audits, Compose/UI instrumentation, or device-matrix tests.",
            ))
            PrimaryTheme.DELIVERY_BUILD -> VerificationChecklist(theme, "Reproducible delivery", listOf(
                "Available: review observed dependency pins, lockfiles, wrapper/checksum configuration, and test-file presence; a dependency bump is maintenance, not proof of a bug.",
                "Suggested verification: run the project's trusted build/lint/test tasks on a clean checkout with the intended toolchain; compare dependency resolution and release artifacts.",
                "Not executed by this lab: Gradle/npm scripts, compilation, package installation, signing, publishing, or test tasks. Imported commands are never run.",
            ))
            PrimaryTheme.SECURITY_PRIVACY -> VerificationChecklist(theme, "Privacy and Android configuration review", listOf(
                "Available: review manifest/source configuration evidence for cleartext transport, debuggability, exported components, permissions, and backup declarations.",
                "Manual verification: inspect the merged release manifest and actual network/backup behavior, component authorization, registration/privacy flows, and sensitive-data handling.",
                "Not supported as a verdict: exhaustive Android lint, exploitability assessment, root/attestation bypass, or a security certification. Do not treat a clean scan as a pass.",
            ))
            PrimaryTheme.PLATFORM_COMPATIBILITY -> VerificationChecklist(theme, "Declared platform compatibility", listOf(
                "Available: distinguish explicit successful compatibility reports from failures and from template-only device/version fields.",
                "Manual verification: reproduce only on supported OS/OEM/architecture combinations using standard development devices; record platform scope explicitly.",
                "Not executed by this lab: device compatibility tests. Specialized device-security operational guidance is not included.",
            ))
            PrimaryTheme.PERFORMANCE -> VerificationChecklist(theme, "Measured performance", listOf(
                "Suggested verification: capture a repeatable baseline and profile startup, CPU, memory, battery, or frame time for the explicit reported symptom.",
                "Not executed by this lab: profiling or benchmarks. A generic crash does not establish a performance cause.",
            ))
            PrimaryTheme.PRODUCT_CAPABILITY, PrimaryTheme.UNCLASSIFIED -> null
        }
    }
}
