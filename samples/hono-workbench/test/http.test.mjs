// SPDX-License-Identifier: GPL-3.0-or-later
// Every API assertion traverses a real @hono/node-server TCP listener on loopback.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtemp, readFile, readdir, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { once } from 'node:events'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { setTimeout as delay, setImmediate as nextTurn } from 'node:timers/promises'
import { startWorkbench } from '../dist/server.js'

const TOKEN = 'local-test-token-0123456789abcdefghijklmnopqrstuvwxyz'
const ORIGIN = 'http://localhost:8787'
async function fixture(t, extra = {}) {
  const dataDir = await mkdtemp(join(tmpdir(), 'hono-workbench-'))
  let instance
  t.after(async () => { await instance?.close(); await rm(dataDir, { recursive: true, force: true }) })
  const start = async () => { instance = await startWorkbench({ dataDir, port: 0, token: TOKEN, origins: [ORIGIN], ...extra }); return instance }
  await start()
  return {
    dataDir, get instance() { return instance },
    async restart() { await instance.close(); return start() },
    request(path, options = {}) {
      return fetch(`${instance.url}${path}`, { ...options, signal: options.signal ?? AbortSignal.timeout(5000), headers: { Authorization: `Bearer ${TOKEN}`, ...options.headers } })
    },
  }
}
const json = (method, value, headers = {}) => ({ method, headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(value) })
async function create(f, value = { title: 'Acceptance task' }) {
  const response = await f.request('/api/tasks', json('POST', value))
  assert.equal(response.status, 201)
  return { ...(await response.json()), tag: response.headers.get('etag'), location: response.headers.get('location') }
}
function multipart(bytes, name = 'notes.txt', type = 'text/plain') {
  const body = new FormData(); body.set('file', new Blob([bytes], { type }), name); return { method: 'POST', body }
}
async function eventually(predicate, message) {
  const deadline = Date.now() + 3000
  while (Date.now() < deadline) { if (await predicate()) return; await delay(20) }
  assert.fail(message)
}
function frames(reader) {
  const decoder = new TextDecoder(); let buffer = ''
  return async () => {
    for (;;) {
      const boundary = buffer.indexOf('\n\n')
      if (boundary !== -1) {
        const frame = buffer.slice(0, boundary); buffer = buffer.slice(boundary + 2)
        return Object.fromEntries(frame.split('\n').map((line) => { const index = line.indexOf(':'); return [line.slice(0, index), line.slice(index + 1).trimStart()] }))
      }
      const { done, value } = await reader.read(); assert.equal(done, false, 'SSE ended before expected event')
      buffer += decoder.decode(value, { stream: true })
    }
  }
}

test('actual loopback server serves health, dashboard assets and security headers', async (t) => {
  const f = await fixture(t)
  assert.equal(f.instance.server.address().address, '127.0.0.1')
  const health = await f.request('/health', { headers: { Authorization: '' } })
  assert.equal(health.status, 200); assert.equal((await health.json()).storage, 'atomic-json')
  const page = await f.request('/')
  assert.equal(page.status, 200); assert.match(page.headers.get('content-security-policy'), /script-src 'self'/)
  assert.equal(page.headers.get('x-content-type-options'), 'nosniff')
  const html = await page.text(); assert.match(html, /name="viewport"/); assert.match(html, /Hono Local Workbench/); assert.ok(!html.includes(TOKEN))
  for (const [path, type] of [['/app.js', 'text/javascript'], ['/style.css', 'text/css']]) {
    const response = await f.request(path); assert.equal(response.status, 200); assert.match(response.headers.get('content-type'), new RegExp(type)); assert.ok((await response.text()).length > 500)
  }
})

test('rejects missing and incorrect bearer auth on private routes', async (t) => {
  const f = await fixture(t)
  for (const path of ['/api/tasks', '/api/uploads', '/api/events', '/api/status']) {
    for (const Authorization of ['', 'Bearer wrong', 'Basic dGVzdA==']) {
      const response = await f.request(path, { headers: { Authorization } })
      assert.equal(response.status, 401); assert.match(response.headers.get('www-authenticate'), /^Bearer /)
      assert.equal((await response.json()).error, 'unauthorized')
    }
  }
})

