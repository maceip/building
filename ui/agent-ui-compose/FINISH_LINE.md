# Agent UI product finish line

This tracks the five requirements in the product review. A green automated
gate means the stated behavior passed; it does not turn a candidate screenshot
into an approved design.

The default 1100×760 preview now presents only the curated kit. Its Conversation
example starts with the supplied browser request and keeps the whole inline
card visible; prior approval and review parts remain reachable by scrolling.
`screenshots/kit-unified-panel-candidate.png` is the actual Compose capture.
The four source-family galleries are available separately for port diagnostics.

The isolated grouped-tool review capture is
`screenshots/kit-tool-group-candidate.png`. It shows the actual expanded
Compose preview; it is a candidate, not an approved baseline.
The kit-owned tool-card capture is `screenshots/kit-tool-card-candidate.png`.
Its Bash disclosure keeps the summary anchored and shows one bordered surface
with the command result beneath a hairline divider. This is also a candidate.
The JSON role capture is `screenshots/kit-json-output-candidate.png`.
The code-lines capture is `screenshots/kit-code-lines-candidate.png`.
The separate timeline capture is `screenshots/kit-tool-timeline-candidate.png`.
The curated approval captures are `screenshots/kit-approval-card-candidate.png`
and `screenshots/kit-approval-card-narrow-candidate.png`.
The lasting-rule scope capture is `screenshots/kit-approval-scope-candidate.png`.
It shows the editable argument pattern before a session or permanent decision.

| Requirement | Current evidence | Remaining acceptance work |
|---|---|---|
| One app panel and conversation runtime | `MacosChromeApp` mounts `AgentChatPanel` through `AgentUiProductSession`. Product tests send to two agents, stream, restore drafts and history, and resolve approvals. A mounted Compose test sends typed prompts and a mention to the production `OpenAiCompatibleBackend` through a loopback HTTP/SSE endpoint. It holds both streams and checks that each partial reply appears in the panel before completion, then checks model targets and final replies. A separate socket-level test checks concurrent streams and journal replay after restart. The published consumer composes the panel, sends to two agents from the panel and an external component, observes both turns, and publishes UI data without a model command. An opt-in Mac test sends both agents through the live qwen3-0.6b rtlm_server, verifies streamed text and completion, and replays the journal. | Exercise the mounted panel against the live server through a foreground keyboard session; the opt-in runtime test does not assess physical UI input or response quality. |
| One live browser session shared by card and expanded pane | Vitre native acceptance checks controller identity, DOM continuity, takeover, release, navigation, history, and painted pixels. Three fresh Windows JCEF processes completed 20 inline/split/maximize cycles each. The external product probe drives takeover and browser actions. Native macOS ARM JCEF acceptance passes for the inline/expanded browser and four-lane pool with candidate-only visual comparison. The loaded pane now shows the reference's page-title and address rows before takeover, keeping them visible through navigation. | Check foreground physical pointer/keyboard input on macOS. Investigate the intermittent mixed-suite Windows JCEF heap exit. The reference's add-tab and browser-menu actions remain absent. |
| Slash and mention composer | Product UI tests route typed slash actions and agent mentions and preserve text on both sides of a mention in a draft. The mounted Compose test selects the Skeptic mention and sends through the production HTTP/SSE backend to a deterministic local server. | Exercise the composer and model together against a running model server. |
| All 140 requested names | `COMPONENT_DECISIONS.csv` maps 81 component exports and 59 icon exports to one curated vocabulary. The product gate renders every component name, compares every icon's geometry, verifies public API isolation, and composes a published consumer. `AgentToolGroup` carries nested tools through the panel and journal. `AgentToolCard` now uses a kit-owned, stable disclosure row and one bordered surface for all eight typed tool variants; its interaction and width tests cover each variant. `AgentToolTimeline` separately carries chronological calls, phases, timing, disclosure, nested subagents and approval through the panel and journal; its Compose tests cover keyboard movement, long lists, coalesced announcements, host tool renderers, and saved fallback details. The curated approval card preserves argument text without source-port line numbers, supports denial feedback, argument edits, scoped keyboard shortcuts, critical confirmation, and narrow layouts. Diff review accepts file contents or patches, returns a Git-applicable patch and per-file applied content, rejected hunks, and pending work. It supports multi-line comments, keyboard review and item focus, and suspendable submission with duplicate prevention and retry after completion or failure. Full file content reviews reveal unchanged lines in 20-line steps or all at once; patch-only reviews do not invent context. Plan documents expand and approve; todo items display agent-owned status. Search results retain source, title, and date, support bounded scrolling, and expose no empty disclosure. | The 81 render cases are smoke checks, not proof of full React behavior or accessibility parity. Audit interaction requirements by curated role. Approval choices now carry caller-supplied rule patterns and the card holds duplicate decisions while an async handler runs, then allows retry on failure. The React policy evaluator and automatic argument suggestions remain outside this kit. Timeline motion has an animated-frame capture; an OS reduced-motion check remains. Diff review has a full short-review Tab path and a 120-file virtualized focus test; a real IME session remains unverified. |
| Visual product and four-surface rule | `WorkspaceSurface` has four values. The browser is a chat card and contextual pane. `AgentChatPanel` owns its conversation header, selected agent, and active run status. The split-view card scales with its chat column so the user request stays visible at 1280x800 and 960x700. Windows and macOS native JCEF captures cover both sizes; Windows live Vitre captures also cover both. `screenshots/full-window-muse-review-candidate.png` compares entire current windows with the supplied Muse references. The former root `macos26-cloud-base` window is folded into Model & Key Control Center as four utilities, without adding a surface. | Resolve the remaining shell navigation and reference-only chat/browser menu behavior, then have the user review and approve actual baselines before enabling the default visual gate. |

