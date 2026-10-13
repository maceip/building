package dev.ide.workbench.quality

import java.time.Instant
import java.util.Locale

internal data class ImportedRecord(
    val id: String,
    val repository: String,
    val number: Long,
    val kind: RecordKind,
    val state: RecordState,
    val mergeStatus: MergeStatus,
    val title: String,
    val body: String,
    val createdAt: Instant?,
    val url: String,
    val references: List<ExplicitReference>,
    val truncated: Boolean,
)

/** Deliberately conservative local pattern matching, not an LLM or a root-cause classifier. */
internal object IssueClassifier {
    private data class Rule(val theme: PrimaryTheme, val key: String, val pattern: Regex)
    private data class Hit(val rule: Rule, val score: Int, val titleMatch: MatchResult?, val bodyMatch: MatchResult?)
    private fun rule(theme: PrimaryTheme, key: String, expression: String) =
        Rule(theme, key, Regex(expression, setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)))

    private val rules = listOf(
        rule(PrimaryTheme.DATA_STATE, "selection_queue_state", "(?:select(?:ed|ion)?|queue|playlist|read (?:state|status)|message|ordering).{0,85}(?:zero|count.{0,20}0|reset|lost|loss|wrong|duplicate|order|unread|persist|not sav|out.of.order)|(?:lost|reset|duplicate|corrupt|wrong|unread).{0,50}(?:selection|queue|playlist|messages?|state)|(?:选择|选中|队列|播放列表|已读|未读).{0,50}(?:丢失|重置|重复|顺序|为零|为0|数量.*0|状态)|(?:丢失|重复|重置).{0,30}(?:队列|播放列表|选择|已读状态)"),
        rule(PrimaryTheme.DATA_STATE, "backup_restore_integrity", "(?:backup|restore|database|migration|import|export|storage|saved? (?:data|settings)).{0,90}(?:fail|error|corrupt|loss|lost|wrong|crash|portable|incompatib|I/O|\\bIO\\b|passphrase|permission|duplicat)|(?:fail|corrupt|lost|crash|fix|repair|support).{0,50}(?:backup|restore|database|migration|import|export|persist)|(?:备份|恢复|还原|数据库|导入|导出|数据).{0,45}(?:失败|错误|损坏|丢失|异常|闪退|不兼容)|(?:丢失|损坏|无法保存|不能保存).{0,25}(?:数据|设置|备份)?"),
        rule(PrimaryTheme.DATA_STATE, "transactional_state_updates", "(?:latest|last).{0,20}(?:selection|request).{0,25}(?:wins|persist)|(?:atomic|transactional|race condition|stale write|rollback).{0,60}(?:write|state|data|selection|save)|(?:write|save|state).{0,50}(?:race condition|atomic|transactional|rollback)|(?:并发|事务|原子|回滚).{0,30}(?:写入|保存|状态)"),
        rule(PrimaryTheme.EXTERNAL_SERVICES, "network_api_recovery", "(?:network|connection|request|API|server|provider|stream|download|sync|authentication|login|session).{0,75}(?:fail|error|timeout|time.out|unavailable|disconnect|refused|expired|rate.limit|401|403|429|5[0-9]{2}|retry|reconnect)|(?:cannot|can't|unable|failed|broken).{0,25}(?:connect|download|stream|sync|log.?in|authenticate)|(?:网络|连接|请求|接口|服务器|下载|同步|登录).{0,45}(?:失败|超时|错误|断开|不可用|重试|失效)|(?:无法|不能).{0,12}(?:连接|下载|同步|登录)"),
        rule(PrimaryTheme.UI_UX, "layout_accessibility", "(?:layout|screen|window|aspect ratio|font|text|button|dialog|toolbar|navigation|accessibility|translation|localization).{0,65}(?:cut.off|clipp|overlap|too (?:small|large)|narrow|wide|wrong|broken|missing|unreadable|inaccessible|doesn.t fit|size|ratio)|(?:clipp|overlap|narrow screen|small screen|large font|screen reader|talkback|landscape|portrait|aspect ratio)|(?:界面|屏幕|布局|字体|按钮|对话框|翻译|无障碍).{0,40}(?:遮挡|重叠|截断|太小|太大|错误|缺失|比例|适配)|(?:横屏|竖屏|小屏|大字体|屏幕阅读器)"),
        rule(PrimaryTheme.UI_UX, "input_navigation", "(?:keyboard|sticker|input|navigation|gesture|back button|swipe).{0,65}(?:URI|malformed|wrong|fail|crash|assum|dismiss|broken|stuck|invalid)|(?:键盘|输入法|贴纸|返回键|手势).{0,40}(?:错误|失败|崩溃|闪退|异常|无效)|(?:keep|stay).{0,18}(?:screen|display).{0,20}(?:awake|on)|(?:屏幕常亮|保持亮屏)"),
        rule(PrimaryTheme.DELIVERY_BUILD, "build_dependency_reproducibility", "(?:build|gradle|compil(?:e|ation)|packaging|signing|install|updat(?:e|ing)|CI|toolchain).{0,65}(?:fail|error|broken|cannot|unable|incompatib)|(?:bump|updat(?:e|ing)|upgrade|pin).{0,45}(?:dependenc|gradle|kotlin|version|library|libraries)|(?:构建|编译|打包|安装|签名|依赖).{0,40}(?:失败|错误|不兼容|升级|更新)|(?:更新|升级).{0,20}(?:依赖|版本)"),
        rule(PrimaryTheme.PERFORMANCE, "resource_usage", "(?:high|excessive|too much|leak|drain|slow|sluggish|jitter|stutter).{0,50}(?:CPU|memory|battery|disk|startup|launch|render|scroll|performance)|(?:CPU|memory|battery|startup|launch|performance).{0,50}(?:high|excessive|leak|drain|slow|sluggish)|(?:耗电|内存泄漏|启动缓慢|卡顿|电量消耗|CPU占用|内存占用过高)"),
        rule(PrimaryTheme.SECURITY_PRIVACY, "privacy_transport_exposure", "(?:privacy|permission|private|sensitive|password|token|account|registration).{0,65}(?:leak|expos|omitt|not (?:shown|respected|saved)|without consent|unprotected)|(?:cleartext|insecure transport|exported component|privacy setting|screenshot protection|privacy policy|unencrypted|data exposure)|(?:隐私|权限|敏感|密码|令牌|账户).{0,40}(?:泄露|暴露|遗漏|未保护|未经|明文)|(?:不安全传输|明文传输|截屏保护)"),
        rule(PrimaryTheme.PLATFORM_COMPATIBILITY, "platform_support", "(?:unsupported|incompatible|not supported|does not work|doesn't work|won.t (?:start|run)|cannot run).{0,55}(?:Android|iOS|Windows|Linux|macOS|device|OEM|kernel|architecture|arm64)|(?:Android|iOS|Windows|Linux|macOS|OEM|kernel|arm64).{0,45}(?:unsupported|incompatib|not supported|does not work|doesn't work)|(?:安卓|鸿蒙|系统|机型|架构|内核|Android).{0,35}(?:不兼容|不支持|无法运行|不能运行|适配)|(?:不支持|不兼容).{0,25}(?:机型|系统|安卓|Android)"),
        rule(PrimaryTheme.RUNTIME_LIFECYCLE, "background_continuity", "(?:background|notification|service|playback|session).{0,65}(?:stop|kill|lost|not (?:deliver|receiv|show)|die|dies|interrupt|crash)|(?:stops?|killed|lost).{0,35}(?:background|notification|service|playback)|(?:后台|通知|服务|播放).{0,40}(?:停止|中断|崩溃|闪退|收不到|不显示|被杀)"),
        rule(PrimaryTheme.RUNTIME_LIFECYCLE, "crash_hang_recovery", "\\b(?:crash(?:es|ed|ing)?|hangs?|freez(?:e|es|ing)|ANR|NullPointerException|IllegalStateException|fatal exception)\\b|(?:闪退|崩溃|无响应|死机|卡死)"),
        rule(PrimaryTheme.PRODUCT_CAPABILITY, "new_product_capability", "(?:feature request|please add|add support for|new (?:provider|integration|feature)|support for).{0,90}(?:provider|integration|format|codec|streaming|function|feature)|(?:新增|添加|增加).{0,35}(?:功能|服务商|提供商|格式|集成)|(?:功能请求|功能建议)"),
    )
    private val environmentHeading = Regex("(?i)^(?:environment|device(?: info(?:rmation)?)?|device model|phone(?: model)?|operating system|OS(?: version)?|android(?: version)?|app(?:lication)? version|version|build(?: version| number)?|additional (?:context|information)|环境|设备(?:信息|型号)?|系统版本|安卓版本|应用版本|版本号)\\s*[:：]?$" )
    private val environmentLine = Regex("(?i)^(?:[-*]\\s*)?(?:OS|Android version|App version|Device(?: model)?|Phone|Model|Build number|设备|机型|系统版本|应用版本)\\s*[:：=].*$")
    private val emptyBoilerplate = Regex("(?i)^(?:[-*]\\s*)?(?:\\[[ xX]\\]\\s*)?(?:_?no response_?|n/?a|none|not provided|not applicable|please (?:describe|fill|provide|enter|replace).{0,160}|i have (?:read|searched|checked).{0,180}|steps to reproduce|expected behavio[u]?r|actual behavio[u]?r|bug description|describe the bug|screenshots?|logs?|复现步骤|预期行为|实际行为|无|暂无|请填写.{0,80})[.：:]?$" )
    private val specialized = Regex("(?i)(?:\\b(?:root|xposed|lsposed|magisk|kernelsu|apatch|attestation|bootloader|exploit|device profile)\\b.{0,65}(?:support|profile|bypass|detect|hide|compatib|config|unlock|fail|working|works)|(?:bypass|evade|hide).{0,45}(?:root|detect|attestation|integrity)|(?:kernel exploit|anti.root|root detection|play integrity|safetynet|device.profile support)|(?:内核漏洞|设备配置文件|绕过.{0,20}(?:检测|验证)|隐藏root|root检测|支持.{0,20}(?:root|面具|内核)))")
    private val specializedTools = Regex("(?i)\\b(?:xposed|lsposed|magisk|kernelsu|apatch|safetynet|attestation|bootloader|kprobe|kprobes|gki|su[_ -]?manager)\\b|(?:内核漏洞|设备配置文件|机型配置|内核配置文件|解锁.{0,10}(?:引导|bootloader)|刷入.{0,15}(?:内核|模块)|面具模块|绕过.{0,20}(?:检测|验证)|隐藏.{0,10}(?:root|应用)|反root|root检测)")
    private val specializedContext = Regex("(?i)(?:\\b(?:kernel|device|root|rom|integrity)\\b.{0,75}(?:profile|offset|exploit|unsupported|not supported|support request|detection|bypass|module|patch|config)|(?:profile|offset|exploit|unsupported|not supported|support request|bypass|rooted|rooting).{0,65}\\b(?:kernel|device|root|rom|integrity)\\b|(?:内核|root|设备|机型).{0,35}(?:配置|不支持|适配|偏移|漏洞|绕过|检测|模块|补丁)|(?:配置|适配|偏移|漏洞).{0,25}(?:内核|root|机型))")

    internal fun isDeviceSecuritySpecific(text: String): Boolean = specialized.containsMatchIn(text) ||
        specializedTools.containsMatchIn(text) || specializedContext.containsMatchIn(text)

    private val positive = Regex("(?i)(?:\\b(?:works|working|runs) (?:fine |well |correctly )?(?:on|with)\\b|compatibility (?:confirmed|report)|confirmed working|successfully (?:tested|running)|(?:运行正常|兼容正常|测试成功|正常使用|完美支持))")
    private val maintenance = Regex("(?i)^(?:\\[[^]]+\\]\\s*)?(?:chore[:( ]|build[:( ]|deps[:( ]|bump\\b|update dependencies|upgrade dependencies|dependency (?:update|bump)|更新依赖|升级依赖)")
    private val feature = Regex("(?i)(?:feature request|please add|request[: ]|could you add|enhancement|new feature|keep.{0,15}screen.{0,20}awake|功能请求|功能建议|希望增加|添加功能|屏幕常亮)")
    private val hypothesis = Regex("(?i)(?:i (?:think|suspect)|might be|may be caused|probably|possibly|seems (?:to be|related)|怀疑|可能是|似乎)")
    private val mechanism = Regex("(?i)(?:because|caused by|due to|assumes? |instead of|incorrectly (?:marks|treats|categorizes)|race condition|因为|由于|错误地|根因)")

    internal fun substantiveBody(body: String): String {
        var environment = false
        var fence = false
        val output = ArrayList<String>()
        for (raw in body.replace(Regex("(?s)<!--.*?(?:-->|$)"), "").lineSequence()) {
            var line = raw.trim()
            if (line.startsWith("```") || line.startsWith("~~~")) { fence = !fence; continue }
            if (fence) continue // stack traces are analyzed separately, not treated as semantic prose.
            if (line.startsWith("<!--") || line.startsWith("-->")) continue
            val heading = Regex("^#{1,6}\\s+(.+)$").find(line)
            if (heading != null) {
                line = heading.groupValues[1].trim().trim('*')
                environment = environmentHeading.matches(line)
                continue
            }
            if (environmentLine.matches(line) || emptyBoilerplate.matches(line) || line.isBlank()) continue
            if (environmentHeading.matches(line.trim('*'))) { environment = true; continue }
            if (environment) continue
            if (line.startsWith("at ") || line.startsWith("Caused by:") || line.length > 1200) continue
            output += line
        }
        return output.joinToString("\n")
    }

    fun classify(raw: ImportedRecord, repositorySecurityScope: Boolean = false): QualityRecord {
        val body = substantiveBody(raw.body)
        val title = raw.title.trim()
        val text = "$title\n$body"
        // Safety filtering sees raw bounded text, including code/template blocks that semantic triage ignores.
        val sensitive = repositorySecurityScope || isDeviceSecuritySpecific("$title\n${raw.body}")
        val isPositive = positive.containsMatchIn(title) && !Regex("(?i)not working|doesn't work|不正常|无法").containsMatchIn(title)
        val isMaintenance = raw.kind == RecordKind.PULL_REQUEST && maintenance.containsMatchIn(title)
        val hits = rules.mapNotNull { rule ->
            val titleMatch = rule.pattern.find(title)
            val bodyMatch = rule.pattern.find(body)
            if (titleMatch == null && bodyMatch == null) null
            else Hit(rule, (if (titleMatch != null) 6 else 0) + (if (bodyMatch != null) 4 else 0), titleMatch, bodyMatch)
        }
        val ranked = hits.groupBy { it.rule.theme }.map { (_, list) -> list.maxBy { it.score } }
            .sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.rule.theme.ordinal })
        var selected = ranked.firstOrNull()
        var confidence = when {
            selected == null -> Confidence.LOW
            ranked.size > 1 && selected.score - ranked[1].score <= 1 -> Confidence.LOW
            selected.score >= 6 && (ranked.size == 1 || selected.score - ranked[1].score >= 4) -> Confidence.HIGH
            else -> Confidence.MEDIUM
        }
        var explanation = "Matched an explicit problem phrase; title matches weigh more than body matches. This is heuristic triage, not a verified cause."
        // A concrete body symptom is more informative than a generic 'crash' in a title.
        val specific = hits.filter { it.rule.theme in setOf(PrimaryTheme.DATA_STATE, PrimaryTheme.EXTERNAL_SERVICES, PrimaryTheme.UI_UX) && it.bodyMatch != null }
            .maxByOrNull { it.score }
        if (selected?.rule?.key == "crash_hang_recovery" && specific != null) {
            selected = specific
            confidence = Confidence.MEDIUM
            explanation = "A specific data, service, or input symptom in the body takes precedence over a generic crash label in the title; review the evidence."
        }
        var theme = if (confidence == Confidence.LOW) PrimaryTheme.UNCLASSIFIED else selected!!.rule.theme
        var key = if (theme == PrimaryTheme.UNCLASSIFIED) "insufficient_or_ambiguous_evidence" else selected!!.rule.key
        if (isPositive) {
            theme = PrimaryTheme.PLATFORM_COMPATIBILITY; key = "positive_compatibility_report"; confidence = Confidence.MEDIUM
            explanation = "The title reports successful compatibility, not an observed defect."
        } else if (isMaintenance) {
            theme = PrimaryTheme.DELIVERY_BUILD; key = "dependency_maintenance"; confidence = Confidence.HIGH
            explanation = "The PR title describes dependency/build maintenance, not evidence of a user-facing failure."
        } else if (sensitive) {
            if (theme == PrimaryTheme.UNCLASSIFIED && isDeviceSecuritySpecific(text)) {
                theme = PrimaryTheme.PLATFORM_COMPATIBILITY; confidence = Confidence.MEDIUM
            }
            if (theme != PrimaryTheme.UNCLASSIFIED) key = "specialized_device_security_support"
            explanation = "Specialized device-security scope is separated for sensitivity analysis. Raw/template or configured scope alone does not establish a primary problem theme; operational details are withheld."
        } else if (theme == PrimaryTheme.UNCLASSIFIED) {
            explanation = if (ranked.isEmpty()) "No sufficiently specific problem phrase survived template/environment filtering; review needed."
            else "Competing theme signals are ambiguous; no primary label is asserted."
        }
        val intent = when {
            isPositive -> RecordIntent.POSITIVE_COMPATIBILITY_REPORT
            isMaintenance -> RecordIntent.MAINTENANCE
            raw.kind == RecordKind.PULL_REQUEST && Regex("(?i)\\b(?:fix|repair|prevent|correct)\\b|修复|纠正").containsMatchIn(title) -> RecordIntent.BUG_FIX
            feature.containsMatchIn(title) || theme == PrimaryTheme.PRODUCT_CAPABILITY -> RecordIntent.FEATURE_REQUEST
            title.endsWith("?") || title.endsWith("？") -> RecordIntent.SUPPORT_QUESTION
            theme != PrimaryTheme.UNCLASSIFIED && raw.kind == RecordKind.ISSUE -> RecordIntent.BUG_REPORT
            else -> RecordIntent.UNCLEAR
        }
        val cause = when {
            intent in setOf(RecordIntent.FEATURE_REQUEST, RecordIntent.MAINTENANCE, RecordIntent.POSITIVE_COMPATIBILITY_REPORT) -> CauseStatus.NOT_APPLICABLE
            hypothesis.containsMatchIn(text) -> CauseStatus.REPORTER_HYPOTHESIS
            mechanism.containsMatchIn(text) -> CauseStatus.MECHANISM_DESCRIBED
            else -> CauseStatus.SYMPTOM_ONLY
        }
        val source = if (selected?.titleMatch != null || isPositive || isMaintenance || sensitive || selected == null) EvidenceSource.TITLE else EvidenceSource.BODY
        val sourceText = if (source == EvidenceSource.TITLE) title else body
        // Redact the entire context BEFORE locating/slicing an excerpt. Slicing a password first can
        // remove its key and leave an unrecognizable credential suffix in an otherwise valid quote.
        val redaction = QualityRedactor.redact(sourceText)
        val match = selected?.rule?.pattern?.find(redaction.text)
        val quote = if (match == null) redaction.text.take(220) else redaction.text.substring(
            maxOf(0, match.range.first - 35), minOf(redaction.text.length, match.range.last + 100)).take(220)
        val evidence = EvidenceCitation(raw.id, raw.url,
            if (sensitive) "[Specialized device-security text withheld]" else quote.replace(Regex("\\s+"), " ").trim(),
            source, redacted = redaction.changed || sensitive)
        val transferability = when {
            sensitive || theme == PrimaryTheme.PRODUCT_CAPABILITY || isPositive -> Transferability.APP_SPECIFIC
            theme == PrimaryTheme.DELIVERY_BUILD -> Transferability.DEVELOPER_TOOLING
            theme == PrimaryTheme.UNCLASSIFIED -> Transferability.UNCLEAR
            else -> Transferability.GENERIC_APP_QUALITY
        }
        return QualityRecord(
            id = raw.id, repository = raw.repository, number = raw.number, kind = raw.kind,
            state = raw.state, mergeStatus = raw.mergeStatus,
            title = if (sensitive) "Specialized device-security support (details withheld)" else QualityRedactor.redact(title).text,
            body = if (sensitive) "[Withheld]" else QualityRedactor.redact(raw.body).text,
            createdAt = raw.createdAt, url = raw.url, intent = intent, theme = theme,
            secondaryThemes = if (theme == PrimaryTheme.UNCLASSIFIED || sensitive) emptyList() else ranked
                .filter { it.rule.theme != theme && it.score >= 6 }.take(2).map { it.rule.theme },
            problemKey = key, confidence = confidence, causeStatus = cause, transferability = transferability,
            deviceSecuritySpecific = sensitive, evidence = evidence, classificationReason = explanation,
            references = raw.references, textTruncated = raw.truncated,
        )
    }
}

