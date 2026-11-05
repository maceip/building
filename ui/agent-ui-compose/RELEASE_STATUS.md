# Agent UI release status — 2026-10-27

## Current gate snapshot — 2026-10-28

Commit `8c09769` passed the full Windows product gate with the tracked candidate
browser images supplied explicitly. That run included the mounted panel and
conversation tests, native Vitre browser and four-lane pool acceptance, the
separate-process window probe, the published consumer, and the 140-name
contract (81 component renders, 59 icon geometries, 46 curated roles). The
loaded browser pane now shows the reference's light page-title and address
rows before takeover; those rows stay mounted during navigation, and the
external probe checks their pixels and Back/Forward/Reload clicks. Windows live
Cerebral Valley captures passed at 1280×800 and 960×700.
On macOS 27.0.1 ARM, native browser and four-lane pool acceptance passed on the
same Kotlin code with recaptured candidate images. An earlier native browser
run at `7d4c9dc` timed out once waiting for a repaint after a page background
change, then passed on rerun. That intermittent timeout remains a test
reliability issue.

The candidate image override is not visual approval. Browser captures for
1280×800 and 960×700 on Windows and macOS still need user review before moving
to `approved-baselines/` and running the product gate without an override.
Foreground physical input on macOS 26 has not been verified.

This kit is one `fragments.agentui` API in `:agent-ui-compose`. The four macOS
workspace surfaces remain Agent & Computer, Data Multi-Buffer, Mobile Companion,
and Diffity Chrome. The browser is an inline chat card and an expanded contextual
pane, never another workspace surface.

## What is implemented and checked

| Area | Evidence | Limit |
|---|---|---|
| 140 requested names | `COMPONENT_DECISIONS.csv` maps 81 components and 59 icons to 38 curated composable roles, eight `ChatTool` variants, and semantic `AgentIconId` values. The contract gate checks exact coverage, uniqueness, and that every mapped role exists. A public-signature test rejects source-family models and palettes. The published JAR and compile metadata exclude source-family ports; a separate consumer build composes all 38 mapped composable roles and eight tool variants. Agent Elements `ToolGroup` maps to `AgentToolGroup`; signoff `ToolCallTimeline` maps to its own `AgentToolTimeline`. The timeline renders ordered calls, phases, details, timing bars, controlled disclosure, keyboard movement, failure expansion, nested subagent calls with approval routing, coalesced completion announcements, and a keyed lazy list beyond 150 top-level calls. The panel and journal carry both roles. | Source-family copies remain available only through the separate source-port module. Aliases and subparts are not separate curated leaves. Consumer composition is not complete behavior parity. Timeline motion and host renderers have later coverage below; an animated-frame capture and OS reduced-motion check remain. |
| Source ports | `ComponentRenderTest` has 81 independent Compose render cases. The curated gallery exposes five sections, and `CuratedComponentsUiTest` exercises inbox, trigger, single and batch approval, diff, source, suggestion, question, task, and hold-to-stop controls. It also checks typed content for all eight curated tool variants and single-shot Bash/Edit approval. The shared composer test checks arrow-key selection, Enter, Escape, and reopening a menu after an edit. The tool disclosure test checks stable text geometry, Button role, expanded/collapsed state, and Enter activation. | The 81 render cases are smoke tests, not complete React behavior or accessibility parity. |
| Icons | `IconParityTest` renders 59 cases; `verify_icon_geometry.py` compares their shapes with pinned React SVG source. | Motion and hover treatment are not checked by SVG geometry. |
| Conversation | Runtime tests cover explicit multi-agent routing, ordered events, replay, and preserving edits during send. A panel UI test uses an injected picker to attach, remove, and send a selected file through `ConversationHandle`. An agent capability controls whether the attach button appears, and the runtime rejects unsupported files before opening a turn. The product hides attachment because its model transport currently accepts text only. Product tests cover two agents, streaming, remounted drafts, slash/mention routing, approval callbacks, replay of both agents' turns and tool parts after restart, and continued projection of a streaming turn after remount. A cold restart now marks a run with no backing model message as Interrupted, clears its streaming cursor, shows the status in the panel, and durably records the recovery. | The product cannot send file attachments until its model transport supports them. The journal stores event text and metadata; image pixels and attachment bytes remain external. Journal retention controls remain open. |
| Browser | Vitre native acceptance uses a deterministic local page. It checks one controller across inline, expanded, takeover, release, and return to inline with DOM state intact. It clicks the native page and checks input is blocked before takeover, reaches Chromium during human control, then is blocked after release. The native canvas gains Compose focus on takeover and loses it on release. An AWT key event sent through Vitre's surface changes the focused page input. It also checks planner click and type actions against the real DOM after approval, a request and visibly painted page at another local path, an unchanged DOM after denial, a queued click paused during takeover, and rejection of a hallucinated handle. The expanded pane has a site tab and native Back/Forward/Reload controls enabled during human takeover; the test drives all three and checks the resulting URL, pixels, and reload request. The product starts without seeded demo DOM handles. The adapter reads normalized boxes for interactive `@e` handles from Vitre's DOM registry. The product renders reticles over those boxes and shares hover state with the chat browser card in both directions. The native test checks boxes and reticles while the chat badge stays hovered and the real page scrolls out of view and back; the product UI test checks hover callbacks and clears stale targets after navigation. Vitre stays mounted once in the workspace while the card and pane supply its position. | Physical OS key delivery through the full Compose event path is unverified. Compose's synthetic key events carry no native AWT character for Vitre. A post-navigation JCEF JavaScript callback was intermittently unavailable; navigation is checked with the server request, store URL, and page pixels instead. |
| Product layout | Compose tests check the card's 16:10 viewport, a visible composer, one-line subtitle, and removal of file chrome at 1280×800 and 960×700. At 1280×800 the inline card matches the Muse capture's measured 480px outer width, 24px inset, 60px icon, and 54px preview action; at 960×700 it uses a 360px compact card to keep the full user request visible; the action has Button semantics. The refreshed review sheet measures the reference and current title text at 79px and 80px wide, and subtitles at 342px and 349px with the same one-line truncation. A sampled dark action pixel is RGB (80, 81, 84), within two levels of the reference's (82, 83, 85). The expanded pane follows the reference's dark title and site-tab rows, light page-tab and navigation rows, and flush browser canvas. Its left edge sits at 45–47% of the window width at both tested sizes, matching the reference's roughly 46% split. Native JCEF captures wait for page pixels and check that the input retains its size after moving into the expanded pane. | The surrounding conversation and the deterministic local page differ from the Muse screenshots. The current browser chrome omits the reference's additional tab and menu actions because those interactions are not implemented. Pixel baselines still need visual review and approval; these measurements are not release approval. |
| Build | `verify-agent-ui.ps1 -Scope product -VitreJbrHome <JBR with JCEF>` passed the selected contract and consumer compile before the visual baseline requirement was added. The image comparison passed a full product run against separate candidate files, including recovery and browser-step projection. The planner-step acceptance class passed four additional forced runs after one earlier combined-gate timeout; that timeout's cause is unknown. The native test exposed a stale 432×270 inline frame after expansion. On Windows, the app starts JCEF with GPU compositing disabled before Vitre adopts it. Three further fresh-process native runs passed with forced test execution. | The default product gate fails until candidate screenshots are reviewed and placed in `approved-baselines/windows-jcef/`. Several mixed-suite JCEF test processes exited with Windows heap status `0xC0000374`. The native class now runs in a separate Gradle test process and passed three forced isolated runs plus a fresh full product gate; the underlying heap fault remains unexplained. Native macOS JCEF and a longer repaint soak remain unverified. |

