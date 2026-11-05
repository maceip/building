# Agent UI evidence gate

Run `./verify-agent-ui.ps1 -Scope product -VitreJbrHome <JBR with JCEF>` for the
current product integration checks. `-Scope core` is useful during kit development;
it does not exercise the product or native browser. Both scopes require current
Gradle/JUnit results and an external consumer that renders and sends through
the published Maven artifact.

The script forces each test task to run, then reads separate JUnit output for
the regular Compose suite, the single-page native JCEF acceptance process, and
the four-lane native JCEF pool process.

The product scope also runs the full `:ui:test` suite, including terminal
execution, streaming, bounded output, timeouts, and browser-store state tests.
On Windows it also launches the actual product in a separate JVM with an isolated
profile and local HTTP page. `tools/product_black_box.py` captures the native
window through Win32, clicks the inline card through its AWT canvas, and requires
the expanded Vitre pane to paint that same fixture. The initial screenshot must
fail the expanded-state assertion, so removing the click cannot produce a pass.
Install `tools/requirements-black-box.txt` for this gate.

| Claim | Automated evidence | Limit |
|---|---|---|
| Requested names are present | Independent declaration inventory for 81 source components and 59 icons; one render case per component; SVG geometry comparison for icons; exact and unique 140-row decision map to declared curated roles | Presence and rendering do not establish complete React behavior or visual quality |
| Curated API is usable | Public signature test rejects source-family models and palettes; the published JAR and Gradle/Maven compile metadata exclude source-family ports. A separate Maven consumer composes all 34 mapped public roles and all eight tool variants, exercises representative callbacks, sends through the public runtime, and attaches, sends, and removes files through the published composer. The contract gate requires the consumer tests and checks future mapped roles have consumer call sites. | Source ports still exist as an explicit comparison/runtime module; consumer composition is not complete behavior parity |
| Core interactions work | Runtime routing/replay tests, panel/composer/browser callbacks, file attach/remove/send through a host picker, rejection of unsupported attachments before a turn opens, expansion geometry and disclosure keyboard semantics, and curated inbox, trigger, single and batch approval, diff, source, suggestion, question, task, and hold-to-stop actions; the five-section curated gallery has a black-box interaction test | Untested interactions and accessibility remain |
| Product uses the kit | Compose tests prove the product mounts `AgentChatPanel`, hides the unsupported file attachment control, handles two agents and drafts, routes slash/mention actions, labels recovered interrupted runs, and preserves the measured inline browser card width, insets, icon, preview aspect ratio, action height, and Button role at 1280×800 and 960×700. A cold-restart test checks orphan recovery and durable journal state while a same-store remount retains streaming. A separate-process Windows test clicks the actual inline card and verifies native browser expansion against a local page. | These tests do not approve the appearance; external toolbar, multi-agent, and keyboard actions remain to be covered |
| Native browser continuity works | Vitre/JCEF acceptance against a local page checks one persistent controller across a 1600×1000 offscreen inline thumbnail and the expanded pane, verifies the frame changes to the expanded aspect ratio, checks page clicks blocked before takeover and after release, a page click accepted during takeover, browser canvas focus gained and cleared with takeover, an AWT character delivered through Vitre to the page input, approval/denial, DOM actions, native Back/Forward/Reload during takeover, painted page pixels at 1280×800 and 960×700, and normalized bounds and reticles for `@e` targets. A held hover over a chat badge keeps its native box aligned through page scrolling. A product UI test checks chat-to-viewport and viewport-to-chat hover state. Planner steps project into chat, survive journal replay, and highlight a live native reticle on hover. A product test checks the shared current-step description in inline, expanded history, and expanded live states, including scrub updates and full card visibility. | The page is a deterministic fixture; physical OS keyboard events through the full Compose input path and visual reference match remain open |
| Four-lane browser pool works on Windows | A separate native JCEF test runs four visible Vitre lanes against four local HTTP origins, checks completed workflow titles, lane labels, and network observations, closes and reopens the detail drawer, and confirms the single-page controller survives a return from the pool tab. A 960×700 Compose test checks the URL fields fit and the collapsed drawer returns at least 100px to the browser grid. | Native macOS behavior, workflow persistence, and visual reference match remain open |

The product gate now requires four approved browser screenshot baselines, as
described in `VISUAL_BASELINES.md`. It fails closed while those files are absent.
It also requires the planner-step replay test and the native chat-badge-to-reticle
assertion; an old step with a changed URL or handle label stays historical.
It requires the native maximize/split continuity and origin-scoped Action Guard
checks, plus the narrow-pane decision layout test.
The candidate images passed the gate with a local baseline-directory override;
that is a dry run, not approval. After baseline approval, a successful product
result will still cover only the selected automated claims. The remaining
interaction/accessibility cases in `RELEASE_STATUS.md` must also pass.

For a release, keep acceptance tests and approved screenshot baselines in a
protected branch or separate QA repository that implementation agents cannot
edit. Build the candidate artifact in a clean consumer app, then exercise the
app from outside its process against a local deterministic agent and browser
server. Verify visible UI, runtime events, transport target/correlation IDs,
native browser session identity, and resulting screenshots. Mutation checks
should remove a required component, disconnect a click handler, or swap a
browser session ID and confirm the gate fails. Human review must approve the
initial visual baseline; pixel comparisons only guard against later drift.