internal fun countRecords(records: List<QualityRecord>): RecordCounts =
    RecordCounts(records.count { it.kind == RecordKind.ISSUE }, records.count { it.kind == RecordKind.PULL_REQUEST })

internal fun themeCounts(records: List<QualityRecord>): Map<PrimaryTheme, RecordCounts> =
    records.groupBy { it.theme }.toSortedMap(compareBy { it.ordinal }).mapValues { countRecords(it.value) }

internal fun buildThemeGroups(records: List<QualityRecord>): List<QualityGroup> {
    val byId = records.associateBy { it.id }
    return records.groupBy { "${it.theme.key}/${it.problemKey}" }.toSortedMap().map { (key, members) ->
        val linked = linkedSetOf<String>()
        val memberIds = members.map { it.id }.toSet()
        for (member in members) {
            member.references.filter { it.kind == RecordKind.PULL_REQUEST || byId[it.id]?.kind == RecordKind.PULL_REQUEST }
                .forEach { linked += it.id }
        }
        records.filter { it.kind == RecordKind.PULL_REQUEST && it.references.any { ref -> ref.id in memberIds } }
            .forEach { linked += it.id }
        val counts = countRecords(members)
        QualityGroup(
            id = "theme:$key", title = members.first().problemKey.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) },
            theme = members.first().theme,
            confidence = if (members.any { it.confidence == Confidence.LOW }) Confidence.LOW
                else if (members.all { it.confidence == Confidence.HIGH }) Confidence.HIGH else Confidence.MEDIUM,
            kind = GroupKind.THEME, issueCount = counts.issues, pullRequestCount = counts.pullRequests,
            recordIds = members.map { it.id }.sorted(), evidence = members.sortedBy { it.id }.take(5).map { it.evidence },
            linkedPullRequestIds = linked.sorted(),
            explanation = "Deterministic primary-theme/problem-pattern grouping. Similar symptoms do not establish the same cause or a successful remedy.",
        )
    }
}