test('persists full authenticated create/read/update/delete with optimistic ETags', async (t) => {
  const f = await fixture(t)
  const { task, tag, location } = await create(f, { title: '  Android acceptance  ', description: 'Keep this description', tags: ['hono', 'android'] })
  assert.equal(task.title, 'Android acceptance'); assert.equal(task.done, false); assert.equal(task.revision, 1)
  assert.equal(location, `/api/tasks/${task.id}`); assert.equal(tag, `"${task.id}:1"`)
  const get = await f.request(location); assert.deepEqual((await get.json()).task, task); assert.equal(get.headers.get('etag'), tag)
  const update = await f.request(location, json('PATCH', { done: true }, { 'If-Match': tag }))
  assert.equal(update.status, 200)
  const updated = (await update.json()).task
  assert.equal(updated.done, true); assert.equal(updated.revision, 2)
  assert.equal(updated.title, task.title); assert.equal(updated.description, task.description); assert.deepEqual(updated.tags, task.tags)
  assert.notEqual(update.headers.get('etag'), tag)
  const disk = JSON.parse(await readFile(join(f.dataDir, 'state.json'), 'utf8')); assert.deepEqual(disk.tasks[0], updated)
  const removed = await f.request(location, { method: 'DELETE', headers: { 'If-Match': update.headers.get('etag') } })
  assert.equal(removed.status, 204); assert.equal(await removed.text(), '')
  assert.equal((await f.request(location)).status, 404)
  assert.deepEqual((await (await f.request('/api/tasks')).json()).items, [])
})

for (const [label, value] of [
  ['blank title', { title: '   ' }], ['long title', { title: 'x'.repeat(121) }],
  ['unknown field', { title: 'Valid', admin: true }], ['wrong boolean', { title: 'Valid', done: 'yes' }],
  ['too many tags', { title: 'Valid', tags: Array(9).fill('tag') }], ['long description', { title: 'Valid', description: 'x'.repeat(4001) }],
]) {
  test(`Zod rejects ${label} without changing persisted data`, async (t) => {
    const f = await fixture(t)
    const response = await f.request('/api/tasks', json('POST', value))
    assert.equal(response.status, 400); assert.equal((await response.json()).success, false)
    assert.equal((await (await f.request('/api/tasks')).json()).total, 0)
    assert.deepEqual(JSON.parse(await readFile(join(f.dataDir, 'state.json'), 'utf8')).tasks, [])
  })
}

