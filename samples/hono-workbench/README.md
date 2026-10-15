# Hono local workbench

This is a **repository-owned application sample**, not upstream Hono application source and not a substitute for the separately vendored `hono-node-server` project. It actually imports the public `hono`, `@hono/node-server`, `@hono/zod-validator` and `zod` packages. The lockfile pins exact registry tarballs and integrity hashes; no framework mock or replacement server is used.

## Run

Requires Node **24 or newer** and npm. From this directory:

```sh
npm ci --registry=https://registry.npmjs.org
npm run typecheck
npm run build
npm test
npm start
# Alternatively, edit TypeScript with Node's built-in watch/type stripping:
npm run dev
```

`npm test` builds first, then runs real HTTP integration tests with `node:test`. Every server binds **127.0.0.1**; there is no option to expose it externally. The default port is 8787. Open `http://127.0.0.1:8787` in the browser **on the same device**. On Android, use the installed app's Node runtime/terminal and a browser on that Android device; another computer's localhost is a different machine.

First startup generates `.data/local-token` (32 random bytes, mode 0600). Use your IDE's file viewer to copy its value into the dashboard's bearer-token field. The token is not returned by the HTTP server, printed to logs, placed in URLs, or persisted in browser storage. Disconnect clears it from the page. The server prints the actual URL and token-file path as one JSON line.

Optional `.env` settings are described in `.env.example`. Set `WORKBENCH_TOKEN` to your own 32–256 URL-safe characters to avoid a generated token; never commit real tokens. `DATA_DIR` is relative to the launch directory unless absolute. `PORT=0` selects an available loopback port. `CORS_ORIGINS` is a comma-separated **exact** origin allowlist, never a wildcard. No CORS setup is required for the bundled same-origin dashboard.

This bearer token is **developer-local authentication, not a production security claim**. There are no accounts, roles, TLS, rate limits or multi-user isolation. Do not expose it through a public proxy. Other processes running as the same OS user can read its data and token.

## What it does

The responsive dashboard supports connecting, task creation/editing/completion/deletion/search, checked file upload/download, server status and live SSE events. Task strings are rendered as text, not HTML. A strict same-origin content security policy keeps scripts/styles local. No CDN, cloud service, database service or telemetry is used.

| Method | Route | Behavior |
| --- | --- | --- |
| GET | `/`, `/app.js`, `/style.css` | Public dashboard assets with ETags |
| GET | `/health` | Public liveness, Node version, storage type |
| GET | `/api/status` | Authenticated counts, limits and current SSE subscribers |
| GET | `/api/tasks` | Search `q`, filter `done=true/false`, `limit=1..100`, `offset=0..5000` |
| POST | `/api/tasks` | Create validated task; returns 201, Location, ETag |
| GET | `/api/tasks/:id` | Read task, conditional `If-None-Match` / 304 |
| PATCH | `/api/tasks/:id` | Partial update; requires current `If-Match` ETag |
| DELETE | `/api/tasks/:id` | Delete; requires current `If-Match` ETag, returns 204 |
| GET / POST | `/api/uploads` | List metadata / upload exactly one multipart `file` field |
| GET | `/api/uploads/:id` | Integrity-verified attachment download with ETag and SHA-256 header |
| GET | `/api/events` | Authenticated SSE ready/change/heartbeat events |

All `/api/*` operations require `Authorization: Bearer <token>` (CORS preflight is intentionally unauthenticated). Missing/incorrect auth returns 401. Task validation uses **@hono/zod-validator + Zod**: title 1–120 trimmed characters, description up to 4000, boolean `done`, and up to eight nonempty tags of up to 24 characters. Unknown fields and empty patches are rejected. Mutations require JSON; wrong media types return 415. Missing write ETag returns 428; a stale one returns 412, including concurrent updates. JSON requests are limited to 32 KiB, including chunked requests. The collection holds up to 5000 tasks.

For example, using your locally loaded token:

```sh
curl -sS http://127.0.0.1:8787/api/tasks \
  -H "Authorization: Bearer $WORKBENCH_TOKEN" \
  -H 'Content-Type: application/json' \
  --data '{"title":"Test Android browser","tags":["android","hono"]}'
```

