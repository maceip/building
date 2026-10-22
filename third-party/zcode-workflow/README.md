# Vendored ZCode dynamic workflow (upstream v3.15.1)

Source: https://github.com/zai-org/ZCode (Apache-2.0, see LICENSE.zcode),
`apps/zcode-cli/packages/` at commit 3c5b2276, pulled 2026-10-22.

- `dynamic-workflow/` — @zcode/dynamic-workflow: in-memory TypeScript compiler
  (virtual host, noEmit typecheck against the facade .d.ts), AST analysis
  (taint fixpoint, facade-misuse, fanout cardinality, causality/flow graphs,
  schema synthesis from ask<T> type arguments), and lowering to plain
  JavaScript with static site ids. The sandbox never executes TypeScript.
  Only runtime dependency: typescript.
- `dynamic-workflow-runtime/` — @zcode/dynamic-workflow-runtime: child-process
  vm-cell harness executing the lowered JS (channel/future primitives,
  stall/deadlock detection, stdio host protocol for ask/hole/world-read).
- `core-memory-reference/` — snapshot of upstream `core/src/memory` and
  `core/src/compact` for reference while porting the memory layer. NOT
  drop-in: upstream memory imports core model/runtime/tool internals, so
  this is porting source, not a runnable package.

Not vendored: the Rust/Duroxide port in ~/interactive-task-engine (stale
snapshot; its vendored taint.ts is byte-identical to upstream, and its
lowering predates upstream hole/channel/future/model support).

Integration target: services/agent-workflow in the building app, running
the lowered JS on the bundled on-device Node (measured 150-238 ms cold
start on D6OZUCKVEIQ4LVLV), with ask dispatched to child AgentLoop actors.
