# Compose component catalog and integration status

This inventory was checked against the exported `fun` declarations in
`../agent-ui-source-ports/src/main/kotlin/fragments/components` on 2026-10-27. **Present** means a Compose
function exists and compiles. It does **not** mean React feature parity or that the
component has been exercised in the unified panel. The app-facing API lives in
`fragments.agentui`; the source-family packages live in a separate runtime
artifact and are available to the product's comparison gallery through an
explicit dependency.

| Source family | Requested named components | Present | Requested icons | Present |
|---|---:|---:|---:|---:|
| Agent Elements | 33 | 33 | 5 | 5 |
| Creance | 15 | 15 | 35 | 35 |
| signoff-ui | 24 | 24 | 19 | 19 |
| assistant-ui additions | 9 | 9 | 0 | 0 |
| **Total** | **81** | **81** | **59** | **59** |

## Agent Elements

Chat and input: `AgentChat`, `AnAgentChat`, `MessageList`, `UserMessage`,
`InputBar`, `Markdown`, `ErrorMessage`, `ImageLightbox`, `TextShimmer`,
`SpiralLoader`, `Suggestions`, `AttachmentButton`, `FileAttachment`,
`SendButton`, `QuestionPrompt`, `QuestionTool`.

Tools: `ToolRenderer`, `ToolGroup`, `ToolRowBase`, `ToolApprovalFooter`,
`BashTool`, `BashToolTerminalCard`, `EditTool`, `EditToolDiffCard`, `SearchTool`,
`SearchGroupRich`, `TodoTool`, `PlanTool`, `ThinkingTool`, `ThinkingCollapsed`,
`McpTool`, `GenericTool`, `GenericToolRow`.

Icons: `IconSpinner`, `CheckIcon`, `IconArrowRight`,
`IconDoubleChevronRight`, `FileExtIcon`.

Source: `fragments.components.agentelements` and its `.icons` package.

## Creance

`Chat`, `NeedsYouInbox`, `KillSwitch`, `Button`, `LoadingDots`, `PromptInput`,
`Reasoning`, `Sources`, `Citation`, `Avatar`, `Message`, `MessageList`,
`Suggestions`, `LiveRegion`, `VisuallyHidden`.

Icons: `CheckIcon`, `CrossIcon`, `PauseIcon`, `BlockedIcon`, `AlertIcon`,
`DotIcon`, `ChevronIcon`, `RetryIcon`, `ShieldIcon`, `ArrowDownIcon`,
`ChevronDownIcon`, `InfoIcon`, `ChevronLeftIcon`, `SearchIcon`, `CopyIcon`,
`PlusIcon`, `SunIcon`, `MoonIcon`, `MonitorIcon`, `ThumbIcon`, `DotsIcon`,
`PlayIcon`, `PauseBarsIcon`, `VolumeIcon`, `MutedIcon`, `ExpandIcon`,
`StopIcon`, `MenuIcon`, `SidebarIcon`, `SendIcon`, `SparkIcon`, `FileIcon`,
`ImageIcon`, `ArrowUpIcon`, `MinusIcon`.

Source: `fragments.components.creance` and its `.icons` package.

## signoff-ui

`AgentMessage`, `AgentMessageBase`, `ApprovalCard`, `ToolApprovalCard`,
`ToolApprovalBatch`, `DiffReview`, `RunMeter`, `SignoffLabelsProvider`,
`WithLabels`, `MarkdownRendererProvider`, `PlainText`, `CustomMarkdown`,
`Markdown`, `ReasoningBase`, `Sources`, `ToolCallTimeline`, `ToolCallDetails`,
`Img`, `Kbd`, `LiveRegion`, `CopyButton`, `CodeLines`, `JsonView`, `Revealed`.

Icons: `CheckIcon`, `XIcon`, `ChevronIcon`, `BanIcon`, `AlertIcon`,
`ShieldIcon`, `SparkIcon`, `CopyIcon`, `ExternalIcon`, `FileIcon`, `ImageIcon`,
`ToolIcon`, `TerminalIcon`, `ClockIcon`, `ArrowUpIcon`, `ArrowDownIcon`,
`CommentIcon`, `UndoIcon`, `SpinnerIcon`.

Source: `fragments.components.signoff` and its `.icons` package.

## assistant-ui additions

`SpeakerIdentity`, `SubagentList`, `AgentCard`, `TaskCard`, `TaskStateIcon`,
`ComposerTriggerPopover`, `DirectiveText`, `ShimmerLabel`, `SwapLabel`.

Source: `fragments.components.assistantui`.

## Curated kit composition

`fragments.agentui` now has one embeddable root (`AgentChatPanel`), one composer
(`ChatInput`), a shared `ConversationRuntime`, `AgentMessageTurn`, and themed
app-facing leaves in `AgentUiComponents.kt`. The panel composes ordered text,
reasoning, tools, sources, images, attachments, questions, browser cards, diff
entries, and registered custom parts. `AgentUiPreviews.kt` now exposes five
curated preview sections: Conversation, Tools & review, Workflow, Content,
and Feedback. The source-family tabs remain available for comparison.

The source-family versions remain public in `agent-ui-source-ports`, and their
gallery tabs remain useful for comparison. The published `agent-ui-compose`
artifact has only the curated API on a downstream app's compile classpath.
The separate `consumer-smoke` build renders the published panel and sends a
message through its public runtime with the runtime source-port dependency.
`COMPONENT_DECISIONS.csv` records exactly one curated role for each of the 81
component names and 59 icon names. The 81 source components each have an
independent nonblank render smoke test; the 59 icons have both render tests and
geometry comparison against pinned React SVG source. These tests do **not** prove
full React behavior, accessibility, or a visual match to the supplied screenshots.
The 140 requested names consolidate to 46 component roles (including eight typed
`ChatTool` variants) and 46 semantic icon roles. They are not 140 distinct public
Compose widgets.
The contract gate checks that all 140 decision rows are unique, cover the requested
names exactly, and resolve to declared curated roles.
`ToolApprovalBatch` maps to `AgentApprovalBatch`, which accepts kit-owned request
models and resolves individual or remaining pending requests once by id.

The macOS product now mounts `AgentChatPanel` through `AgentUiProductSession`,
with per-agent routing, streaming, draft persistence, approvals, and a Vitre
native browser session shared by inline and expanded views. Remaining release
work includes broader interaction and accessibility checks for the individual source
ports and approved product screenshots at desktop and narrower window
sizes. The native Vitre
acceptance test now drives the actual page through click, type, and navigation
with the action guard's approve and deny paths.

Hosts can register a `ToolRenderer` by stable `ChatTool.Generic.kind` in a
`ToolRendererRegistry`, pass that registry to `AgentChatPanel`, and close the
registration when the feature stops. The kit retains the tool header, border,
status, and approval controls; the host renders only its tool body. The same
renderer reaches direct and nested group calls. `Generic.fields` carries
structured display data through the product event journal. If a renderer is
removed, the tool shows its saved `detail` and fields as a readable fallback.