Uploads are bounded to **1 MiB** each, 1 MiB + 16 KiB total multipart body, and 100 stored upload records. Only valid UTF-8 text without binary control bytes, parseable JSON, and signature-checked PNG/JPEG are allowed. Image checks are **not** full decoding, malware scanning or a production content-safety guarantee. All files download as attachments with `nosniff`. Safe ASCII display names are at most 120 characters; only computed SHA-256 values are used as blob filenames. Stored bytes are rehashed on download. Upload records are append-only in this sample; reset an offline data directory when finished.

SSE is consumed with authenticated `fetch`, not token-bearing EventSource URLs. It emits `ready`, persisted `task.created/updated/deleted`, `upload.created` and 15-second `heartbeat` events. Abort and reader cancellation remove subscriptions/timers. Each connection has a 64-event queue; a slow consumer is told to reset. IDs are connection-local; **no replay/resume guarantee** is provided. Reconnect and Refresh to resynchronize. Refresh also updates task/status panels; the event log does not silently overwrite an open editor.

## Persistence and operational limits

`state.json` stores validated task/upload metadata. Each mutation is serialized, written to a same-directory temporary file, fsynced, and atomically renamed before responding or publishing an event. Blobs are written before their metadata. A failed metadata write can leave an unreferenced blob; this is not a database with transactional garbage collection. There is no claim of power-loss durability across every filesystem (the parent directory is not fsynced). Clean process restarts are tested.

The `writer.lock` file prevents two sample processes sharing a data directory. SIGINT/SIGTERM closes sockets and releases the lock. Following an ungraceful kill, verify the previous writer is no longer running before removing a stale `writer.lock`. A malformed `state.json` fails startup rather than silently resetting data. Back up the entire data directory while stopped. Keep it on writable **app-private/internal storage**, not a read-only asset directory or an Android document-provider URI; private permissions and atomic same-directory rename are required.

## Tests and evidence

Host execution on Node 24.15.0 completed `npm ci`, typecheck, build, and **33 tests passing, 0 skipped, 0 cancelled**. Tests create isolated temporary data directories and start actual `@hono/node-server` loopback listeners. Coverage includes authentication rejection, CRUD/validation/pagination, ETags/stale concurrent writes, upload bytes/checksums/type/size/path handling, chunked limits, CORS, request IDs, sanitized 404/500 handling, SSE events and both cancellation paths, corrupt storage detection, generated-token permissions, single-writer locking, concurrent persistence and restart durability. A separate spawned compiled CLI process is stopped and restarted to verify persisted HTTP data.

For machine-readable results after a build:

```sh
node --test --test-concurrency=1 --test-reporter=tap test/*.test.mjs
# Or --test-reporter=junit
```

The later physical Pixel run through building **114 / 3.28.4** performed fresh-cache Internet npm installation, typecheck/build and **34 passing tests**, with no failures/skips. The 34th test covers bounded oversized-body rejection; the original 33 assertions remain. Android's slower upload exposed an early-response/keep-alive race, fixed by closing rejected-body connections and a bounded pre-response drain. The regression uses server events rather than desktop timing assumptions. No tests are conditionally skipped for Android. The public fault-test endpoint exists only under an explicit in-process test option, never in normal CLI startup.

The emulator IDE UI also opened this project, installed/built/started it through the PTY, and served the dashboard; browser form-handler interaction created a persistent task and received SSE events. This is not complete mobile-browser visual/accessibility certification.

## Portability and packaging

Runtime dependencies are pure JavaScript: Hono 4.13.13, node-server 2.1.5, zod-validator 0.9.1 and Zod 4.6.7. Development uses TypeScript **5.9.3**, Node typings 24.13.2 and transitive undici-types 7.18.2. There is **no** SQLite, native addon, node-gyp, esbuild, Rollup, shell build helper or dependency install script. `npm run dev` uses Node 24's built-in TypeScript stripping; `build/start` use ordinary emitted ESM JavaScript. Android still requires a working Node/npm runtime, filesystem, loopback networking, and registry access/cache for `npm ci`.

Package source, `test/`, `public/`, package manifests/lock, config and license/notices. Exclude `node_modules/`, `dist/`, `.data/` and `.env`; build on the target. Preserve `public/` beside `src/` and `dist/` because the server resolves assets relative to its module. This sample does not modify monorepo, upstream or Android configuration.

## License

Owned sample code uses the repository's **GPL-3.0-or-later** license; `LICENSE` is a verbatim copy of the repository license. No new linking exception or MIT license for our code is asserted. Third-party packages retain their own licenses; see `THIRD_PARTY_NOTICES.txt`, the exact lockfile, and installed package notices.
