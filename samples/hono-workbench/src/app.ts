// SPDX-License-Identifier: GPL-3.0-or-later
import { createHash, timingSafeEqual } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { Hono } from 'hono'
import { HTTPException } from 'hono/http-exception'
import { bodyLimit } from 'hono/body-limit'
import { cors } from 'hono/cors'
import { etag } from 'hono/etag'
import { requestId } from 'hono/request-id'
import { secureHeaders } from 'hono/secure-headers'
import { streamSSE } from 'hono/streaming'
import { zValidator } from '@hono/zod-validator'
import { z } from 'zod'
import { Store, StoreError, taskInput, taskPatch, taskTag, type Change, type Upload } from './store.ts'

export const MAX_FILE_BYTES = 1024 * 1024
export const MAX_MULTIPART_BYTES = MAX_FILE_BYTES + 16 * 1024
const uuid = z.object({ id: z.string().uuid() })
const listQuery = z.object({
  q: z.string().max(120).optional(), done: z.enum(['true', 'false']).optional(),
  limit: z.coerce.number().int().min(1).max(100).default(50),
  offset: z.coerce.number().int().min(0).max(5000).default(0),
}).strict()

export interface AppOptions {
  store: Store
  token: string
  origins?: string[]
  heartbeatMs?: number
  // Explicit test injection, never configurable from an HTTP request or production env.
  enableTestFault?: boolean
  logError?: (error: Error, requestId: string) => void
}