`CURATED_ROLE_AUDIT.csv` inventories the 38 composable roles and eight typed
tool variants with explicit evidence tiers. Every role has at least focused
semantics or behavior evidence. The product gate now requires all 46 named
role-audit cases to pass; `CURATED_ROLE_AUDIT.md` states the remaining
motion, overflow, and source-behavior review limits.

The unlabeled `⋯` toolbar action that silently cycled workspace surfaces was
removed. The four named surface tabs remain directly selectable. The browser's
separate four-lane tab is specified in `ui/specs/live-sandbox-pane.md`; it is a
mode inside the browser pane, not another `WorkspaceSurface`.

The macOS ARM review run uses the official JetBrains JBR 21.0.11 with JCEF and
a temporary checkout of this branch. The native browser and four-lane pool
tests pass with candidate images; the approved macOS baseline is still absent.
The 960x700 inline card now uses its compact width so the entire user request
remains visible above it. Four macOS captures are in
`screenshots/macos-jcef-baseline-candidates/`. The test sends simulated input
through Compose and AWT; foreground hardware input remains a separate check.
The opt-in `LiveModelProductIntegrationTest` passed on that Mac against the
resident `qwen3-0.6b` model at `127.0.0.1:9379`: two sequential agent sends
produced nonempty streaming deltas, completed, and replayed after remount.
It skips on Windows without `FRAGMENTS_AGENT_UI_LIVE_MODEL=1`.

The curated composer now keeps file attachment and slash actions available from
the same `+` launcher when a host enables both. A focused Compose interaction
test opens each action; hosts with attachments alone keep the direct picker.
When a host provides slash actions without a callback, selecting one now inserts
the command in that agent's draft rather than silently discarding it. A panel UI
test covers that fallback. Enter while an IME composition is active no longer
selects a slash or mention menu item; a real IME session remains unverified.
The selected Creance composer distinguishes image and file attachments. The
curated composer now carries that distinction, and the product panel labels
selected images while preserving the original file in the send command. An
unwired remove callback no longer produces a dead button. Focused UI tests
cover these states; thumbnail/lightbox behavior from the broader Agent Elements
input remains outside this selected composer contract.
Host-registered generic tool bodies now flow through direct and nested group
cards, survive journal replay with their structured fields, and return to the
saved fallback when the registration is disposed. Timeline-specific custom
rendering now uses that same registry for direct and nested timeline calls. The
renderer key and fields survive journal replay; disposing a registration restores
the saved timeline details. The role audit still covers motion and source behavior.
Timeline disclosures and chevrons now animate in Compose, and a geometry test
holds the trigger row at the same top position through expansion. New calls enter
once with the source's 320ms fade and 4px translation; running and waiting
status nodes spin and ping at the source durations. Initial history skips entry
motion, and a zero motion-duration scale disables these effects. The existing
preview is `screenshots/kit-tool-timeline-motion-candidate.png`; it predates
the status animation work. The refreshed settled-state capture is
`screenshots/kit-tool-timeline-status-candidate.png`. A frame-level Compose
test now observes running and approval status motion, while a zero motion-
duration scale leaves the same pixels unchanged. The two actual frames are
`screenshots/kit-tool-timeline-motion-start-candidate.png` and
`screenshots/kit-tool-timeline-motion-end-candidate.png`. These are review
candidates, not approved baselines; an OS-level reduced-motion setting still
needs verification.

