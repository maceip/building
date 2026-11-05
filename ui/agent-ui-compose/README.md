# Agent UI Compose

Reusable Compose Desktop components live in this Gradle module. Apps use the curated `fragments.agentui` API. The `fragments.components.*` packages retain the source-by-source conversions for comparison; they are not the recommended app API.

The [composition contract](ARCHITECTURE.md) defines the one kit, its conversation runtime,
multi-agent routing, component containment, and workspace boundary. The
[component selection record](COMPONENT_SELECTIONS.md) tracks the React foundations and
the current Compose implementation status.
The [component catalog](COMPONENT_CATALOG.md) inventories all requested named
Compose exports and icons, with their current integration status.
The [evidence gate](QUALITY_GATE.md) separates declaration presence, selected
interaction coverage, and product readiness. Run `./verify-agent-ui.ps1 -Scope product`
before making a product-ready claim. A passing gate covers selected contracts;
the pending visual baseline approval and remaining release checks are tracked
in `FINISH_LINE.md`, `RELEASE_STATUS.md`, and `VISUAL_BASELINES.md`.

## Use the unified panel

The host creates one `ConversationRuntime` for a conversation, registers one
`AgentTransport` per agent, and places `AgentChatPanel(handle)` inside its own
workspace. It may mount another observer or panel on the same handle. Transport
submission acknowledges routing; agent events then update the ordered timeline.
The host supplies the actual browser preview and expanded-pane callbacks for
`ConversationPart.Browser`, along with review/source callbacks. The in-memory
coordinator is suitable for previews and tests; persistence remains host-owned.

`AgentDiffReview(patch, onSubmit)` keeps its own viewed-file state by default.
Apps that persist a review can pass `viewedFiles` and `onViewedFilesChange` to
control that state. The submit result includes `viewedFiles` alongside hunk
decisions; marking a file viewed folds it, and Show file reveals it again.
Use `onSubmitAsync = { result -> ... }` when persistence or approval suspends;
the Apply action stays disabled until the callback finishes and can be retried
after a failure. Supply one submit callback per review.
Pass `files = listOf(ChatFileChange(path, oldContent = ..., newContent = ...))`
to let readers reveal unchanged lines around each hunk. A patch string alone
contains no hidden lines to reveal.

For multi-step tool calls, publish one `ConversationPart.ToolGroup` containing a
`ChatToolGroup`; child `ChatTool.id` values are the approval targets. The panel
renders the grouped disclosure and the host journal preserves nested groups.
For chronological signoff-style calls, publish `ConversationPart.ToolTimeline`
with `ChatToolTimelineCall` entries instead. Each entry has a stable call ID,
phase, optional input/output and `ChatToolTiming`; approval callbacks use that
call ID. The timeline supports controlled expanded IDs, arrow/Home/End focus,
and a keyed lazy list for runs above 150 top-level calls. Add
`ChatTimelineSubagent` to a starting call's `subagents` list to nest its calls;
the journal preserves the hierarchy and a pending child approval opens its
subagent unless the reader explicitly collapses it. New completions and failures
are announced together after a 300ms quiet period; set `announce = false` when
the host provides its own accessible run announcements.
Pass the panel's `toolRendererRegistry` to render host-specific tool bodies in
direct cards, groups, and timeline calls. A timeline call selects its renderer
with `toolKind` and passes its persisted `fields` plus input, output, and exact
`timelinePhase`; its generic status reflects the active phase. When a
registration closes, the timeline shows the saved input, output, and fields.
Use `AgentApprovalCard(toolName = ..., input = ..., onDecide = ...)` for one pending action,
or `AgentApprovalBatch(requests, onDecide = ...)` for several. The card previews
the supplied argument text without line numbers, accepts optional denial
feedback, and returns edited arguments when `editable = true`. Critical risk
requires a second explicit approval press. The host owns the final effect of
each returned decision.
For session or permanent choices, pass `ruleArgs` to show editable argument
patterns; `ChatApprovalChoice.args` returns the chosen patterns, or `null` for
any arguments. Set `ruleScope = false` when the host defines the scope itself.
Use `onDecideAsync` instead of `onDecide` for a suspendable decision: the card
holds repeat actions while it runs and reopens on failure. The host evaluates
and enforces any lasting rule.
Use `AgentJsonView(jsonText, label = "Tool result", collapseAfter = 24)` for
read-only tool output. It copies the entire value even when the visible output
is folded.
Use `AgentCodeBlock(lines, language = "kt")` for source snippets. It preserves
the supplied text and indentation and does not add line numbers.

The `Kit` tab of `AgentUiPreviewsKt` runs the panel with explicit local preview
data. Its browser slot is a labeled placeholder, not an embedded native browser.
The separate `consumer-smoke` build depends on the published Maven artifact and
renders every mapped curated role in `PublishedKitCatalogTest`; this protects
the drop-in API and transitive packaging without treating a render as full
interaction parity.

## Composer