export async function createApp(options: AppOptions) {
  const { store } = options
  const app = new Hono()
  const tokenHash = createHash('sha256').update(`Bearer ${options.token}`).digest()
  const origins = new Set(options.origins ?? [])
  const assets = new Map(await Promise.all(['index.html', 'app.js', 'style.css'].map(async (name) => [
    name, await readFile(new URL(`../public/${name}`, import.meta.url), 'utf8'),
  ] as const)))

  app.use('*', requestId())
  app.use('*', async (c, next) => {
    await next()
    // ETag may replace the response with a 304; restore correlation/cache headers.
    c.header('X-Request-Id', c.get('requestId'))
    if (c.req.path.startsWith('/api/')) c.header('Cache-Control', 'private, no-cache')
  })
  app.use('*', secureHeaders({ strictTransportSecurity: false, contentSecurityPolicy: {
    defaultSrc: ["'self'"], scriptSrc: ["'self'"], styleSrc: ["'self'"],
    connectSrc: ["'self'"], objectSrc: ["'none'"], frameAncestors: ["'none'"],
    baseUri: ["'none'"], formAction: ["'self'"],
  } }))
  app.use('/api/*', cors({
    origin: (origin) => origins.has(origin) ? origin : null,
    allowMethods: ['GET', 'POST', 'PATCH', 'DELETE', 'OPTIONS'],
    allowHeaders: ['Authorization', 'Content-Type', 'If-Match', 'If-None-Match', 'X-Request-Id'],
    exposeHeaders: ['ETag', 'X-Request-Id', 'Location'], maxAge: 600,
  }))
  app.use('/api/*', async (c, next) => {
    c.header('Cache-Control', 'private, no-cache')
    const supplied = createHash('sha256').update(c.req.header('Authorization') ?? '').digest()
    if (!timingSafeEqual(supplied, tokenHash)) {
      c.header('WWW-Authenticate', 'Bearer realm="local-workbench"')
      return c.json({ error: 'unauthorized', requestId: c.get('requestId') }, 401)
    }
    await next()
  })
  // This middleware runs only after authentication, including for chunked requests.
  app.use('/api/*', async (c, next) => bodyLimit({
    maxSize: c.req.path === '/api/uploads' ? MAX_MULTIPART_BYTES : 32 * 1024,
    onError: async (ctx) => {
      // A rejected request may still be uploading. Never offer this socket for reuse:
      // node-server's post-response cleanup can otherwise race the next keep-alive request.
      ctx.header('Connection', 'close')
      await discardRejectedBody(ctx.req.raw)
      return ctx.json({ error: 'body_too_large', requestId: ctx.get('requestId') }, 413)
    },
  })(c, next))
  app.use('/api/*', async (c, next) => {
    if ((c.req.method === 'POST' || c.req.method === 'PATCH') && c.req.path.startsWith('/api/tasks')) {
      if (!/^application\/json(?:\s*;|$)/i.test(c.req.header('content-type') ?? '')) {
        return c.json({ error: 'application/json required' }, 415)
      }
    }
    await next()
  })

  app.get('/', etag(), (c) => c.html(assets.get('index.html')!))
  app.get('/app.js', etag(), (c) => { c.header('Content-Type', 'text/javascript; charset=utf-8'); return c.body(assets.get('app.js')!) })
  app.get('/style.css', etag(), (c) => { c.header('Content-Type', 'text/css; charset=utf-8'); return c.body(assets.get('style.css')!) })
  app.get('/health', (c) => c.json({ status: 'ok', application: 'repository-owned-hono-workbench', storage: 'atomic-json', node: process.version }))
  app.get('/api/status', (c) => c.json({
    tasks: store.tasks().length, uploads: store.uploads().length,
    subscribers: store.subscriberCount, uptimeSeconds: Math.floor(process.uptime()),
    limits: { fileBytes: MAX_FILE_BYTES, tasks: 5000, uploads: 100 },
  }))

  app.get('/api/tasks', etag(), zValidator('query', listQuery), (c) => {
    const query = c.req.valid('query')
    const matching = store.tasks().filter((task) =>
      (query.done === undefined || task.done === (query.done === 'true')) &&
      (!query.q || `${task.title} ${task.description} ${task.tags.join(' ')}`.toLowerCase().includes(query.q.toLowerCase())),
    ).sort((a, b) => b.createdAt.localeCompare(a.createdAt) || a.id.localeCompare(b.id))
    return c.json({ items: matching.slice(query.offset, query.offset + query.limit), total: matching.length })
  })
  app.post('/api/tasks', zValidator('json', taskInput), async (c) => {
    const task = await store.createTask(c.req.valid('json'))
    c.header('ETag', taskTag(task)); c.header('Location', `/api/tasks/${task.id}`)
    return c.json({ task }, 201)
  })
  app.get('/api/tasks/:id', etag(), zValidator('param', uuid), (c) => {
    const task = store.task(c.req.valid('param').id)
    if (!task) throw new StoreError('Task not found', 404)
    c.header('ETag', taskTag(task))
    return c.json({ task })
  })
  app.patch('/api/tasks/:id', zValidator('param', uuid), zValidator('json', taskPatch), async (c) => {
    const task = await store.updateTask(c.req.valid('param').id, c.req.valid('json'), c.req.header('If-Match'))
    c.header('ETag', taskTag(task))
    return c.json({ task })
  })
  app.delete('/api/tasks/:id', zValidator('param', uuid), async (c) => {
    await store.deleteTask(c.req.valid('param').id, c.req.header('If-Match'))
    return c.body(null, 204)
  })

  app.get('/api/uploads', etag(), (c) => c.json({ items: store.uploads() }))
  app.post('/api/uploads', async (c) => {
    if (!/^multipart\/form-data\s*;/i.test(c.req.header('Content-Type') ?? '')) {
      return c.json({ error: 'multipart/form-data required' }, 415)
    }
    let form: FormData
    try { form = await c.req.formData() } catch { return c.json({ error: 'Malformed multipart body' }, 400) }
    const values = [...form.entries()]
    const file = form.get('file')
    if (values.length !== 1 || values[0]?.[0] !== 'file' || !(file instanceof File)) {
      return c.json({ error: 'Exactly one file field is required' }, 400)
    }
    if (file.size === 0 || file.size > MAX_FILE_BYTES) return c.json({ error: 'File must contain 1–1048576 bytes' }, 413)
    const bytes = new Uint8Array(await file.arrayBuffer())
    let type: Upload['type']
    try { type = validateContent(file.type, bytes) }
    catch (error) { return c.json({ error: (error as Error).message }, 415) }
    const upload = await store.saveUpload(safeName(file.name), type, bytes)
    c.header('Location', `/api/uploads/${upload.id}`)
    return c.json({ upload }, 201)
  })
  app.get('/api/uploads/:id', etag(), zValidator('param', uuid), async (c) => {
    const file = store.upload(c.req.valid('param').id)
    if (!file) throw new StoreError('Upload not found', 404)
    const bytes = await store.readUpload(file)
    c.header('Content-Type', file.type)
    c.header('Content-Disposition', `attachment; filename="${file.name}"`)
    c.header('X-Content-SHA256', file.sha256)
    c.header('ETag', `"${file.sha256}"`)
    return c.body(bytes)
  })

  app.get('/api/events', (c) => {
    c.header('X-Accel-Buffering', 'no')
    // Deliberately no ETag middleware: it would buffer an infinite stream.
    return streamSSE(c, async (stream) => {
      const queue: Change[] = []
      let wake: (() => void) | undefined
      let stopped = false
      let overflow = false
      let sequence = 0
      const stop = () => { stopped = true; wake?.() }
      const unsubscribe = store.subscribe((change) => {
        if (queue.length >= 64) { overflow = true; stop(); return }
        queue.push(change); wake?.()
      })
      stream.onAbort(stop)
      c.req.raw.signal.addEventListener('abort', stop, { once: true })
      try {
        if (c.req.raw.signal.aborted || stream.aborted) return
        await stream.writeSSE({ event: 'ready', id: String(++sequence), data: JSON.stringify({ message: 'Connected', replay: false }) })
        while (!stopped && !stream.aborted) {
          if (!queue.length) {
            await new Promise<void>((resolve) => {
              const timer = setTimeout(done, options.heartbeatMs ?? 15000)
              function done() { clearTimeout(timer); wake = undefined; resolve() }
              wake = done
              if (stopped || queue.length) done()
            })
          }
          if (stopped || stream.aborted) break
          const change = queue.shift()
          await stream.writeSSE({
            event: change?.kind ?? 'heartbeat', id: String(++sequence),
            data: JSON.stringify(change ?? { at: new Date().toISOString() }),
          })
        }
        if (overflow && !stream.aborted) await stream.writeSSE({ event: 'reset', data: JSON.stringify({ reason: 'slow_consumer', reload: true }) })
      } finally {
        unsubscribe(); stop()
        c.req.raw.signal.removeEventListener('abort', stop)
      }
    })
  })
  if (options.enableTestFault) app.get('/api/_test/error', () => { throw new Error('injected secret detail') })
  app.notFound((c) => c.json({ error: 'not_found', requestId: c.get('requestId') }, 404))
  app.onError((error, c) => {
    if (error instanceof StoreError) return c.json({ error: error.message, requestId: c.get('requestId') }, error.status)
    if (error instanceof HTTPException && error.status < 500) {
      return c.json({ error: error.message, requestId: c.get('requestId') }, error.status)
    }
    options.logError?.(error, c.get('requestId'))
    return c.json({ error: 'internal_error', requestId: c.get('requestId') }, 500)
  })
  return app
}