test('rejects malformed JSON, media types, empty patch, invalid UUID and query', async (t) => {
  const f = await fixture(t)
  assert.equal((await f.request('/api/tasks', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{broken' })).status, 400)
  assert.equal((await f.request('/api/tasks', { method: 'POST', body: 'hello' })).status, 415)
  const { location, tag } = await create(f)
  assert.equal((await f.request(location, json('PATCH', {}, { 'If-Match': tag }))).status, 400)
  assert.equal((await f.request('/api/tasks/not-a-uuid')).status, 400)
  for (const query of ['limit=0', 'limit=101', 'done=maybe', 'offset=-1', 'unknown=x']) assert.equal((await f.request(`/api/tasks?${query}`)).status, 400)
})

test('requires If-Match and rejects stale writers including concurrent updates', async (t) => {
  const f = await fixture(t); const { location, tag } = await create(f)
  assert.equal((await f.request(location, json('PATCH', { title: 'Missing match' }))).status, 428)
  assert.equal((await f.request(location, { method: 'DELETE' })).status, 428)
  const responses = await Promise.all(['First', 'Second'].map((title) => f.request(location, json('PATCH', { title }, { 'If-Match': tag }))))
  assert.deepEqual(responses.map((r) => r.status).sort(), [200, 412])
  assert.equal((await f.request(location, { method: 'DELETE', headers: { 'If-Match': tag } })).status, 412)
  assert.equal((await (await f.request(location)).json()).task.revision, 2)
})

test('filters and paginates persisted tasks', async (t) => {
  const f = await fixture(t)
  await create(f, { title: 'Alpha', tags: ['mobile'], done: true })
  await create(f, { title: 'Beta', description: 'MOBILE browser' })
  await create(f, { title: 'Gamma' })
  const result = await (await f.request('/api/tasks?q=mobile&limit=1')).json()
  assert.equal(result.total, 2); assert.equal(result.items.length, 1)
  const next = await (await f.request('/api/tasks?q=mobile&limit=1&offset=1')).json()
  assert.equal(next.total, 2); assert.notEqual(next.items[0].id, result.items[0].id)
  const complete = await (await f.request('/api/tasks?done=true')).json()
  assert.equal(complete.total, 1); assert.equal(complete.items[0].title, 'Alpha')
})

test('honors conditional GET and changes collection ETag after writes', async (t) => {
  const f = await fixture(t); const { location, tag } = await create(f)
  const response = await f.request(location, { headers: { 'If-None-Match': tag } })
  assert.equal(response.status, 304); assert.equal(await response.text(), '')
  assert.ok(response.headers.get('x-request-id')); assert.equal(response.headers.get('cache-control'), 'private, no-cache')
  const before = await f.request('/api/tasks'); await before.arrayBuffer()
  const unchanged = await f.request('/api/tasks', { headers: { 'If-None-Match': before.headers.get('etag') } }); assert.equal(unchanged.status, 304)
  await create(f, { title: 'New entry' })
  const changed = await f.request('/api/tasks', { headers: { 'If-None-Match': before.headers.get('etag') } })
  assert.equal(changed.status, 200); assert.notEqual(changed.headers.get('etag'), before.headers.get('etag'))
})

test('CORS permits exact local origins and bearer preflight but not unlisted origins', async (t) => {
  const f = await fixture(t)
  const preflight = await f.request('/api/tasks', { method: 'OPTIONS', headers: { Authorization: '', Origin: ORIGIN, 'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'authorization,content-type,if-match' } })
  assert.equal(preflight.status, 204); assert.equal(preflight.headers.get('access-control-allow-origin'), ORIGIN)
  assert.match(preflight.headers.get('access-control-allow-headers'), /Authorization/i)
  const get = await f.request('/api/tasks', { headers: { Origin: ORIGIN } })
  assert.equal(get.headers.get('access-control-allow-origin'), ORIGIN)
  assert.match(get.headers.get('access-control-expose-headers'), /ETag/i)
  const denied = await f.request('/api/tasks', { headers: { Origin: 'https://unlisted.invalid' } })
  assert.equal(denied.headers.get('access-control-allow-origin'), null)
  const auth = await f.request('/api/tasks', { headers: { Authorization: '', Origin: ORIGIN } })
  assert.equal(auth.status, 401); assert.equal(auth.headers.get('access-control-allow-origin'), ORIGIN)
})

test('request IDs correlate JSON 404 and sanitized error responses', async (t) => {
  const errors = []; const f = await fixture(t, { enableTestFault: true, logError: (...args) => errors.push(args) })
  const missing = await f.request('/not-a-route', { headers: { 'X-Request-Id': 'acceptance-404' } })
  assert.equal(missing.status, 404); assert.equal(missing.headers.get('x-request-id'), 'acceptance-404')
  assert.deepEqual(await missing.json(), { error: 'not_found', requestId: 'acceptance-404' })
  const failure = await f.request('/api/_test/error')
  assert.equal(failure.status, 500)
  const body = await failure.json(); assert.equal(body.error, 'internal_error'); assert.equal(body.requestId, failure.headers.get('x-request-id'))
  assert.ok(!JSON.stringify(body).includes('secret')); assert.equal(errors.length, 1); assert.match(errors[0][0].message, /secret/)
})

test('test fault endpoint is absent by default', async (t) => {
  const f = await fixture(t); assert.equal((await f.request('/api/_test/error')).status, 404)
})

test('multipart upload stores SHA-256, safe filename and exact downloadable bytes', async (t) => {
  const f = await fixture(t); const bytes = Buffer.from('Hello Android\n你好 Hono\n')
  const response = await f.request('/api/uploads', multipart(bytes, '../../unsafe name.txt'))
  assert.equal(response.status, 201)
  const { upload } = await response.json()
  assert.equal(upload.name, 'unsafe_name.txt'); assert.equal(upload.size, bytes.length)
  assert.equal(upload.sha256, createHash('sha256').update(bytes).digest('hex'))
  assert.equal(response.headers.get('location'), `/api/uploads/${upload.id}`)
  const download = await f.request(response.headers.get('location'))
  assert.equal(download.status, 200); assert.match(download.headers.get('content-disposition'), /^attachment; filename="unsafe_name.txt"$/)
  assert.equal(download.headers.get('x-content-type-options'), 'nosniff')
  assert.equal(download.headers.get('x-content-sha256'), upload.sha256)
  assert.deepEqual(Buffer.from(await download.arrayBuffer()), bytes)
  assert.deepEqual(await readdir(join(f.dataDir, 'blobs')), [upload.sha256])
  assert.deepEqual(await readFile(join(f.dataDir, 'blobs', upload.sha256)), bytes)
  assert.equal((await (await f.request('/api/uploads')).json()).items.length, 1)
})

test('upload accepts valid JSON and signature-checked PNG/JPEG', async (t) => {
  const f = await fixture(t)
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a0i8AAAAASUVORK5CYII=', 'base64')
  // JPEG framing, not full image decoding: documented signature validation contract.
  for (const [bytes, name, type] of [[Buffer.from('{"works":true}'), 'data.json', 'application/json'], [png, 'one.png', 'image/png'], [Buffer.from([255, 216, 255, 224, 255, 217]), 'frame.jpg', 'image/jpeg']]) {
    const response = await f.request('/api/uploads', multipart(bytes, name, type)); assert.equal(response.status, 201); assert.equal((await response.json()).upload.type, type)
  }
})

for (const [label, bytes, type] of [
  ['HTML MIME', '<script>alert(1)</script>', 'text/html'],
  ['forged PNG MIME', 'not an image', 'image/png'],
  ['invalid JSON', '{invalid}', 'application/json'],
  ['binary text', Buffer.from([1, 0, 255]), 'text/plain'],
]) {
  test(`upload rejects ${label} without creating files`, async (t) => {
    const f = await fixture(t); const response = await f.request('/api/uploads', multipart(bytes, 'file.bin', type))
    assert.equal(response.status, 415); assert.deepEqual(await readdir(join(f.dataDir, 'blobs')), [])
  })
}

test('upload rejects extra fields, wrong content type and malformed multipart', async (t) => {
  const f = await fixture(t); const body = new FormData(); body.set('file', new Blob(['hello'], { type: 'text/plain' }), 'test.txt'); body.set('extra', 'not accepted')
  assert.equal((await f.request('/api/uploads', { method: 'POST', body })).status, 400)
  assert.equal((await f.request('/api/uploads', json('POST', { file: 'hello' }))).status, 415)
  assert.equal((await f.request('/api/uploads', { method: 'POST', body: 'broken', headers: { 'Content-Type': 'multipart/form-data; boundary=none' } })).status, 400)
  assert.deepEqual(await readdir(join(f.dataDir, 'blobs')), [])
})

test('bounds empty, oversized, exact-boundary uploads and JSON request bodies', async (t) => {
  const f = await fixture(t)
  // Keep the original five requests, ordering, byte counts and expected statuses.
  // Emit stage/headers only if a request/assertion fails; never retry a broken socket.
  const cases = [
    ['empty file', '/api/uploads', () => multipart(''), 413],
    ['file one byte over 1 MiB', '/api/uploads', () => multipart('x'.repeat(1048577)), 413],
    ['multipart over body limit (1100000 file bytes)', '/api/uploads', () => multipart('x'.repeat(1100000)), 413],
    ['exact 1 MiB file after oversized rejection', '/api/uploads', () => multipart('x'.repeat(1048576)), 201],
    ['JSON over 32 KiB body limit', '/api/tasks', () => json('POST', { title: 'Large', description: 'x'.repeat(40000) }), 413],
  ]
  const completed = []
  for (const [stage, path, request, expected] of cases) {
    let response
    try {
      response = await f.request(path, request())
      assert.equal(response.status, expected, stage)
      completed.push({ stage, status: response.status, connection: response.headers.get('connection'), requestId: response.headers.get('x-request-id') })
    } catch (error) {
      t.diagnostic(JSON.stringify({ stage, path, expected, status: response?.status, connection: response?.headers.get('connection'), completed, error: error.message, cause: error.cause?.code, socket: error.cause?.socket }))
      throw error
    }
  }
})

test('gated oversized Content-Length body drains before rejection and the next request succeeds', async (t) => {
  const f = await fixture(t)
  // Above the JSON limit but far below the bounded discard cap. The original test
  // already covers every 1 MiB multipart boundary. Do not require an artificially
  // paced 1.1 MiB mobile upload to beat the production two-second drain deadline.
  const total = 70000; let sent = 0, ended = false, cancelled = false, requestComplete = false
  let completeAtResponse = false
  const ordering = []
  let admitRemainder
  const accepted = new Promise((resolve) => { admitRemainder = resolve })
  f.instance.server.once('request', (incoming, outgoing) => {
    incoming.once('end', () => { requestComplete = incoming.complete; ordering.push('request-end') })
    outgoing.once('finish', () => { completeAtResponse = incoming.complete; ordering.push('response-finish') })
    // The first byte reaches the real HTTP listener before the rest is offered.
    // One event-loop turn lets a broken early-response handler finish; no elapsed
    // upload-speed assumption or retry is used to decide the assertion.
    void nextTurn().then(admitRemainder)
  })
  const body = new ReadableStream({
    async pull(controller) {
      if (sent !== 0) await accepted
      if (cancelled) return
      const count = sent === 0 ? 1 : total - sent
      sent += count; controller.enqueue(new Uint8Array(count))
      if (sent === total) { ended = true; controller.close() }
    },
    cancel() { cancelled = true; admitRemainder() },
  })
  let stage = 'gated oversized JSON rejection', response
  try {
    response = await f.request('/api/tasks', { method: 'POST', body, duplex: 'half', headers: { 'Content-Type': 'application/json', 'Content-Length': String(total) } })
    assert.equal(response.status, 413)
    assert.equal(response.headers.get('connection'), 'close')
    assert.equal((await response.json()).error, 'body_too_large')
    assert.equal(sent, total, 'small rejected body must complete before replying')
    assert.equal(ended, true, 'gated sender must reach EOF')
    assert.equal(requestComplete, true, 'real server must consume the complete rejected body')
    assert.equal(completeAtResponse, true, 'request must be complete when response finishes')
    assert.deepEqual(ordering, ['request-end', 'response-finish'])
    assert.equal((await (await f.request('/api/tasks')).json()).total, 0)
    stage = 'exact-boundary upload after gated rejection'
    response = await f.request('/api/uploads', multipart('x'.repeat(1048576)))
    assert.equal(response.status, 201)
    const { upload } = await response.json()
    assert.equal(upload.size, 1048576)
    assert.equal((await (await f.request('/api/uploads')).json()).items.length, 1)
  } catch (error) {
    t.diagnostic(JSON.stringify({ stage, sent, total, ended, cancelled, requestComplete, completeAtResponse, ordering, status: response?.status, connection: response?.headers.get('connection'), error: error.message, cause: error.cause?.code, socket: error.cause?.socket }))
    throw error
  } finally { admitRemainder() }
})

test('bounds chunked requests without trusting Content-Length', async (t) => {
  const f = await fixture(t)
  const body = new ReadableStream({ start(controller) { controller.enqueue(new TextEncoder().encode('x'.repeat(40000))); controller.close() } })
  const response = await f.request('/api/tasks', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body, duplex: 'half' })
  assert.equal(response.status, 413); assert.equal((await response.json()).error, 'body_too_large')
})

test('SSE streams ready, persisted task events and heartbeat; abort releases subscription', { timeout: 10000 }, async (t) => {
  const f = await fixture(t, { heartbeatMs: 30 })
  const abort = new AbortController(); t.after(() => abort.abort())
  const response = await f.request('/api/events', { signal: abort.signal })
  assert.equal(response.status, 200); assert.match(response.headers.get('content-type'), /text\/event-stream/)
  assert.equal(response.headers.get('etag'), null)
  const reader = response.body.getReader(); const next = frames(reader)
  assert.equal((await next()).event, 'ready')
  const { task } = await create(f)
  let event
  do { event = await next() } while (event.event === 'heartbeat')
  assert.equal(event.event, 'task.created'); assert.equal(JSON.parse(event.data).id, task.id)
  assert.equal((await next()).event, 'heartbeat')
  assert.equal((await (await f.request('/api/status')).json()).subscribers, 1)
  abort.abort(); await reader.cancel().catch(() => {})
  await eventually(async () => (await (await f.request('/api/status')).json()).subscribers === 0, 'SSE subscription must be removed after abort')
})

test('SSE reader cancellation also releases subscription', async (t) => {
  const f = await fixture(t, { heartbeatMs: 30 }); const response = await f.request('/api/events')
  const reader = response.body.getReader(); assert.equal((await frames(reader)()).event, 'ready'); await reader.cancel()
  await eventually(async () => (await (await f.request('/api/status')).json()).subscribers === 0, 'Reader cancellation must release SSE listener')
})

test('tasks and uploaded bytes survive close and restart using the same data directory', async (t) => {
  const f = await fixture(t); const { task, location, tag } = await create(f, { title: 'Durable', description: 'Survives restart', tags: ['persisted'] })
  const { upload } = await (await f.request('/api/uploads', multipart('durable bytes'))).json()
  await f.restart()
  const response = await f.request(location); assert.equal(response.status, 200); assert.equal(response.headers.get('etag'), tag); assert.deepEqual((await response.json()).task, task)
  assert.equal(await (await f.request(`/api/uploads/${upload.id}`)).text(), 'durable bytes')
  assert.equal((await (await f.request('/api/uploads')).json()).items[0].sha256, upload.sha256)
  await f.request(location, { method: 'DELETE', headers: { 'If-Match': tag } }); await f.restart()
  assert.equal((await f.request(location)).status, 404)
})

test('serializes concurrent creates without losing persisted records', async (t) => {
  const f = await fixture(t)
  await Promise.all(Array.from({ length: 20 }, (_, index) => create(f, { title: `Task ${index}` })))
  assert.equal((await (await f.request('/api/tasks')).json()).total, 20)
  await f.restart(); assert.equal((await (await f.request('/api/tasks')).json()).total, 20)
})

test('generates private reusable local token and rejects a second writer', async (t) => {
  const f = await fixture(t, { token: undefined })
  const token = (await readFile(join(f.dataDir, 'local-token'), 'utf8')).trim()
  assert.match(token, /^[A-Za-z0-9_-]{43}$/); assert.equal((await stat(join(f.dataDir, 'local-token'))).mode & 0o777, 0o600)
  assert.equal((await f.request('/api/status', { headers: { Authorization: `Bearer ${token}` } })).status, 200)
  await assert.rejects(startWorkbench({ dataDir: f.dataDir, port: 0, token }), /locked/)
  await f.restart(); assert.equal(f.instance.token, token)
  assert.equal((await f.request('/api/status', { headers: { Authorization: `Bearer ${token}` } })).status, 200)
})

test('fails closed on corrupt persistent state rather than silently resetting data', async (t) => {
  const f = await fixture(t); await create(f); await f.instance.close()
  await writeFile(join(f.dataDir, 'state.json'), '{corrupt')
  await assert.rejects(startWorkbench({ dataDir: f.dataDir, token: TOKEN, port: 0 }))
  assert.equal(await readFile(join(f.dataDir, 'state.json'), 'utf8'), '{corrupt')
})

test('detects stored blob corruption and returns sanitized server error', async (t) => {
  const f = await fixture(t)
  const { upload } = await (await f.request('/api/uploads', multipart('safe text'))).json()
  await writeFile(join(f.dataDir, 'blobs', upload.sha256), 'tampered')
  const response = await f.request(`/api/uploads/${upload.id}`)
  assert.equal(response.status, 500); assert.equal((await response.json()).error, 'internal_error')
})

test('compiled CLI process starts, serves real HTTP, shuts down and restarts durably', { timeout: 15000 }, async (t) => {
  const dataDir = await mkdtemp(join(tmpdir(), 'hono-workbench-cli-')); const children = []
  t.after(async () => { for (const child of children) if (child.exitCode === null && child.signalCode === null) { const exited = once(child, 'exit'); child.kill('SIGTERM'); await exited } await rm(dataDir, { recursive: true, force: true }) })
  async function boot() {
    const child = spawn(process.execPath, [fileURLToPath(new URL('../dist/server.js', import.meta.url))], { env: { ...process.env, PORT: '0', DATA_DIR: dataDir, WORKBENCH_TOKEN: TOKEN }, stdio: ['ignore', 'pipe', 'pipe'] })
    children.push(child); let output = '', errors = ''; child.stderr.on('data', (data) => { errors += data })
    const url = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`CLI startup timed out: ${errors}`)), 5000)
      child.once('error', (error) => { clearTimeout(timer); reject(error) })
      child.once('exit', (code) => { clearTimeout(timer); reject(new Error(`CLI exited ${code}: ${errors}`)) })
      child.stdout.on('data', (data) => {
        output += data
        if (output.includes('\n')) { clearTimeout(timer); try { resolve(JSON.parse(output.split('\n')[0]).url) } catch (error) { reject(error) } }
      })
    })
    return { url, async stop() { const exited = once(child, 'exit'); child.kill('SIGTERM'); const [code, signal] = await exited; assert.equal(code, 0); assert.equal(signal, null) } }
  }
  const first = await boot()
  const created = await fetch(`${first.url}/api/tasks`, { ...json('POST', { title: 'Process durable' }), headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${TOKEN}` } })
  assert.equal(created.status, 201); const { task } = await created.json(); await first.stop()
  const second = await boot(); const response = await fetch(`${second.url}/api/tasks/${task.id}`, { headers: { Authorization: `Bearer ${TOKEN}` } })
  assert.equal(response.status, 200); assert.equal((await response.json()).task.title, 'Process durable'); await second.stop()
})
