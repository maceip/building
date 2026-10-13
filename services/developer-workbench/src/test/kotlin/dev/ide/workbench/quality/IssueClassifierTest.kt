package dev.ide.workbench.quality

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IssueClassifierTest {
    private fun classify(title: String, body: String = "", kind: RecordKind = RecordKind.ISSUE): QualityRecord = IssueClassifier.classify(
        ImportedRecord("sample/app#1", "sample/app", 1, kind, RecordState.OPEN,
            if (kind == RecordKind.ISSUE) MergeStatus.NOT_APPLICABLE else MergeStatus.UNKNOWN,
            title, body, Instant.parse("2026-10-01T00:00:00Z"), "https://github.com/sample/app/issues/1", emptyList(), false))

    @Test fun `template-only environment keywords do not establish compatibility or crashes`() {
        val report = classify("Bug report", """
            ### Describe the bug
            _No response_
            ### Android version
            Android 16
            ### Device model
            ExamplePhone
            ### Logs
            No response
        """.trimIndent())
        assertEquals(PrimaryTheme.UNCLASSIFIED, report.theme)
        assertEquals(Confidence.LOW, report.confidence)
        assertEquals(emptyList(), report.secondaryThemes)
        val inline = classify("Help", "Android version: 16\nDevice: unsupported-phone\nApp version: 1.0\n- [x] I have checked previous issues\nPlease describe the crash")
        assertEquals(PrimaryTheme.UNCLASSIFIED, inline.theme)
    }

    @Test fun `Chinese problem phrases match body and title without template bias`() {
        assertEquals(PrimaryTheme.DATA_STATE, classify("备份恢复失败", "恢复备份后数据丢失").theme)
        assertEquals(PrimaryTheme.RUNTIME_LIFECYCLE, classify("锁屏后后台播放停止").theme)
        assertEquals(PrimaryTheme.EXTERNAL_SERVICES, classify("网络连接超时，无法登录").theme)
        assertEquals(PrimaryTheme.UI_UX, classify("横屏时按钮重叠，小屏无法显示").theme)
        assertEquals(PrimaryTheme.DELIVERY_BUILD, classify("安装失败，编译错误").theme)
        assertEquals(PrimaryTheme.SECURITY_PRIVACY, classify("隐私设置遗漏，账户数据泄露").theme)
    }

    @Test fun `concrete state symptom overrides generic crash title`() {
        val result = classify("Select All is crashing", "After selecting all files the selected count is zero. No process termination occurs.")
        assertEquals(PrimaryTheme.DATA_STATE, result.theme)
        assertEquals("selection_queue_state", result.problemKey)
        assertEquals(Confidence.MEDIUM, result.confidence)
        assertEquals(EvidenceSource.BODY, result.evidence.source)
        assertTrue(result.evidence.snippet.contains("count is zero"))
    }

    @Test fun `screen-awake request is not automatically background lifecycle failure`() {
        val result = classify("Keep screen awake to observe download progress", "This is a feature request to watch progress, not a background playback failure.")
        assertEquals(PrimaryTheme.UI_UX, result.theme)
        assertEquals(RecordIntent.FEATURE_REQUEST, result.intent)
        assertEquals(CauseStatus.NOT_APPLICABLE, result.causeStatus)
    }

    @Test fun `positive compatibility and dependency maintenance are not user bug evidence`() {
        val positive = classify("Works fine on Android 16", "Tested on my device.")
        assertEquals(PrimaryTheme.PLATFORM_COMPATIBILITY, positive.theme)
        assertEquals(RecordIntent.POSITIVE_COMPATIBILITY_REPORT, positive.intent)
        assertEquals(CauseStatus.NOT_APPLICABLE, positive.causeStatus)
        val maintenance = classify("Bump dependencies to latest versions", kind = RecordKind.PULL_REQUEST)
        assertEquals(PrimaryTheme.DELIVERY_BUILD, maintenance.theme)
        assertEquals(RecordIntent.MAINTENANCE, maintenance.intent)
        assertEquals(CauseStatus.NOT_APPLICABLE, maintenance.causeStatus)
    }

    @Test fun `weak or competing evidence abstains rather than guessing`() {
        assertEquals(PrimaryTheme.UNCLASSIFIED, classify("Does not work", "I have an Android device.").theme)
        val ambiguous = classify("Backup restore fails and network connection fails")
        assertEquals(PrimaryTheme.UNCLASSIFIED, ambiguous.theme)
        assertEquals(Confidence.LOW, ambiguous.confidence)
        assertTrue(ambiguous.classificationReason.contains("ambiguous"))
    }

    @Test fun `specific backup IO and keyboard URI problems are not all generic crashes`() {
        val backup = classify("Fix backup restore failure handling", "I/O errors are incorrectly categorized as passphrase errors; tests not run.", RecordKind.PULL_REQUEST)
        assertEquals(PrimaryTheme.DATA_STATE, backup.theme)
        assertEquals(MergeStatus.UNKNOWN, backup.mergeStatus)
        val input = classify("Fix keyboard sticker URI assumptions", "Add tests for malformed input rather than assuming every provider accepts the URI.", RecordKind.PULL_REQUEST)
        assertEquals(PrimaryTheme.UI_UX, input.theme)
        assertFalse(input.classificationReason.contains("tests passed"))
    }

    @Test fun `author hypothesis and mechanism remain distinct from verified causes`() {
        val hypothesis = classify("Backup restore fails", "I suspect this may be caused by low storage.")
        assertEquals(CauseStatus.REPORTER_HYPOTHESIS, hypothesis.causeStatus)
        val mechanism = classify("Backup restore fails", "The operation fails because the destination is unwritable.")
        assertEquals(CauseStatus.MECHANISM_DESCRIBED, mechanism.causeStatus)
        val symptom = classify("Backup restore fails")
        assertEquals(CauseStatus.SYMPTOM_ONLY, symptom.causeStatus)
    }

    @Test fun `stack and environment blocks do not dominate substantive title`() {
        val input = classify("Small screen button overlap", """
            ### Environment
            Android version: 16
            ### Logs
            ```text
            java.lang.NullPointerException: private data
                at example.Broken.call(Broken.kt:42)
            ```
        """.trimIndent())
        assertEquals(PrimaryTheme.UI_UX, input.theme)
    }

    @Test fun `sensitive flag requires specialized support context not root view keywords`() {
        assertFalse(classify("Root view layout overlap").deviceSecuritySpecific)
        val specialized = classify("Device profile support for kernel exploit")
        assertTrue(specialized.deviceSecuritySpecific)
        assertEquals(Transferability.APP_SPECIFIC, specialized.transferability)
        assertTrue(specialized.evidence.snippet.contains("withheld"))
    }
}