Browser planner records become ordered `ConversationPart.BrowserStep` entries in the journal. A step with an originating agent ID attaches to that agent's latest browser turn. A legacy step without an agent ID attaches only when one agent has a matching browser turn; it stays unassigned when the session is shared by multiple agents. Product tests cover both agents, event origins, a step arriving before its browser card, and replay after restart. The chat step badge highlights a live `@e` reticle only when the current URL and handle label still match; stale targets remain visible as history. The native test covers a real typed input step and chat-to-viewport hover. The product currently exercises agent attribution through the browser store and projector; a model-driven multi-agent browser run is not yet an end-to-end test.

The product's expanded pane and inline chat card now use one curated `AgentBrowserPlaybackBar` for LIVE, previous/next, and step scrubbing. Vitre captures encoded page pixels after planner actions; history displays those recorded pixels while the live controller stays mounted. The native test changes the live page color, checks the older recorded color in history, uses the inline controls, reopens the same recorded frame, and returns to the same native controller. A step without captured pixels explicitly says its frame is unavailable. A curated-panel test covers inline scrubber semantics and keyboard arrows. Captured frames are now limited to the newest 32 images and 32 MiB per browser store; older steps retain their text, URL, and order while their pixels are released. A store test covers both budgets, zero retention, and scrubbing to an evicted step. Step metadata is still unbounded and frames are not persisted to disk. Candidate screenshots at both tested window sizes show a bare local test page and a sparse conversation, unlike the supplied Muse captures. They prove geometry and native session behavior only; they are not a faithful visual baseline.

The inline card and expanded browser now share `AgentBrowserDescriptionOverlay` for the displayed step's thought and action result. It appears over the live browser when the agent controls the page and over the recorded frame in history. A panel test checks description updates during scrubbing; a product test checks inline, expanded history, and expanded live states. A 1280×800 capture exposed that bottom-follow scrolling clipped the browser card header after step projection, so a turn with recorded browser steps now anchors at the start of that turn while ordinary turns still follow the bottom. The product test checks the entire card stays within the timeline. This does not establish backdrop blur parity with the Magentic reference.

The expanded pane now mounts Vitre's four-lane `FramePool` in a tab beside the single page. It accepts two to four user-entered URLs with distinct origins, runs Navigate/Extract workflows, labels each visible physical lane with its assigned workflow, and exposes a collapsible Results, Lanes, and Network drawer. A fresh-process Windows JCEF test reaches four independent local HTTP servers, observes four completed titles, lane labels, and network entries, closes and reopens the drawer, and verifies that returning to the site tab preserves the original single-page controller. A 960×700 product test checks that all four URL fields remain within the pane and closing the drawer returns at least 100px of height to the browser grid. The pool is not yet verified on macOS, and its workflows are not persisted across tab closure or app restart.

The expanded product pane now exposes Maximize and Return to split; the native test checks that one Vitre controller survives both transitions. Its Action Guard offers Deny, Approve Once, and Always Allow for the pending action kind on the full web origin (scheme, host, and port). For navigation, the permission is checked against the destination URL's origin. The native test checks suspension and reuse; a separate 960×700 product test checks that all three decisions stay visible without overlapping. An `:ui` test verifies that a different action kind, URL scheme, or navigation destination still requires approval.

The conversation timeline now follows the bottom of an oversized streaming turn, stops following when the reader scrolls away, and returns to the newest output through Jump to latest. A desktop Compose UI test exercises all three states in a 740×420 panel.

The Windows terminal runner now starts Git Bash directly and writes commands through standard input, preserving shell quoting. Output readers poll pipe bytes so timed-out processes return promptly even when Windows keeps a killed process's pipe read blocked; they drain excess output and cap a single line at 1 MiB. All 154 `:ui:test` cases pass on Windows, and the product gate now runs that whole suite. Native macOS terminal execution remains unverified.

The curated button now exposes Button semantics and responds to Enter after focus. All eight curated tool variants have direct content checks, and Bash/Edit pending approval hides its actions after one decision. Small diffs parse before the first frame; large diffs still parse on `Dispatchers.Default`. Product chat and browser-step observers register at session startup, with a new preexisting-message acceptance case. The selected product gate passed with these changes and candidate image baselines; this does not approve those images. An additional combined run ended with the same intermittent Windows JCEF heap exit, so native stability remains open.

A light/dark pixel test now checks that code and terminal blocks use the kit palette. A full-width pixel check covers all eight tool variants; the Plan and Thinking cards were visibly inconsistent in the rendered gallery and now fill the same tool column. Composer menus, attachment actions, and send/stop actions expose Button semantics. These checks cover specific styling and keyboard behavior, not complete screen-reader or original React parity.

