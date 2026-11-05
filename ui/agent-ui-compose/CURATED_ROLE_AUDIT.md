# Curated role evidence

`COMPONENT_DECISIONS.csv` resolves 81 requested component exports to 38
composable roles and eight typed `ChatTool` variants. The 59 requested icon
exports have separate geometry cases. `CURATED_ROLE_AUDIT.csv` lists each of
the 46 component roles exactly once with a named test-case ID. The product
gate checks the inventory against the decision map, matches each case to its
source file, and requires a passing JUnit result for every row.
Some cases assert several roles together; this traceability check is not a
separate pixel comparison or complete React behavior test for each role.

The evidence tiers mean:

| Tier | Evidence level |
|---|---|
| `product` | A mounted product or conversation-panel test exercises the role. |
| `behavior` | A focused Compose test exercises a callback or state transition. |
| `semantics` | A focused test checks text, image, or accessibility semantics. |
| `tool_behavior` | The curated tool-variant tests check typed content and shared interaction geometry. |
| `render` | The separate published consumer composes the role; this alone says nothing about its full behavior. |

Every mapped role now has at least a focused semantics or behavior test. This
does not establish full React parity. Motion, overflow, and visual fidelity
still need role-specific review, especially for loading, speaker identity,
subagents, and Markdown. Timeline status now has frame-level motion and zero-
scale checks; an actual OS reduced-motion setting remains unverified. The 81
source-port render cases and 59 icon shape
checks prove only their stated coverage. The exact names and evidence tiers in
the CSV are the review inventory, not a declaration that all interactions are
finished.

Visual review candidates: `screenshots/kit-markdown-candidate.png` and
`screenshots/kit-speakers-subagents-candidate.png`, plus the source-numbering
capture `screenshots/kit-citations-candidate.png`. These are Compose desktop
captures for manual review, not approved reference baselines.