// Drain only modest, declared bodies before responding, without buffering them. This
// avoids an early response aborting a nearly complete mobile upload. Oversize/unknown
// bodies and stalled senders still receive a bounded early 413 on a closing connection.
// Keep node-server's default cleanup enabled as the final fallback; never cancel the
// request stream here, since cancelling a Node-backed stream can reset the TCP socket.
const MAX_REJECT_DRAIN_BYTES = 2 * MAX_MULTIPART_BYTES
const REJECT_DRAIN_TIMEOUT_MS = 2000
async function discardRejectedBody(request: Request): Promise<void> {
  const length = Number(request.headers.get('content-length'))
  if (!Number.isSafeInteger(length) || length <= 0 || length > MAX_REJECT_DRAIN_BYTES) return
  const body = request.body
  if (!body || body.locked) return // Hono already holds the reader for chunked limits.
  const reader = body.getReader()
  let timer: ReturnType<typeof setTimeout> | undefined
  let pending: Promise<ReadableStreamReadResult<Uint8Array>> | undefined
  const deadline = new Promise<null>((resolve) => { timer = setTimeout(() => resolve(null), REJECT_DRAIN_TIMEOUT_MS) })
  try {
    let discarded = 0
    while (discarded <= MAX_REJECT_DRAIN_BYTES) {
      pending = reader.read()
      const result = await Promise.race([pending, deadline])
      if (result === null) return
      pending = undefined
      if (result.done) return
      discarded += result.value.byteLength
    }
  } catch {
    // Peer disconnected: still let the adapter send/finish the rejection if possible.
  } finally {
    clearTimeout(timer)
    // releaseLock rejects an outstanding read on timeout; observe that promise so a
    // slow client cannot cause an unhandled rejection or a reader retained forever.
    void pending?.catch(() => {})
    reader.releaseLock()
  }
}

function safeName(raw: string) {
  // Includes Windows separators on Unix; also strips controls, quotes, dot-prefixes.
  return (raw.split(/[\\/]/).pop() ?? '').replace(/[^A-Za-z0-9._-]/g, '_').replace(/^\.+/, '').slice(0, 120) || 'upload'
}
function validateContent(declared: string, bytes: Uint8Array): Upload['type'] {
  if (declared === 'text/plain' || declared === 'application/json') {
    let text: string
    try { text = new TextDecoder('utf-8', { fatal: true }).decode(bytes) }
    catch { throw new Error('Text must be valid UTF-8') }
    if (/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/.test(text)) throw new Error('Binary control bytes are not allowed in text')
    if (declared === 'application/json') { try { JSON.parse(text) } catch { throw new Error('Invalid JSON file') } }
    return declared
  }
  if (declared === 'image/png' && bytes.length >= 24 && Buffer.from(bytes.subarray(0, 8)).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])) && Buffer.from(bytes.subarray(12, 16)).toString('ascii') === 'IHDR') return declared
  if (declared === 'image/jpeg' && bytes.length >= 4 && bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255 && bytes.at(-2) === 255 && bytes.at(-1) === 217) return declared
  throw new Error('Only UTF-8 text, valid JSON, PNG or JPEG signature-checked files are accepted')
}