The curated JSON role now has a full-value copy control, syntax tint, bounded
scrolling, and long-output folding. Its focused UI test expands and collapses a
30-line value without losing the copy control. The curated `AgentCodeBlock`
preserves supplied indentation and lines without invented line numbers, and
colors source tokens by language. Its token and visual checks run in the gate.
The diff review now scopes arrow movement and single-item decisions to a
focused review item or selected line. Header controls no longer trigger a hunk
decision; `J/K` can enter the item list from outside it. Focused UI tests cover
the boundary and movement between hunks. A separate Tab test traverses the
review from its first real control through file actions, hunk actions, line
actions, and Apply without an invisible root stop. It checks that the line
comment icon paints when keyboard focused. A separate 120-file test checks
focus and acceptance across an uncomposed row. A 160-line hunk test checks that
selecting a line keeps focus and extends selection even after its header leaves
the composed viewport. A real IME session still needs a separate check.

The expanded Action Guard now draws its approval strip with the shared
palette's amber warning token. Its 960×700 Compose test samples the banner
color, checks that Deny, Always allow, and Approve once all fit, then resolves
the suspended guard through the selected decision.
Taking browser control while an approval is waiting now returns a structured
denial to that agent, removes the guard banner, and keeps the next agent's
browser request paused until handback. A store concurrency test and a mounted
pane click test cover this handoff.
An in-flight planner step enters `UserPendingTakeover` when takeover is requested.
The striped notice stays visible until the current atomic step finishes; a held
planner store test and a mounted product test cover this safe-point handoff.

## Release gate

The full Windows product gate was rerun on `3be6584` after the batch-approval
change. It passed with `FRAGMENTS_AGENT_UI_BASELINE_DIR` set to the tracked
Windows candidate images, including the external product-window probe, both
native Vitre acceptance tasks, published consumer, and 140-name contract.
This verifies the candidate run; the images still need design approval.
The current product gate also passed after adding a mounted Compose test that
routes two typed prompts through the production HTTP/SSE backend to a held
loopback stream. It checked partial output for both agents before releasing
either response. The gate used candidate visual baselines, so its image result
is still a dry run pending design approval.
The inline browser card now uses the kit's darker `subtle` surface, which
reduced its measured color difference from the supplied Muse card. Fresh
1280×800 and 960×700 native and live-page captures, the unified gallery, and
the comparison sheets were inspected. The full Windows product gate passed
again against the refreshed candidate baselines. The captures remain
unapproved, and the surrounding shell still differs from Muse.

Run `verify-agent-ui.ps1 -Scope product -VitreJbrHome <JBR-with-JCEF>` from this
directory on Windows. Without approved images in
`approved-baselines/windows-jcef/`, the gate intentionally fails at the visual
comparison. `FRAGMENTS_AGENT_UI_BASELINE_DIR` can point at the tracked candidate
images for a dry run; the script warns that this is not approval. The latest
curated code, JSON, grouped-tool, and per-file review-result code passed the full Windows product gate with the tracked
candidate baseline override, including native browser, external black-box
probe, published consumer, and 140-name contract checks. Its full core gate
also passed. Accepted patches were checked and applied with Git for partial
and whole-file edits. Applied content is checked against those Git results for
accepted hunk subsets. The range-comment preview is a separate review
candidate at `screenshots/kit-range-comments-candidate.png`.

The mounted message renderer now numbers source cards across a whole turn even
when other parts occur between them. A Markdown `[n]` marker opens the matching
source through the app's existing callback; markers outside the available
source count remain plain text. The focused click test and
`screenshots/kit-citations-candidate.png` cover this behavior and its rendered
state. The capture is not an approved baseline.

## Order of work

1. Resolve browser tab behavior, then implement real controls and the Muse
   shell features that have specified destinations. Do not add inert buttons.
2. Recapture the actual product at both window sizes and inspect complete
   windows, inline card, and expanded pane. Fix the visible differences.
3. Review the candidates with the user; store only approved images as release
   baselines. Run the default product gate without a candidate override.
4. Run foreground hardware input checks on macOS. Reproduce and fix the Windows
   mixed-suite JCEF fault if it recurs.
5. Finish the curated-role interaction audit and document any intentional
   departures from the source React behavior before calling the kit complete.