The hold-to-stop control is focusable and has a Button role. A UI test holds Enter through the configured duration and verifies that the stop callback fires once. Needs-You inbox rows also expose Button semantics while retaining their selected-item callback.

The curated interaction gate now also checks reasoning disclosure, error retry, image lightbox close, and identity-card connect against their public callbacks. These are focused behavior checks; they do not establish full parity for all 81 named source components.

The mounted composer now uses the reference's single-row 88dp pill layout and sampled dark surface color (RGB 49, 49, 49). When the product's text-only transport cannot attach files, its plus control opens the real slash-action menu; the attachment control still appears for capable agents. Product tests check that the plus action opens the browser, typed slash and mention actions still route, and controls stay aligned at 1280x800 and 960x700. The microphone shown in the Muse reference is not present because the product has no voice input transport. The four native Windows candidate screenshots were recaptured after this geometry change and reviewed against the Muse sheet. A fresh full product gate passed against these candidate files. They remain unapproved, and the surrounding conversation and local test page still differ from the reference.

A live Windows JCEF review exposed that sizing Chromium to the 432×270 inline card made the Cerebral Valley login page reflow and scroll. The card now displays Vitre's painted pixels scaled from a 1600×1000 offscreen viewport, while the same controller resizes into the expanded pane for takeover. The native acceptance test checks the full-size inline frame, scaled input pixels, expanded frame ratio, and one controller. The primary Muse card screenshot's blue button measures 89 pixels; the current live 1280×800 product capture measures 91 pixels. Separate opt-in live-page captures at 1280×800 and 960×700 are review artifacts, not approved baselines; the deterministic fixture still supplies the automated comparison.

The first narrow expanded live capture showed a black page because the capture test waited for the page title but not for painted pixels. The opt-in capture now waits for visible login-form pixels in both card and expanded pane. Fresh 1280×800 and 960×700 captures passed, including a painted narrow expanded view. `screenshots/browser-primary-live-review-candidate.png` compares them with the two supplied primary Muse screenshots; the reference expanded pane was still loading, so its page content cannot be used for pixel equality.

With the 1600×1000 inline viewport and rounded preview border, the full Windows product gate passed against local candidate baselines. It included the published consumer catalog, all 81 named component render cases, all 59 icon geometry cases, product layout at both sizes, and native single-page and four-lane Vitre acceptance. The candidate images were refreshed from that run after it passed; no approved baseline exists yet.

The separate published-artifact consumer also now exercises the composer's injected file picker, attachment send, and removal callbacks. Its focused JUnit test passed. This closes the consumer attachment lifecycle gap for that path; the broader interaction and accessibility matrix remains open.

A fresh gate initially found that the journal-replay test sometimes restored a seeded demo browser into the same `main-browser` session as the saved product turn, adding an unrelated browser step. The product app constructs an unseeded browser store. The replay fixture now does the same, and both the focused replay test and the full Windows product gate passed afterward against the local candidate baselines. The screenshot baseline still needs approval.

A separate-process Windows probe now launches the actual product with a fresh profile and local JCEF page, captures its native window through Win32, clicks the inline browser action through the AWT canvas, and verifies the expanded pane paints the same fixture. It passed on Windows at a 2720×1720 physical window (1360×860 dp at 200% display scale). The full Windows product gate passed with this probe included and the local candidate baseline override. The probe fails if the initial screenshot already satisfies the expanded assertion. The probe now also posts pointer events to the real AWT canvas and checks that a page click is blocked before takeover, changes the page during user control, and is blocked again after release. This is external process input, but physical input through a foreground desktop, URL-bar typing, keyboard, multi-agent routing, and macOS input still need coverage; in-process Compose tests cover those contracts at narrower scope.

The product gate now runs the Vitre native acceptance classes as separate Gradle `Test` tasks, including `nativePoolAcceptanceTest`, and requires each JUnit XML result. All test tasks are forced to execute on each gate run, so Gradle's up-to-date cache cannot turn an old green report into current evidence. A fresh full product gate passed with candidate baselines after the pool was mounted. The prior Windows heap failures in mixed JCEF test executors remain a release investigation item.

The supplied 20:21:39 takeover capture shows a neutral gray browser tile, a `Paused` card subtitle, and an `Open browser` action while the expanded header says `You're in control`. The mounted product now derives all of these from its browser control state, including the icon colors and the empty chat's starter card. Product UI tests exercise takeover and handback at 1280×800 and 960×700 and sample the rendered tile color. The surrounding window still needs visual baseline approval.

After the shared browser description and browser-step scroll change, a fresh full product gate passed again with the locally reviewed candidate images. The 960×700 visual comparator initially failed when anchoring every browser turn changed the simple card's position; the behavior was narrowed to turns with recorded browser steps, and the candidate images were refreshed from fresh native Vitre captures. This remains a dry run because the candidate images are not approved baselines.

## Release work still required

1. Exercise selected curated components through interaction and accessibility
   cases, especially tool expansion, diff selection, approval batches, inbox,
   kill switch, composer attachment lifecycle, and keyboard navigation.
2. Compare product screenshots at both window sizes and expanded-browser mode
   with the user supplied references. Commit only approved visual baselines.
3. Add retention controls for the product's local event journal.
4. Investigate the intermittent mixed-suite Windows JCEF heap exits. Three
   fresh isolated 20-cycle resize and pane-switch soaks passed, but their
   result does not explain the earlier heap fault.
5. Verify the four-lane pool on macOS and decide whether its workflows need persistence.
   Define retention or persistence for step metadata and any history pixels needed
   beyond the current 32-frame, 32 MiB in-memory limit.
The 81 render checks must never be described as 81 complete behavior conversions.

## Grouped tool role

