# Alto and DeepSeek Harness: source comparison

Reference snapshots inspected: [Alto `10e2eb5`](https://github.com/block/alto/tree/10e2eb5221cc33f889386b39b808730903825319)
and [DeepSeek Harness `22ddee3`](https://github.com/deepseek-ai/deepseek-harness/tree/22ddee3792402c3e70ec5f86a63c3e3e895a2f60).
Both use Cordis, but their product boundaries differ. Fragments Agent UI is a published
Compose kit; the application remains its host.

| Question | Alto | DeepSeek Harness | Fragments decision |
|---|---|---|---|
| What is replaceable? | Workspace UI, shell contributions, panes, composer, agent integrations; stable host keeps transport and native capabilities. | Agent loop, models, tools, session store, providers, UI, and launch profiles are plugins. | Publish one coherent UI kit with typed host ports; host owns execution and native services. |
| What is the source of conversation truth? | Session service projects Codex/App Server or ACP agent activity into its browser UI; each `AgentChat` records one provider session. | Per-agent append-only `SessionEvent` log; model history and UI views are derived separately. | Keep one durable execution history per agent; build the shared visual conversation from references to those histories. |
| How is UI composed? | Independently registered conversation/composer components are resolved by workspace layout; slots, outlets, and surface registrations are plugin-owned. | Typed, parent-declared Slots and reusable Component Factories; event Definitions build Chat/Trajectory nodes, then keyed renderers display them. | One `AgentChatPanel` visual root with separate timeline/composer state; typed part/tool/action slots with explicit owner and disposal. |
| How do several agents participate? | Parallel chats in split panes; child subagent activities live under a parent chat. | Experimental Agent Teams owns roster, shared tasks, and addressed peer inbox sends; members keep separate Sessions and UI navigation. | Support both separate panels and a merged visual timeline; all sends name an agent and land in that agent's session. |
| How does another component observe/send? | `ClientSessionService` exposes subscribe/snapshot and send/steer/interrupt; extensions register state, methods, and actions. | Observe durable `session/event` or scoped live `agent/*`; address an `Agent` inbox with followup/steer/inject, or use Session-scoped client input APIs. | `ConversationHandle` exposes snapshots/events and explicit commands; host adapter owns actual delivery and persistence. |
| How are streams/replay handled? | Raw event journal and session projection; plugin reload preserves long-lived host services. | Durable settled events plus transient live chunks; one per-Session assembler creates Chat/Trajectory snapshots and coalesces high-frequency publication. | Persist settled agent facts, keep token deltas transient, assign cross-agent display order, and replay views deterministically. |
| How are panes/surfaces bounded? | Workspace split tree has Chat, Terminal, Browser, Canvas pane kinds; Browser owns native view lifecycle. | Root slot hierarchy and registered conversation Views; UI features contribute within declared slots. | App host owns panes and navigation; Browser card, Diff review, tools, and cloud utilities never create a new `WorkspaceSurface` by themselves. |
| What survives replacement? | Cordis effects remove old registrations; transactional reload restores the previous program if activation fails. | Cordis effects unwind; profile bundles and patches control runtime composition, with HMR for supported profiles. | Registration disposal is required; Compose Hot Reload is a development tool, not transactional runtime plugin reload. |

Sources: [Alto architecture](https://github.com/block/alto/blob/main/docs/architecture.md),
[Alto plugin contract](https://github.com/block/alto/blob/main/docs/plugin-authoring.md),
[Alto session API](https://github.com/block/alto/blob/10e2eb5221cc33f889386b39b808730903825319/program/plugins/session-api.ts),
[DeepSeek architecture](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/architecture.md),
[DeepSeek conversation assembly](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/conversation.md),
[DeepSeek Slots](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/slots.md),
[DeepSeek Agent Teams](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/agent-team.md),
and [DeepSeek Web Client](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/web-client.md).

## What to adopt

1. **Execution history and UI view are separate.** DeepSeek's Session log is a stronger model
   than treating `ConversationSnapshot` as primary storage. The Fragments timeline references
   agent-session events; it does not merge model histories or copy every token into a log.
2. **Addressed input has an acceptance result.** DeepSeek Team sends return target-inbox
   acceptance, not completed agent work. Fragments should return accepted/rejected plus a
   message ID, then correlate later run output separately.
3. **Renderers register into named, owned positions.** DeepSeek's event Definition and keyed
   Slot pairing keeps business state separate from a UI component. Fragments needs a smaller
   typed registry for parts, tools, composer actions, and workspace pane requests.
4. **A view can aggregate without owning execution.** Alto's pane compositor and DeepSeek's
   target views both support a shared Chat panel without embedding provider code in it.
5. **Use capability ports for native features.** Browser, file picker, model transport,
   persistence, and approvals are defined by the kit and supplied by the host.

## What differs from the references

Neither inspected implementation makes a multi-agent merged Chat timeline its durable source
of truth. Alto binds one provider session to each `AgentChat`; DeepSeek Team keeps teammate
Sessions separate, and its experimental team panel shows roster/tasks with navigation rather
than peer messages in one timeline. A Fragments merged timeline is therefore our own view
requirement. It needs a coordinator-owned stable display sequence across agent sessions;
timestamps and per-session sequence numbers cannot supply a deterministic total order alone.

DeepSeek's full Cordis profile system and richly nested Slot algebra solve runtime plugin
replacement across a complete harness. Fragments needs typed extension points and owned
lifetimes, but copying that entire runtime into one Compose library would make a drop-in chat
panel harder to use. The host can later adopt a larger plugin system independently.

Current implementation gap: [the architecture contract](ARCHITECTURE.md) is a decision record.
The `ConversationHandle`, session aggregation, renderer registry, `AgentChatPanel`, and host
adapters are not implemented yet. Source-family Compose conversions are still separate.