**Base:** [prompt-kit PromptInput](https://github.com/ibelick/prompt-kit/blob/main/components/prompt-kit/prompt-input.tsx). The kit retains its extensible input and action slots. The mounted product uses the compact single-row, 88dp composer shape measured from the supplied Muse capture.

**Borrowed behavior:** Creance's attachments, send/stop state, and accessible controls. `ChatInput` also preserves IME composition and Shift+Enter. The composer has one border; the textarea does not draw a second box.

```kotlin
var draft by remember { mutableStateOf("") }
AgentUiTheme(darkTheme = true) {
    ChatInput(
        value = draft,
        onValueChange = { draft = it },
        onSend = { message ->
            sendToAgent(message)
            draft = ""
        },
        status = if (isGenerating) ChatInputStatus.Working else ChatInputStatus.Ready,
        onStop = ::stopGeneration,
        onAttachFiles = ::attachFiles,
    )
}
```

The caller owns the draft and clears it after accepting a send. `onAttachFiles` enables a multi-file picker on Desktop; `ChatAttachment.kind` distinguishes image and file chips, and the remove control appears only when `onRemoveAttachment` is supplied. `actions` accepts app-specific controls. In the product's text-only transport, the plus control opens the slash menu instead of a file picker.

## Product browser pool

The expanded browser pane has a **4 lanes** tab. It mounts Vitre's `VitreFrameHost` with four visible browser lanes, accepts two to four distinct web origins, and runs a Navigate/Extract workflow through `FramePool`. Each visible lane shows its physical lane number and assigned workflow letter, since concurrent workflows can finish in any lane. The collapsible Results, Lanes, and Network drawer shows live progress and observed requests; closing it gives the browser canvas its height back. The single-page browser session remains mounted when the pool tab opens and has the same controller when the user returns to the site tab. `nativePoolAcceptanceTest` exercises four independent local origins and that session continuity on Windows JCEF. The screenshot below uses deterministic fixture pages and is not a Muse reference match.

![Four visible Vitre browser lanes in the product on Windows JCEF](screenshots/vitre-pool-windows-fixture.png)

## Browser visual review

The inline card displays a scaled frame from the same Vitre controller used by the expanded pane. Chromium keeps a 1600×1000 viewport while the card is inline, so a real site does not reflow into the card's 432×270 preview. On the current Cerebral Valley login page, the inline blue button measures 91 pixels in the live product capture and 89 pixels in the supplied primary card screenshot. The native acceptance test checks the offscreen frame size, the expansion resize, and controller identity.

The following Windows JCEF captures loaded the current public Cerebral Valley login page in a fresh browser profile on 2026-10-27. They show actual product rendering at both review sizes. The site can change, so these are human review evidence rather than automated baselines.

The [primary Muse comparison](screenshots/browser-primary-live-review-candidate.png) places the two supplied screenshots beside these live Vitre captures. It remains a review candidate: the reference expanded screenshot shows a loading state, while the product captures show the painted page.

| Window | Inline card | Expanded pane |
|---|---|---|
| 1280×800 | ![Live inline browser at 1280×800](screenshots/live-cv-inline-1280x800.png) | ![Live expanded browser at 1280×800](screenshots/live-cv-expanded-1280x800.png) |
| 960×700 | ![Live inline browser at 960×700](screenshots/live-cv-inline-960x700.png) | ![Live expanded browser at 960×700](screenshots/live-cv-expanded-960x700.png) |

To recapture, set `FRAGMENTS_AGENT_UI_LIVE_CAPTURE=1` and run `:nucleus-macos26:jvmTest --tests fragments.product.LiveBrowserVisualCaptureTest -PvitreJbrHome=<JBR with JCEF>` from `../nucleus`. Set `FRAGMENTS_AGENT_UI_LIVE_WIDTH=960` for the narrow pair. The deterministic local-page test remains the product gate.

The Windows product gate also runs `tools/product_black_box.py` outside the app process. Install `tools/requirements-black-box.txt`, then run `verify-agent-ui.ps1 -Scope product -VitreJbrHome <JCEF JBR>`. Its fresh app profile, local pages, native window screenshots, and process logs stay in `../nucleus/nucleus-macos26/build/black-box-product/`. The test clicks the card through the AWT canvas, verifies that pointer input is blocked before takeover, enabled during takeover, and blocked after release, and drives native Back, Forward, and Reload on a second page. It posts Win32 events from a separate process; it does not prove foreground physical input or approve the Muse visual baseline.

## Local dependency

From `../nucleus`, run `./gradlew :agent-ui-foundation:publishToMavenLocal :agent-ui-source-ports:publishToMavenLocal :agent-ui-compose:publishToMavenLocal`. In another Compose JVM app, add `mavenLocal()` and `implementation("io.fragments:agent-ui-compose:0.1.0-SNAPSHOT")`. Match the app's Kotlin and Compose versions with this module's versions in `../nucleus/gradle/libs.versions.toml`.

For an interactive preview, run `../nucleus/hot-agent-ui-preview.ps1` and leave that session open. After editing preview or library source, run `.\gradlew.bat :nucleus-macos26:reload` from `../nucleus` to recompile and reload the **same window**. The `Kit` tab has five curated sections covering the panel, tools and review, workflow, content, and feedback; its conversation panel toggles light and dark themes. The other tabs remain source comparisons. Changes to data-class shape may require restarting the preview because JVM class redefinition cannot always migrate live instances.