Agent Elements `ToolGroup` maps to the curated `AgentToolGroup` role. The
published consumer renders it and routes a
child approval by ID; the conversation panel test does the same. The journal
round-trips nested groups. A Compose preview captures the expanded group and
checks that collapse and expansion preserve the summary position and that
approval changes its status. The full Windows product gate passed with the
tracked candidate browser baselines. The final preview status transition was
then checked in its focused UI test. The visual capture is
`screenshots/kit-tool-group-candidate.png`, awaiting design review. Signoff's
chronological `ToolCallTimeline` now maps separately to `AgentToolTimeline`.
Its published consumer and panel tests route call approvals, the journal
round-trips timing and phases, and Compose tests cover disclosure, keyboard
movement across 160 calls, error expansion, and a stopped clock. The visual
candidate is `screenshots/kit-tool-timeline-candidate.png`. Calls started by a
subagent nest under the parent call, waiting approvals open the subagent unless
the user collapsed it, keyboard navigation reaches the nested call, and the
published consumer routes its approval by call ID. Journal replay preserves the
hierarchy. New completions and failures coalesce into a polite live-region
announcement after 300ms; restored history is not announced. Exact React
motion and per-tool rendering hooks remain open.

The full Windows product gate passed after nested subagents and coalesced
announcements were added, with the tracked candidate JCEF baselines supplied
explicitly. It includes the published consumer, panel and journal tests, native
browser acceptance, black-box product probe, 81 source render cases, 59 icon
geometry checks, and the 140-name mapping check. The candidate images are still
awaiting design approval.

## Curated approval card

`AgentApprovalCard` is now a kit-owned single bordered surface. Its argument
preview preserves supplied lines without the source-port's generated line
numbers. It keeps the existing one-shot `ChatApprovalChoice` callback used by
the panel and approval batch, adds optional denial feedback and argument edits,
scopes Y/N shortcuts to the card, and requires a second explicit press for a
critical approval. Tests cover those transitions and action visibility at 320px;
the separate published consumer checks the argument and feedback path. The
wide and narrow review captures are `screenshots/kit-approval-card-candidate.png`
and `screenshots/kit-approval-card-narrow-candidate.png`. These are not approved
baselines. The scope review capture is
`screenshots/kit-approval-scope-candidate.png`. Session and permanent choices
now carry editable caller-supplied
argument patterns, or an explicit any-arguments scope. A suspendable handler
holds duplicate actions while it runs and permits retry after failure; the
published consumer exercises both additions. The React policy evaluator and
automatic argument suggestions are outside the kit, and a real IME session
remains unverified.
The full Windows product gate passed with the tracked candidate browser
baselines supplied explicitly. It exercised the published approval card,
product approval routing, the wide and narrow Compose previews, native Vitre,
the black-box app probe, and the 140-name contract. The approval captures remain
review candidates.

The curated `AgentJsonView` no longer delegates to the numbered-line source
port. It keeps a full-value copy action visible while folding long output,
shows a labeled 320dp scrolling viewport, and colors JSON keys, strings,
numbers, and literals. The focused UI and preview tests cover expansion and
collapse. `screenshots/kit-json-output-candidate.png` is a Compose capture
awaiting design review. `AgentCodeBlock` now replaces the numbered-line source
port in the curated API: it preserves source indentation and line text, with
language-aware token colors. A unit check covers JavaScript and JSON tokens,
and the Compose UI and screenshot checks cover the rendered code. Its review
capture is `screenshots/kit-code-lines-candidate.png`.
The full Windows product gate passed after this change with the tracked
candidate browser baselines, including the published consumer, native Vitre
acceptance, separate-process window probe, and 140-name contract. Candidate
baselines still require user approval.

## 2026-10-28 product review

The product transport now projects existing user turns from `AgenticChatStore`
into the mounted conversation in source order. It suppresses user turns already
restored from the runtime journal and the duplicate created when a new runtime
send reaches the backing store. A product test covers first mount, replay, and
a new send. The old store has no durable runtime turn ID, so replay pairing is
currently by matching text occurrence; a future store schema should carry the
runtime ID directly.

The deterministic and live Vitre captures now use the supplied browser request
immediately above the browser card. The shared panel uses the sampled teal
user-bubble color and hides author labels when only one assistant is present.
The live thumbnail keeps the desktop-size Vitre controller and scales its
complete frame into the card before expansion. Once the controller resizes
for the expanded pane, the card shows that settled frame.
`screenshots/browser-loaded-live-review-candidate.png` compares the settled
card and expanded pane with the supplied 20:22:02 capture. These are
review candidates; the reference still has page-tab and menu controls that
the product does not implement, and Windows captures cannot approve macOS
rendering.

The fresh Windows product gate passed against the local candidate baselines
after these changes. It forced all tests, checked the published consumer,
81 named component render cases, 59 icon shapes, product routing and composer
behavior, native Vitre continuity, and the separate-process window probe.
Candidate baseline use is explicitly a dry run, not visual approval.

The native page title now replaces the browser tab's placeholder after the
page loads. The JCEF acceptance fixture starts with `Loading page` and waits
for the real document title in the product tab. An attempted external takeover
probe could capture the product but found `Windows Default Lock Screen` as the
foreground window; pointer delivery through that locked desktop is not
verified. The separate-process product gate retains its previously passing
inline-to-expanded click and paint check. A later Win32 event audit found that
the posted coordinates were shifted by the AWT bridge. Correcting that mapping
let the separate-process probe exercise takeover, page pointer input, and
release against a color-changing Vitre page. Foreground physical input remains
unverified because the Windows desktop was locked during the attempt.
The fresh Windows product gate passed again with the native title assertion
and local candidate image override.

The 81 source-name render matrix no longer exempts visually hidden exports
from an assertion. `LiveRegion` must expose a polite live-region label, and
`VisuallyHidden` must expose its label while occupying no visible area.
The focused parameterized class passed with these checks. This strengthens
accessibility evidence for those names; the other render cases remain visual
smoke tests rather than complete behavior parity.

The curated Conversation gallery now opens with its browser card fully inside
the visible timeline. Its previous sample event order placed the card above
later review parts, so bottom-follow scrolling cut off the card header in the
first preview frame. A gallery UI test checks the whole card's bounds after
opening. This changes preview data order only; product conversation ordering
still follows its real events.

The full Windows product gate passed at `74880ab` against local candidate
baselines after the painted-page and gallery checks were added. The four
deterministic native captures were then refreshed with the painted marker and
tracked under `screenshots/windows-jcef-baseline-candidates/` alongside a
native comparison sheet. They remain candidates pending the expanded-chrome
design decision and visual approval. A fresh full Windows product gate passed
with `FRAGMENTS_AGENT_UI_BASELINE_DIR` pointing at those tracked candidates;
the override remains a review run, not baseline approval.

