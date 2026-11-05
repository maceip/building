# Curated component roles

The app-facing namespace is `fragments.agentui`; the containment and state contract is in
[ARCHITECTURE.md](ARCHITECTURE.md). The table selects one foundation per role. Selection is a
design decision, not evidence that its current Compose port is production ready. Source
conversions under `fragments.components.*` remain reference implementations while behavior,
previews, and interactions are verified. Each adopted component uses `AgentUiTheme`.

| Role | Foundation | Reason | Compose status |
|---|---|---|---|
| Composer | **Selected:** prompt-kit `PromptInput`, with Creance attachment and stop behavior | Input and action slots let the host add controls without replacing the composer. | `ChatInput` is curated, tested, and previewed. The mounted product uses a single-row 88dp pill; its text-only transport routes the plus control to the slash menu. A panel UI test covers attach, remove, and send for an attachment-capable agent. |
| Conversation shell and scrolling | **Selected:** Creance `Chat` + `MessageList` behavior | Clear message/composer ownership, bottom pinning, and a jump control when the reader scrolls up. | `AgentChatPanel` is mounted in macOS; a UI test covers an oversized streaming turn, reader scroll-away, and Jump to latest. Narrow-window QA remains. |
| Mixed assistant message | **Selected:** signoff-ui `AgentMessage` part model | Parts stay in order: text, reasoning, tools, sources, files, and app data. | Runtime and `AgentMessageTurn` preserve ordered parts; source conversion still needs React parity review. |
| Specialized tool cards | **Selected:** Agent Elements tool renderers | Bash, edit, search, plan, todo, question, MCP, and generic calls have distinct representations. | Source conversions exist; shared styling and interactions pending. |
| Approval, diff review, run meter | **Selected:** signoff-ui | Purpose-built approval policy, diff controls, and run accounting. | Curated single and batch approvals now return request ids with single-shot decisions; diff and run meter need further interaction review. |
| Needs-you inbox and kill switch | **Selected:** Creance | Distinct cross-conversation triage and run cancellation. | Source conversions exist; shared styling review pending. |
| Agent identity, subagents, task cards, composer triggers | **Selected:** assistant-ui | Agent/run identity and task progress complete the turn hierarchy. | Source conversions exist; shared styling review pending. |
| Icons | **Selected:** one kit icon vocabulary, selected per semantic role | Apps ask for a send, status, or tool icon instead of choosing among duplicate `CheckIcon` exports. | `AgentIconId` selects one implementation per role; all 59 source geometries are checked against pinned React SVGs. |

The complete 140-name mapping is in [COMPONENT_DECISIONS.csv](COMPONENT_DECISIONS.csv).
An alias or source subpart in that table maps to its containing curated role;
it is not promised as a separate kit leaf. All 81 source ports pass a visible
render smoke test. This is a narrower claim than React interaction parity.

The chat contract in `../ui/specs/agentic-chat.md` still governs behavior: ordered mixed blocks,
honest status, real file selection, keyboard handling, and no inert controls. The newer
multi-agent runtime and navigation boundaries are defined by the composition contract.

Source files used for these decisions live in the separate [react-agent--ui repository](https://github.com/maceip/react-agent--ui/tree/main/components) (`creance/chat/Chat.tsx`, `creance/message-list/MessageList.tsx`, `signoff-ui/agent-message-base.tsx`, and `agent-elements/agent-chat.tsx`). The composer follows [prompt-kit PromptInput](https://github.com/ibelick/prompt-kit/blob/main/components/prompt-kit/prompt-input.tsx).
