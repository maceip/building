# Browser visual baselines

`VitreNativeAcceptanceTest` captures the actual product at 1280×800 and
960×700, inline and expanded. `ApprovedBrowserVisualBaseline` compares these
four images with the approved files in `approved-baselines/<platform>/`.
Without those files the product gate fails. A candidate directory can be
supplied for local review with `FRAGMENTS_AGENT_UI_BASELINE_DIR`; that override
does not make the images approved.

The current Windows candidates are tracked in
`screenshots/windows-jcef-baseline-candidates/` so the four exact images under
review are available with this branch. The side-by-side native review sheet is
`screenshots/browser-native-review-candidate.png`. Regenerate it with
`python tools/make_browser_review_sheet.py <20.22.02 reference PNG>
screenshots/windows-jcef-baseline-candidates screenshots/browser-native-review-candidate.png`
after recapturing the native product. A local copy under
`../nucleus/nucleus-macos26/build/agent-ui-baseline-candidates/windows-jcef/`
is only a build artifact. The tracked candidates include the current 46% pane
split, inline card typography, 88dp composer, four-lane tab, full desktop
Vitre preview, and deterministic painted marker. They are review inputs,
not approvals.
The inline browser card now uses the kit's darker `subtle` surface. In the
supplied expanded Muse screenshot, an interior card pixel is about `#1B1C1C`;
the recaptured product card is `#1C1D1F` at the corresponding interior point.
The four deterministic candidates and the 1280×800 and 960×700 live-page
captures form the current review set. The loaded expanded pane now shows
the light page-title and address rows from the supplied 20:22:02 Muse capture
before takeover; navigation is read-only until takeover, and the page canvas
meets the address row without an inset. While the page is preparing, the pane
keeps the inset viewport shown in the earlier loading reference. The four-lane
tab remains because the browser spec requires it. Muse's add-tab and browser
menu actions and the surrounding shell still differ from this product.
The reference column uses the supplied Muse capture from 20:22:02. The
candidate columns use a real Vitre/JCEF page loaded from a deterministic local
server. Its content differs from the live Cerebral Valley page in the reference.
The inline card now scales a full-size Vitre frame instead of resizing Chromium
to the card; this was verified with separate live-page captures at 1280×800 and
960×700 in `screenshots/live-cv-*.png`. Those live captures are review evidence,
not baseline files, because the external page can change.
The live capture test waits for visible page pixels in both the inline card and
expanded pane before saving either image. For comparison with the two primary
Muse screenshots, use `python tools/make_browser_review_sheet.py
<embedded_browser_component.png> screenshots screenshots/browser-primary-live-review-candidate.png
--live --expanded-reference <embedded_browser_component2.png>`.
The loaded-page comparison is `screenshots/browser-loaded-live-review-candidate.png`,
generated with the supplied 20:22:02 screenshot and `--live --settled`. It
uses the card from the expanded screenshot so both columns show the later
settled state. Both sheets use the same seeded user request and browser tool
card as the reference. Before expansion, the inline preview scales the entire
painted 1600×1000 Vitre frame into the card, as in the primary captures.
Opening the pane resizes the same controller, and its settled frame updates
the inline card as in the later reference. These are visual candidates,
not approved matches.
At 960×700 the expanded page is taller relative to the card, so the card fits
the whole painted frame and keeps the page's bottom action visible. At
1280×800 it retains the reference's fuller card crop.
The Muse capture includes a chat-level "See more" control overlapping the bottom
of the card; the current browser card does not own that control. The current
expanded chrome also omits the reference's additional tab and menu actions.
The 20:21:39 takeover capture is a separate reference state: the browser tile
turns neutral gray while the card says `Paused` and `Open browser`. Product UI
tests check that transition and its handback at both window sizes. The four
image candidates cover only the normal inline and expanded states, so they do
not constitute visual approval of the takeover state.

The four tracked deterministic Windows candidates and the native review sheet
were recaptured after moving the conversation header into `AgentChatPanel` and
removing the duplicate chat window toolbar. The split-view card now uses 70%
of its chat column. The 800dp-high inline view retains the 480dp card; the
700dp-high view uses a 360dp compact card so the full user request stays visible
above it. The native test checks this in inline and split views at both window
sizes. Live Cerebral Valley captures at
both sizes and the full-window Muse review sheet were also refreshed. The
native acceptance
test now bounds the screen area of a 40 CSS-pixel fixture marker in the inline
card, which catches the old 2.45× zoom while still checking real painted
pixels. A second marker near the page bottom must remain visible in the
settled 960×700 card. Windows live captures at both sizes and both card states
passed. Four macOS ARM JCEF captures in
`screenshots/macos-jcef-baseline-candidates/` show the deterministic native
fixture at both sizes. The macOS native browser and four-lane pool tests pass
with a candidate visual override. Foreground physical input is unverified.

The opt-in live-page capture now checks that the full user request and browser
card fit in the conversation before saving each image. The earlier tracked
960×700 inline live capture clipped the request; the refreshed capture passes
that check. The review-sheet crop shows the compact 360px card at its native
size instead of enlarging it to the 480px reference width.

The comparison excludes the live page pixels, the inline page preview, and
the random local-server port and URL text. It checks the remaining app pixels
with a per-channel tolerance of 8 and allows at most 1 changed pixel in 300.
Native tests separately check actual page paint, input size, session identity,
takeover, navigation, and DOM actions. The mask cannot hide a missing browser
frame because those native assertions run before baseline comparison. The
deterministic page also paints a fixed orange marker; the native test requires
that marker in the inline thumbnail, expanded page, and expanded chat card.
This checks recognizable page content rather than accepting a solid background.

After visual approval, copy the four reviewed candidate PNGs into
`approved-baselines/windows-jcef/`, remove the local baseline override, and
run `verify-agent-ui.ps1 -Scope product` with a JCEF JBR. A macOS JCEF capture
needs its own approved baseline in `approved-baselines/macos-jcef/`; the Windows
candidate does not approve macOS rendering.