The catalog audit separated signoff `Img` from the Agent Elements lightbox.
`AgentInlineImage` now renders image content inside a message, and
`AgentImageLightbox` remains the separate dismissible overlay. The panel calls
the curated inline role, and the published consumer and product UI tests cover
the distinction. This changes the curated role count from 34 to 35.

The Markdown renderer provider is a separate scoped capability from rendering
one Markdown string. The public kit now exposes `AgentMarkdownRenderer` for a
subtree and accepts a renderer on `AgentMarkdown` for one call. Nested
`AgentUiLabels` merge outer labels with local overrides, and `AgentUiLabel`
reads the resolved value. A published consumer test exercises all three
paths without importing source-family APIs. This changes the curated role
count from 35 to 36.

The fresh Windows product gate passed with the external handoff probe required.
Its native captures sampled 24,892 blue page pixels after a view-only click,
24,890 green pixels after the click during takeover, and 20,835 green pixels
with zero red pixels after a click following release. The probe posts Win32
mouse events from a separate process into the launched product's AWT canvas;
foreground hardware input and macOS remain separate checks.

The external probe now follows a real fixture link to a second Vitre page,
clicks Back and Forward in the expanded toolbar, and checks the painted page
after each action. It clicks Reload and requires a new HTTP request from the
fixture server before returning to the first page and releasing control.
This covers the toolbar from outside the app process; URL-bar typing and
foreground hardware keyboard input remain open.

The isolated native acceptance test now replaces the expanded address field
with the local fixture URL and presses Enter while the user controls the page.
It requires a new HTTP request, the expected page color, the updated session
URL, and the same Vitre controller. This is Compose-injected text and key input;
foreground OS keyboard delivery through the external product window remains
unverified. The native test passed against candidate visual baselines; the
default run still fails at its intentionally missing approved-baseline gate.

The curated approval batch now binds its local decision to the current request
value and drops it when that request leaves the batch or changes. A Compose UI
regression test removes an approved request, adds a new request with the same
ID, and verifies it can be decided again. All 22 focused curated UI cases and
the full Windows product gate passed with local candidate visual baselines.
This does not approve those images or establish interaction parity for every
source component.

Batch approval now includes only requests with an explicit Low, Medium, or High
risk. Critical and unclassified requests stay on their individual cards; a
Critical request still requires a second press. The published-consumer test
checks the mixed batch and the contract gate requires that test.

The unified gallery's conversation Review action now opens the curated
`AgentDiffReview` rather than the source-family signoff port. Its UI test scrolls
the review row into view, opens the pane, accepts the hunk, applies the result,
and closes the pane. An opt-in 1100×760 gallery capture shows the actual split
view with the diff controls visible.

The curated source callback now resolves a selected citation by the source
port's returned object identity. A UI test renders two equal citations, clicks
the second numbered source, and verifies the caller receives the second source
instance. This keeps duplicate citations attached to the correct conversation
part.

Question submission is now controlled by the unified panel while the command
is pending. The question card shows a submitting state without active form
controls, then restores the selected answer for retry if the transport rejects
the command. On acceptance, the runtime records the answer and the panel shows
the answered summary. A panel UI test exercises rejection, retry, and acceptance
with two deferred transport results; it passed with the other seven panel cases.

The curated diff review now matches the React decision toggle for each hunk:
clicking a selected Accept or Reject button returns that hunk to pending, and
both controls expose selected semantics. A two-hunk UI test verifies independent
decisions, counts, and split-layout continuity. Large patches now reset their
parsed rows by patch and layout and disable Apply while parsing; a separate UI
test replaces one large patch with another and checks that old rows and
decisions disappear. File-wide Accept and Reject actions now set every hunk in
the selected file, while individual hunk controls can override that result.
A two-file UI test checks that the other file stays independent and the
submitted map and counts match. Keyboard shortcuts were not covered by these
checks.

The shared composer's mention callback now receives the complete draft with
the selected token removed. Routing a mention to another agent preserves text
on both sides of that token, including when the caret is inside its word.
Without a routing callback, selecting a mention replaces the whole word and
uses the following whitespace instead of adding a second space. Focused
product and composer UI tests cover both paths with a middle-of-draft caret.
The `ChatInput.onMentionSelected` callback now takes `(ChatMention, remainingDraft)`;
source consumers using its earlier one-argument form must update.

The native Vitre acceptance test has an opt-in `FRAGMENTS_AGENT_UI_SOAK_CYCLES`
loop. Each cycle closes the expanded pane, reopens the inline card into split,
maximizes, restores split, checks the same controller, and requires the page to
paint. With the tracked Windows candidate baseline directory supplied, three
fresh JCEF test JVMs passed 20 cycles each (53s, 32s, 32s). Two runs logged a
Chromium `Timeout of new browser info response` diagnostic, but no assertion
or JVM heap exit occurred. The earlier mixed-suite `0xC0000374` failure is not
explained by this isolated soak; macOS and foreground hardware input remain
unverified. The standalone native Gradle task now reruns instead of using a
cached prior result.

`screenshots/full-window-muse-review-candidate.png` puts the supplied Muse
inline and expanded full-window references beside existing 1280x800 live Vitre
product captures. The product has a working native page and a visible inline
card, but its sparse activity rail, chat header, page-tab actions, and browser
menu differ visibly from Muse. The capture uses a fresh fixture conversation,
so the surrounding messages also differ. This sheet is a review candidate,
not an approved baseline or evidence of macOS visual fidelity. It can be
regenerated with `tools/make_full_window_review_sheet.py` using the four
source image paths.

To repeat the dry run from `nucleus/` on Windows:

```powershell
$env:FRAGMENTS_AGENT_UI_SOAK_CYCLES = '20'
$env:FRAGMENTS_AGENT_UI_BASELINE_DIR = (Resolve-Path ../agent-ui-compose/screenshots/windows-jcef-baseline-candidates).Path
.\gradlew.bat :nucleus-macos26:nativeBrowserAcceptanceTest --tests 'fragments.product.VitreNativeAcceptanceTest.inline and expanded views keep one native page through takeover' -PvitreJbrHome='<JBR-with-JCEF>'
```

