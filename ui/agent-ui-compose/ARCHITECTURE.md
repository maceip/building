# Fragments Agent UI: composition contract

**Decision:** the reusable product is **Fragments Agent UI**. Its existing Gradle module and
artifact remain `:agent-ui-compose` and `io.fragments:agent-ui-compose`. App code imports the
`fragments.agentui` API. `AgentChatPanel` is the embeddable root; it is a pane's content, not a
window shell or a new `WorkspaceSurface`.

This adopts Alto's separation of a stable host, a conversation service, independently owned
composer/browser/review features, and typed contributions. It does not require an app to adopt
Alto's Cordis runtime. See [Alto architecture](https://github.com/block/alto/blob/main/docs/architecture.md),
[plugin hierarchy](https://github.com/block/alto/blob/main/program/cordis.json), and
[chat surface contracts](https://github.com/block/alto/blob/main/program/plugins/chat-surfaces-api.ts).
The [Alto and DeepSeek comparison](REFERENCE_ARCHITECTURES.md) records the source-level
differences and the parts chosen for this kit.

## Containment

```text
Application workspace host                         app-owned
├── AgentChatPanel                                 kit-owned layout, app-owned session
│   ├── ConversationHeader                         agent identities + run state, optional app actions
│   ├── ConversationTimeline                       stable-keyed, bottom-pinned scrolling
│   │   └── ConversationTurn                       one author/agent/run, ordered parts
│   │       ├── Text / Markdown / citations / file / image
│   │       ├── ReasoningDisclosure                 subordinate to the answer
│   │       ├── ToolGroup                           Bash/Edit/Search/Plan/Todo/Question/MCP/generic
│   │       │   └── ToolRow → output / approval / question response
│   │       ├── BrowserEmbedCard                    inline projection of one browser session
│   │       ├── ReviewEntry                         opens full diff review in workspace pane/drawer
│   │       └── TurnFooter                          copy, feedback, RunMeter, subagent progress
│   ├── AgentComposer                              prompt-kit structure; own draft/focus lifecycle
│   │   ├── attachment chips
│   │   ├── growing editor + slash/mention menus
│   │   └── agent target, host action slots, send/steer/stop
│   └── panel-local overlays                        image lightbox, contextual menus
├── BrowserPane                                    app workspace; same session as BrowserEmbedCard
├── DiffReviewPane                                 app workspace; opened from ReviewEntry
└── NeedsYouInbox                                  app workspace adjunct across conversations
```

The kit also exposes the leaf components for use in other views. Nesting is defined by the
tree above: tools are parts of turns, an approval belongs to its tool request, and a diff's
compact entry belongs in the turn while the full review uses the workspace. `KillSwitch`
controls an agent **run**; the composer Stop action interrupts the current generation. They
must not become two buttons for the same operation. Browser, diff, and inbox may be large
panes without becoming top-level navigation surfaces.

## Conversation runtime

The kit needs a headless contract that every UI reads, rather than each component owning a
private message list. The app supplies provider adapters, **one execution session and durable
history per agent**, persistence, permissions, native browser hosting, and workspace layout.
The kit supplies a conversation projection, snapshots, routing contracts, and Compose
renderers. Its default in-memory implementation is for previews and tests; production
persistence is an injected port. This follows DeepSeek Harness's separation of
[durable session facts from transient stream frames](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/architecture.md#events)
and its [event-to-view conversation assembly](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/conversation.md).

```kotlin
interface ConversationHandle {
    val conversationId: ConversationId
    val snapshots: StateFlow<ConversationSnapshot>
    val events: Flow<ConversationEvent>
    fun history(): List<ConversationEvent>
    fun setDraft(target: AgentId, value: String)
    suspend fun dispatch(command: ConversationCommand): CommandResult
}

interface AgentTransport {
    val agentId: AgentId
    val capabilities: StateFlow<AgentCapabilities>
    suspend fun submit(command: AgentCommand, sink: ConversationEventSink): CommandResult
}
```

`ConversationSnapshot` contains participants, agent capabilities, ordered turns, requests,
run states, draft state, and references to browser/artifact sessions. It is a **merged view
over agent sessions**, not the model-visible log of every agent. Each turn has stable
`conversationId`, `turnId`, `authorId`, optional `agentId` and `runId`; its content is an
**ordered list of typed parts**. A single conversation can interleave several agents and
subagents without collapsing them into one assistant role. Source events carry an origin,
per-session sequence, timestamp, and correlation ID so tool output, approvals, and replies
attach to the correct run. The conversation coordinator assigns a stable display order to
references from different session logs; wall-clock timestamps alone cannot define that
order. The projection rejects duplicate IDs and exposes a current snapshot for Compose plus
an event feed for other components. Replay comes from session histories and the display-order
record, not an unbounded hot-flow buffer. The in-memory coordinator exposes `history()`
and `restore(history)` so the host can persist and replay the display record. The live
`events` flow emits accepted changes in order with backpressure. Production persistence
and streaming token coalescing are still host/transport work.

Commands are explicit: `Send(target: AgentId, draft)`, `Steer(target: AgentId, runId, draft)`,
`Interrupt(target: AgentId, runId)`, `ResolveApproval(target, runId, toolId, decision)`,
`RespondToQuestion(target, runId, turnId, questionId, answer)`, and
`PublishUiPart(authorId, part, turnId?)`. A target is required when more than one agent can
receive input; no global "selected agent" silently decides routing. A browser pane, editor,
or automation sends through the same handle as the composer. A component that only listens
collects `snapshots` or `events` by conversation ID. External parts are accepted through a
typed publisher and rendered in stream order. `PublishUiPart` is presentation only; anything
an agent will see must enter that agent's durable session through `Send` or `Steer`. Commands
report rejection or unsupported capability visibly; controls are enabled from
`AgentCapabilities`.

The app creates one handle per conversation. Multiple panels can observe one handle; changing
the focused workspace pane does not create a second agent session. Drafts are keyed by
conversation and target agent, so a reload, split, or tab switch preserves text and focus
without placing draft state inside the message renderer. Transport registrations and UI
extensions have explicit `close`/dispose lifetime, preventing duplicate listeners after
Compose reload or pane remount.

## Extension contract

Extensions register against a conversation host, with stable IDs and an owned lifetime:

| Registration | Purpose | Placement |
|---|---|---|
| `MessagePartRenderer` | Render one typed part kind | Inside a turn, in event order |
| `ToolRenderer` | Render one tool kind and its state | Inside `ToolGroup` |
| `ComposerAction` | Add a command/target/attachment action | Named composer action slot |
| `TurnAction` | Copy, feedback, review or app action | Turn footer or contextual menu |
| `ConversationObserver` | React to snapshots/events | No UI ownership |
| `WorkspacePaneRequest` | Ask host to open Browser/Diff/etc. | Host decides placement |

Registration is done when the host or feature starts, not during every composition. IDs
cannot collide. Unregistering removes the renderer/action and its listeners. Unknown part
or tool kinds show a compact generic fallback with original data available, not a blank
region. Contributions inherit `AgentUiTheme`; extensions do not bring separate palettes,
window chrome, or sidebars. The public API offers named slots and typed callbacks, while
the React source packages remain implementation references.

## Component selection and placement

| Role | Selected foundation | Scope |
|---|---|---|
| `ConversationTimeline` | Creance `MessageList` behavior | Bottom pinning, jump to latest, stable item keys |
| `ConversationTurn` | signoff-ui `AgentMessage` model | Ordered mixed parts; agent identity from assistant-ui |
| `AgentComposer` | prompt-kit `PromptInput` structure | Creance attachment and send/stop behavior; one composer implementation |
| `ToolGroup` / tool rows | Agent Elements | Specialized tool renderers; signoff-ui approval footer |
| `DiffReviewPane` / `RunMeter` | signoff-ui | Review opens contextually; meter belongs to a run/turn |
| `NeedsYouInbox` / `KillSwitch` | Creance | Cross-conversation triage / active run control |
| `SubagentList` / `TaskCard` | assistant-ui | Progress attached to the relevant agent/run |
| Icons, primitives, theme | One Fragments semantic vocabulary | No source-family icon or palette choice in app code |

These are architecture selections, **not a claim that the current Compose ports have complete
React fidelity**. The source-family copies under `fragments.components.*` are compiled
in `:agent-ui-source-ports` and are runtime-only dependencies of the published kit.
The macOS product mounts `AgentChatPanel` through `AgentUiProductSession` and adapts
its existing `AgenticChatStore` to a `ConversationHandle`; it no longer renders its app-local
chat composer or message list. External consumers see only the curated kit and its
shared foundation on their compile classpath. The default preview presents only
the curated kit; a separate developer comparison gallery explicitly depends on
the source-port module.

## Surface rule

`WorkspaceSurface` is application navigation. The current four values are Agent & Computer,
Data Multi-Buffer, Mobile Companion, and Diffity Chrome. Agent identities, provider tools,
cloud utilities, browser expansions, review panes, and drawers are **content or capabilities
inside those views**. Adding a message part, renderer, extension, or pane request never adds
a `WorkspaceSurface`. See [workspace navigation](../ui/specs/workspace-navigation.md) and the
[chat](../ui/specs/agentic-chat.md) and
[browser](../ui/specs/live-sandbox-pane.md) contracts.

## Migration order

1. Add typed IDs, ordered parts/events, `ConversationHandle`, `AgentTransport`, and tests
   for multi-agent interleaving, explicit targeting, replay, duplicate events, and disposal.
2. Build `AgentChatPanel` from the selected timeline, turn, and current `ChatInput`; verify
   draft persistence, focus, scroll, and long expanded tool output in Hot Reload previews.
3. Bring source-family tool, approval, reasoning, identity, and attachment behavior into
   the one theme and extension registry. Remove duplicate public choices role by role.
4. Adapt `:ui`'s `AgenticChatStore` and browser store to the handle. Use the same browser
   session for its inline card and expanded workspace pane; route approvals into real state.
5. Publish the curated artifact with an example app and compatibility tests. Inspect the
   actual window at narrow and wide widths before replacing the existing chat UI.

Every visible action needs a state change or a host callback, every part needs a renderer or
honest fallback, and every agent action needs an explicit target and correlated result.
