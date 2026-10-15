# Run the real Hono Node adapter in building

This is the upstream `honojs/node-server` project, not a replacement hello-world service. Source and tests are vendored unchanged at revision `4b47a0d8a5c12ed69a9f8493df69918f62fb61c0` (2.1.5); see `UPSTREAM.json` and `LICENSE`. The upstream tree includes HTTP/HTTPS/HTTP2, WebSockets, streaming, multipart bodies, static files, cache/compression middleware, abort and socket-lifecycle tests.

The added `package-lock.json` freezes the npm installation used for the Android acceptance run. The upstream pnpm lock remains untouched. Dependencies are downloaded from the real npm registry; they are not replaced with local mock packages. Package scripts and dependencies are trusted executable code.

```sh
npm ci
npm run build
npm run typecheck
npm test -- --run
```

The upstream `postbuild` invokes publint, which may select pnpm from `packageManager`. Install the matching pnpm in the development runtime when needed; do not silently skip that hook. Run the unchanged tests with bounded workers on Android if needed, for example `npm test -- --run --maxWorkers=2`.

In the IDE, open Developer workbench → Runtime → Open real Hono project. The source is copied into a new private workspace so you can inspect/edit it. Installation and execution require the normal explicit trust actions; opening does not run project code. Pick `npm build`, `npm typecheck` or `npm test` in the run-target list. Add `--run --maxWorkers=2` as arguments for Vitest.

Production build, typecheck and all 503 unchanged upstream tests pass both on the host and on the physical Pixel through building's actual bundled runtime. The physical test performed fresh-cache Internet npm installation and the unchanged publint/pnpm postbuild hook; it did not reuse desktop node_modules. Verified app build 114 / 3.28.4 keeps before/after source/test hashes and the actual JUnit report. This proves this pinned project's test suite, not every Node framework or package.