The curated diff review now allows one submission per review state, matching
signoff-ui's guard against a double click or repeated shortcut. Changing a
hunk or file decision re-arms submission; selecting the same file decision
again does not. A focused Compose UI test covers first submission, disabled
repeat, changed decision, second submission, and unchanged file decision. The
`CuratedComponentsUiTest` class passed with 27 executed tests. Comments and
keyboard navigation are still missing from this port.

The two primary Muse captures show the full browser page reduced into the
inline card. The product was applying an extra 2.45× graphics-layer zoom,
making the login form much larger before expansion. `NativeBrowserThumbnail`
now scales the complete 1600×1000 painted frame. A native fixture assertion
checks the projected area of its 40 CSS-pixel marker; the JCEF acceptance test
passed at 1280×800 and 960×700. Fresh live Cerebral Valley captures passed at
both sizes, and the expanded capture now waits for the same controller to
resize to the pane before saving. The primary and later settled comparison
sheets, full-window sheet, and four deterministic Windows candidates were
refreshed. They remain review candidates, not approved baselines.

The settled 960×700 card initially cropped off the login form's bottom action
because the expanded pane's page frame is taller than the 16:10 card. The
thumbnail now fits frames narrower than 1.18:1 inside that card. A new
bottom-of-page fixture marker remains visible in the narrow expanded card,
and a fresh live Cerebral Valley capture shows the Continue action. The
native acceptance test passed again; the four deterministic candidates and
comparison sheets were refreshed for this narrow state.
The complete Windows product gate then passed with the tracked candidate
baseline override, including the outside-process Vitre probe, published
consumer, 81 component render cases, and 59 icon geometry cases. This dry run
does not approve the candidate images.

The curated diff review now supports local or controlled viewed-file state.
Marking a file viewed folds its hunks; Show file restores them, and the submit
result includes the viewed file IDs. Changing a viewed mark re-arms the
single-submission guard. The Compose UI suite checks both controlled and
drop-in local state, and the separate published-consumer test compiles and
submits viewed IDs through the curated public API. The core gate passed after
the API change; the final focused Compose class passed 28 tests after extending
its local-state case. Line-range selection and keyboard review remain open.
The 1100×760 curated gallery was captured after marking its review file viewed;
`screenshots/kit-review-viewed-candidate.png` shows the compact file header and
folded review without shifting the neighboring conversation column. This is a
preview artifact, not a product visual baseline.

The diff review now has curated comment models and controlled or local comment
state. Reviewers can add, edit, and delete file, hunk, and single-line comments;
the submit result carries them with file and line identity. The Compose UI test
checks the full edit and submit path, and the published-consumer test uses the
same API without importing source-port types. The gallery captures
`screenshots/kit-review-comment-candidate.png` for layout review. The React
source also supports multi-line selection and keyboard review; those interactions
are not yet in the Compose port.
The full Windows product gate passed with the tracked candidate baseline
override after this change. That run includes the 81 source component renders,
59 icon geometry checks, published-consumer test, native browser acceptance,
and outside-process browser probe. The visual images remain candidates.
JCEF logged one frame-info timeout during the native run even though its
acceptance assertions passed; the mixed-suite Windows JCEF issue remains open.

The curated diff review also accepts controlled decisions and reports changes
through curated `ChatHunkDecision` values. An external decision change re-enables
submission, while the component still manages its own decisions when no
controlled map is supplied. The Compose test checks both directions and the
published-consumer test checks the public signature. Keyboard review remains
open.

The curated diff review now accepts `ChatFileChange` values with old and new
contents or a per-file patch. Content changes produce stable hunks and line
numbers off the UI thread for large inputs. Whole binary files and edits with
no text hunk can be decided as one file item. Foundation tests cover separated
hunks, repeated paths, insertions, deletions, large replacements, missing final
newlines, patch markers, and randomized replay of changed lines. The Compose
test checks a content edit and a binary file in the same review; the published
consumer exercises the new overload. React's richer applied-file result,
accepted patch output, multi-line selection, and keyboard review remain open.
The full Windows product gate passed after the file-change input and parser fix,
using the tracked candidate visual baseline override. The focused core gate
passed after caching parsed file changes across unified/split layout switches;
a large-file Compose test verifies that the decision survives the switch.

The Agent Elements tool audit found that the Compose Todo card had invented a
local checkbox toggle, while the React source displays agent-owned pending,
in-progress, and completed states. The curated `ChatTodoItem` model now carries
those states, and the card has no click action. The React Plan tool also has an
expandable plan document and a single approval action; `ChatPlanDocument` now
exposes that path through `ChatTool.Plan` while the app's existing checklist
mode remains available. UI tests check expansion, one approval, and read-only
todo rows. The product journal round-trips both new models, and the published
consumer exercises the plan path. Candidate gallery captures show collapsed and
expanded plan states; they are not visual baselines.
The Windows product run passed the Compose, native Vitre, and outside-process
browser stages. Its separate consumer build initially failed on a test-only
import; after correcting that import, the consumer build, artifact-boundary
check, and product contract verifier passed. A fresh full core gate then passed
on the final code. The product image baselines remain candidates.

The Agent Elements Search audit found that the Compose tool discarded result
source and date metadata and exposed an expandable row even with no results.
`ChatSearchResult` now carries source, title, and date through the public kit,
source renderer, and conversation journal. Existing string result callers stay
valid. The tool shows a bounded scrolling result list, and its disclosure state
is keyed by tool ID. Desktop UI tests cover metadata, empty streaming state,
and state reset; the published consumer exercises both populated and empty
states. `screenshots/kit-search-results-candidate.png` was captured and
visually inspected; it is not an approved baseline.
The Windows product script passed foundation, Compose, journal, desktop UI,
native Vitre, external black-box, and publication stages with candidate image
override. The first published-consumer pass failed on an incorrect test
expectation for two separately rendered text nodes. After fixing the test,
the published-consumer suite, artifact-boundary check, and product contract
verifier passed separately on the final code. The full product script was not
rerun as a single command after that test-only correction.
A fresh full core gate passed on the final files.

The signoff diff review now returns `acceptedPatch` in its public
`ChatDiffReviewResult`. The formatter includes accepted hunks, renumbers later
hunks around rejected or undecided edits, and preserves create/delete headers
and missing-final-newline markers. Foundation tests pass the emitted patch to
real `git apply --check` and `git apply`, including every accepted subset of
three separated replace, insert, and delete hunks. Desktop UI and a separately
built published consumer verify the callback. A full Windows product gate
passed with the tracked candidate visual baseline override, including native
Vitre acceptance, the external browser probe, and 140-name checks. Candidate
images still need design approval, and applied-file results, range comments,
and keyboard review remained open at that commit.

Diff review now supports contiguous line selection by Shift-click and
Shift-arrow within a hunk. The line-comment editor stores the selected new
range and any old-side bounds, displays the saved range in unified and split
layouts, and returns those bounds through `ChatDiffReviewResult`. The curated
preview has a range-review example at
`screenshots/kit-range-comments-candidate.png`. This preview is a review
artifact, not an approved visual baseline. Focused UI tests cover keyboard
extension, Shift-click, mixed old/new ranges, and layout switching; an
independently built published consumer checks the callback model. The
contract gate requires those tests. The full core gate and the full Windows
product gate passed with candidate image override after this change. The
product run included the native Vitre tests, separate-process browser probe,
published consumer, and 140-name contract audit. React's broader review
shortcuts and per-file applied content are still absent. The full-window Muse
comparison still shows shell/header/navigation drift, and macOS native input
and approved baselines remain open.

The public `ChatDiffReviewResult` now includes a per-file result with status,
decision, viewed state, accepted/rejected/pending item IDs, and content after
only accepted hunks are applied. It also reports pending count and rejected
hunk text. Patch-only input reports `content = null` instead of inventing
contents. Foundation tests compare every accepted subset of three separated
edits with a real Git-applied patch; the separately built published consumer
checks partial, unchanged, binary, renamed, and patch-only results. An
unchanged text file contributes no review item.

The diff review's focused keyboard surface supports J/K item navigation,
Shift-J/K file navigation, A/R/X decisions, U reset, C comment, V viewed,
Alt-A/R file decisions, Shift-A/R whole-review decisions, and Ctrl/Meta-Enter
submission. Active hunks scroll into a small lazy viewport; UI tests cover
navigation, decisions, comment editor, viewed state, one-shot submission,
and offscreen reveal. These tests are required by the contract gate. The
complete core gate and Windows product gate passed with the candidate visual
baseline override. The product run again included both isolated native Vitre
classes, the external window probe, published consumer, and 140-name audit.
The Windows run reported unusually long Gradle stage times while the JUnit
Compose suite recorded about 45 seconds of test-body time; the cause of the
stage delay is unknown. React's full roving-focus, context-expansion,
asynchronous-submit, and IME details still need a role-level audit. Muse shell
drift, approved visual baselines, macOS native input, and foreground hardware
input remain open.

## 2026-10-28 conversation header and split-card review

`AgentChatPanel` now owns the conversation header. It shows the selected
participant and active work or approval state from the same `ConversationHandle`
as the timeline and composer. The duplicate chat-only window toolbar is gone;
settings remains on the activity rail and the keyboard shortcut. The kit header
has an optional action slot for product-owned actions. Focused Compose tests
checked agent selection and the settings path. A concurrent sidebar stress case
failed once during the broader focused run, then passed on its isolated rerun;
it is unrelated to the chat header and is not a proven fix.

The browser card still reaches 480dp in the full inline conversation. In the
expanded split, its width is capped at 70% of the chat content width, with
smaller icon, spacing, type, and preview action metrics. A native JCEF test at
1280×800 and 960×700 checks that the card scales with the chat column and the
latest user request remains fully visible above it. Fresh live Cerebral Valley
captures at both widths show the page in the card and expanded pane. The four
tracked deterministic candidate images and the native, live, and full-window
review sheets were refreshed. They remain unapproved candidates; the reference
still has different navigation, an avatar/Invite area, a chat-level `See more`
control, and extra browser menu actions. The test fixture also has fewer prior
messages than the reference conversation.

Removing the extra chat toolbar moved the browser navigation row. The external
black-box probe now locates that row relative to the painted page instead of
sampling a fixed window percentage. It passed in a separate Windows process,
covering inline expansion, pointer gating, takeover, Back/Forward/Reload, and
release. The full Windows product gate passed on the final responsive card
with the candidate visual override, including native single-browser and
four-lane tests, the external window probe, published consumer, and 140-name
contract audit. This does not claim image approval.

## 2026-10-28 diff reviewer interaction audit

`AgentDiffReview` now accepts either a synchronous `onSubmit` or a suspendable
`onSubmitAsync`. An asynchronous callback disables submission while it runs,
prevents duplicates, and reopens submission when it completes or fails. A
failed callback shows a retry message. The separate Maven consumer exercises
the new public callback. The comment editor keeps Compose `TextFieldValue`
composition state, saves with Ctrl/Meta-Enter, and cancels with Escape. The
shortcut handler does not act while the input reports an active IME composition.
Keyboard navigation now moves actual focus to active hunk or file items after
scrolling them into view. Focus, callback state, failure retry, and comment
shortcuts have UI tests required by `verify_contract.py`.

The final Windows product gate passed with the tracked candidate image
override. It included the native Vitre session and pool tests, external
black-box probe, separately published consumer, and 140-name contract audit.
This does not prove full React interaction parity. An actual IME session
still needs verification. The candidate
images remain unapproved.

## 2026-10-28 diff context expansion

The curated diff reviewer now reveals unchanged lines around hunks when both
old and new file contents are supplied. Gap controls reveal all hidden lines
or 20 from either edge; `E` reveals 20 lines beside the active hunk. Unified
and split layouts preserve the reveal state. The gap controls wrap within a
300dp review, and an accessible row label reports old and new line numbers
after insertions or deletions. Patch-only reviews show no context controls
because their missing lines cannot be reconstructed. Compose UI tests cover
the two layouts, keyboard steps, insertion and deletion numbering, narrow geometry, and
the patch-only boundary. The published Maven consumer exercises a reveal.
The full Windows product gate passed with the tracked candidate baseline
override, including native Vitre tests, the external window probe, published
consumer, and the 140-name contract audit. The images remain unapproved.

## 2026-10-28 macOS native and live-model review

The macOS 27.0.1 ARM host ran a temporary checkout with the official JetBrains
JBR/JCEF 21.0.11 archive after its SHA-512 checksum passed. The Gradle native
task now selects `bin/java` on macOS and `bin/java.exe` on Windows. macOS native
browser and four-lane pool acceptance passed; the browser task used a separate
candidate-image comparison run. The four macOS captures are tracked under
`screenshots/macos-jcef-baseline-candidates/`, without approving them.

The initial 960×700 macOS inline capture clipped the user's message above the
480dp browser card. The panel now caps that card at 360dp in a 700dp-high
window, keeping the complete message, card, and composer visible. Native
acceptance asserts that the bubble and card both fit. The corresponding Windows
candidate images were recaptured, and the full Windows product gate passed
with the refreshed candidate override. Foreground physical input and macOS 26
itself remain unverified.

An opt-in integration test passed against the Mac's running `rtlm_server`
using `qwen3-0.6b`: two routed agent sends produced streamed nonempty replies,
completed, and replayed from the journal. The test skips when the live-server
flag is absent; it does not measure response quality or test foreground typing
into the mounted panel.

## 2026-10-28 long diff review focus

A 120-file Compose review exposed a focus jump: clicking the hunk at file 80
requested focus through the review root, which moved focus and selection back
to the first file. Hunk headers now focus their own item; file headers route
focus to the first reviewable item, and line selection focuses its hunk when
that hunk is composed. A UI test scrolls to file 80, confirms file 81 is not
composed, moves focus to file 81 with `J`, and accepts it with `A`. The focused
curated component suite passes. A foreground IME session remains unverified.
The full Windows product gate passed with the tracked candidate image override,
including native browser and pool tests, the external window probe, published
consumer, and all 81 component and 59 icon contract checks. This is not visual
baseline approval.

## 2026-10-28 curated role count and run meter

The decision map has 140 requested source names: 81 component exports and 59
icon exports. The component exports resolve to 38 curated composable roles and
eight typed `ChatTool` variants. The earlier count of 39 composable roles was
incorrect. A role check also found that an expanded live run meter omitted its
`Live` label when no model name was supplied. The heading now keeps that label
with or without a model name; a direct Compose UI test covers both states.
The full Windows product gate passed with the candidate image override. The
images remain unapproved.

The separate published-consumer catalog now asserts the shared support roles
it previously only composed: the live region has polite announcement semantics,
visually hidden text remains in the semantics tree, directive mentions render,
`SwapLabel` reaches its new state, and a disclosure closes after opening. The
focused published-consumer test passes.

## 2026-10-28 long-hunk line selection

A 160-line hunk revealed that selecting a visible line after its hunk header had
left the lazy viewport marked the line selected but left keyboard focus elsewhere.
The selection control now focuses itself on pointer, keyboard, and semantic
activation. A Compose test scrolls to line 98, asserts the header is uncomposed,
selects the line, and extends the selection with Down. The full curated UI
suite and the published-consumer core gate pass. Foreground IME input remains a
separate check.

## 2026-10-28 live browser capture correction

The tracked 960×700 live inline screenshot still clipped the top of the user's
request after the compact-card fix, despite the deterministic native candidate
showing the whole bubble. The opt-in Vitre/Cerebral Valley capture now asserts
that the request and card both fit before saving. Fresh 960×700 and 1280×800
inline and expanded captures pass. The four tracked live images and the primary
comparison sheet were refreshed where pixels changed; the sheet keeps the
360px narrow card at native size. These are review candidates, not approved
baselines.

## 2026-10-28 host clipboard contract

`AgentCopyButton` now uses Compose's host clipboard instead of writing directly
to AWT's global clipboard. An injected clipboard in both the curated UI suite
and separate published consumer receives the complete multiline value, and the
button reports `Copied` after the write. This checks the public drop-in behavior
without changing the user's system clipboard during the test. The full core
gate passes, including the separate published-consumer test.

## 2026-10-28 curated role evidence audit

`CURATED_ROLE_AUDIT.csv` now records an evidence tier and cited test file for
every mapped component role: 38 composables and eight typed tool variants.
`verify_contract.py` fails if the role inventory drifts from the 140-name
decision map or cites a missing file. The core gate passes with 46/46 roles
inventoried. Every role now has at least focused semantics or behavior
evidence. The audit document does not claim full React behavior parity.

## 2026-10-28 browser takeover and chrome review

The browser now enters `UserPendingTakeover` when Take Control is requested
during an active planner step. The 64dp striped notice stays visible while the
current step finishes; the page input shield stays in place, and queued agent
steps wait for handback. A held-planner store test and a mounted product UI test
cover this transition. Active planner steps now set the inline card to `Working`
and return it to `Completed` after the step or approval path finishes. The
held-planner store and product UI tests assert that visible lifecycle. The full
Windows product gate passed before the `Working` follow-up; its focused store,
mounted UI, and native browser checks pass after that follow-up.

The expanded browser now keeps one dark site tab above a 12dp inset rounded
viewport. The nested bright tab/address bars are gone from the agent view;
Back, Forward, Reload, and the address field appear while the user controls the
page. Fresh deterministic and live Cerebral Valley captures at 1280×800 and
960×700 are tracked as review candidates. Native Vitre acceptance checks the
viewport's position below the site tab, and the separate-process window probe
checks navigation and release in the changed layout. The published consumer
and contract stages pass: 140/140 names mapped, 46/46 curated roles with passing
cases, 81/81 source component renders, and 59/59 icon geometries. Candidate
images are not approved baselines. The macOS 27.0.1 ARM candidate images were
recaptured from commit `37d5bc2`; native browser and four-lane pool acceptance
both passed against the refreshed Mac candidates. Foreground physical input on
macOS 26 remains unverified.
