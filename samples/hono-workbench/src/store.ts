// SPDX-License-Identifier: GPL-3.0-or-later
// Repository-owned sample code; Hono itself is an unmodified public dependency.
import { createHash, randomBytes, randomUUID } from 'node:crypto'
import { mkdir, open, readFile, rename, rm } from 'node:fs/promises'
import { join } from 'node:path'
import { z } from 'zod'

const taskFields = {
  title: z.string().trim().min(1).max(120),
  description: z.string().max(4000),
  done: z.boolean(),
  tags: z.array(z.string().trim().min(1).max(24)).max(8),
}
export const taskInput = z.object({
  ...taskFields, description: taskFields.description.default(''),
  done: taskFields.done.default(false), tags: taskFields.tags.default([]),
}).strict()
// Do not derive PATCH from defaulted fields: Zod 4 applies defaults inside partial().
export const taskPatch = z.object(taskFields).strict().partial().refine((value) => Object.keys(value).length > 0, 'Supply at least one field')
const taskSchema = taskInput.extend({
  id: z.string().uuid(), revision: z.number().int().positive(),
  createdAt: z.string().datetime(), updatedAt: z.string().datetime(),
})
const uploadSchema = z.object({
  id: z.string().uuid(), name: z.string().min(1).max(120),
  type: z.enum(['text/plain', 'application/json', 'image/png', 'image/jpeg']),
  size: z.number().int().positive().max(1024 * 1024),
  sha256: z.string().regex(/^[a-f0-9]{64}$/), createdAt: z.string().datetime(),
}).strict()
const stateSchema = z.object({
  version: z.literal(1), tasks: z.array(taskSchema).max(5000), uploads: z.array(uploadSchema).max(100),
}).strict()
export type Task = z.infer<typeof taskSchema>
export type Upload = z.infer<typeof uploadSchema>
type State = z.infer<typeof stateSchema>
export type Change = { kind: string; id: string; at: string }
export class StoreError extends Error {
  readonly status: 404 | 409 | 412 | 428
  constructor(message: string, status: 404 | 409 | 412 | 428) { super(message); this.status = status }
}
export const taskTag = (task: Task) => `"${task.id}:${task.revision}"`
const isMissing = (error: unknown) => (error as NodeJS.ErrnoException).code === 'ENOENT'

// Temp file + fsync + same-directory rename keeps readers from seeing partial JSON.
// Data is single-process/single-writer; a lock prevents two servers sharing a directory.
async function atomicWrite(path: string, content: string | Uint8Array) {
  const temporary = `${path}.${randomUUID()}.tmp`
  try {
    const handle = await open(temporary, 'wx', 0o600)
    try {
      await handle.writeFile(content)
      await handle.sync()
    } finally {
      await handle.close()
    }
    await rename(temporary, path)
  } finally { await rm(temporary, { force: true }) }
}

export async function loadToken(dataDir: string, supplied?: string): Promise<string> {
  await mkdir(dataDir, { recursive: true, mode: 0o700 })
  if (supplied !== undefined) {
    if (!/^[A-Za-z0-9._~-]{32,256}$/.test(supplied)) {
      throw new Error('WORKBENCH_TOKEN must be 32–256 URL-safe characters')
    }
    return supplied
  }
  const path = join(dataDir, 'local-token')
  try {
    const token = (await readFile(path, 'utf8')).trim()
    if (!/^[A-Za-z0-9._~-]{32,256}$/.test(token)) throw new Error('Invalid local-token file')
    return token
  } catch (error) { if (!isMissing(error)) throw error }
  const token = randomBytes(32).toString('base64url')
  const handle = await open(path, 'wx', 0o600)
  try { await handle.writeFile(`${token}\n`); await handle.sync() } finally { await handle.close() }
  return token
}

export class Store {
  private state: State
  private queue: Promise<unknown> = Promise.resolve()
  private listeners = new Set<(change: Change) => void>()
  readonly dir: string
  private constructor(dir: string, state: State) { this.dir = dir; this.state = state }

  static async open(dir: string): Promise<Store> {
    await mkdir(join(dir, 'blobs'), { recursive: true, mode: 0o700 })
    const lockPath = join(dir, 'writer.lock')
    const lock = await open(lockPath, 'wx', 0o600).catch((error: NodeJS.ErrnoException) => {
      if (error.code === 'EEXIST') throw new Error(`Data directory is locked: ${lockPath}. Stop its owner before removing a stale lock.`)
      throw error
    })
    await lock.writeFile(`${process.pid}\n`)
    await lock.close()
    try {
      let state: State
      try { state = stateSchema.parse(JSON.parse(await readFile(join(dir, 'state.json'), 'utf8'))) }
      catch (error) {
        if (!isMissing(error)) throw error // Never silently replace a corrupt database.
        state = { version: 1, tasks: [], uploads: [] }
        await atomicWrite(join(dir, 'state.json'), JSON.stringify(state, null, 2))
      }
      return new Store(dir, state)
    } catch (error) { await rm(lockPath, { force: true }); throw error }
  }

  async close() { await this.queue.catch(() => {}); this.listeners.clear(); await rm(join(this.dir, 'writer.lock'), { force: true }) }
  tasks() { return structuredClone(this.state.tasks) }
  uploads() { return structuredClone(this.state.uploads) }
  task(id: string) { const value = this.state.tasks.find((task) => task.id === id); return value ? structuredClone(value) : undefined }
  upload(id: string) { const value = this.state.uploads.find((file) => file.id === id); return value ? structuredClone(value) : undefined }
  get subscriberCount() { return this.listeners.size }
  subscribe(listener: (change: Change) => void) { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }

  private mutate<T>(kind: string, edit: (draft: State) => Promise<{ value: T; id: string }> | { value: T; id: string }): Promise<T> {
    const next = this.queue.then(async () => {
      const draft = structuredClone(this.state)
      const { value, id } = await edit(draft)
      stateSchema.parse(draft)
      await atomicWrite(join(this.dir, 'state.json'), JSON.stringify(draft, null, 2))
      this.state = draft
      const change = { kind, id, at: new Date().toISOString() }
      for (const listener of this.listeners) listener(change)
      return structuredClone(value)
    })
    this.queue = next.catch(() => {})
    return next
  }

  createTask(input: z.infer<typeof taskInput>) {
    return this.mutate('task.created', (draft) => {
      if (draft.tasks.length >= 5000) throw new StoreError('Task capacity reached (5000)', 409)
      const now = new Date().toISOString()
      const task: Task = { ...input, id: randomUUID(), revision: 1, createdAt: now, updatedAt: now }
      draft.tasks.push(task)
      return { value: task, id: task.id }
    })
  }
  updateTask(id: string, patch: z.infer<typeof taskPatch>, match?: string) {
    return this.mutate('task.updated', (draft) => {
      const task = draft.tasks.find((value) => value.id === id)
      this.checkMatch(task, match)
      Object.assign(task, patch, { revision: task.revision + 1, updatedAt: new Date().toISOString() })
      return { value: task, id }
    })
  }
  deleteTask(id: string, match?: string) {
    return this.mutate('task.deleted', (draft) => {
      this.checkMatch(draft.tasks.find((value) => value.id === id), match)
      draft.tasks = draft.tasks.filter((task) => task.id !== id)
      return { value: null, id }
    })
  }
  private checkMatch(task: Task | undefined, match?: string): asserts task is Task {
    if (!task) throw new StoreError('Task not found', 404)
    if (!match) throw new StoreError('If-Match is required; use the task ETag', 428)
    if (match !== taskTag(task)) throw new StoreError('Task changed; reload before editing', 412)
  }
  saveUpload(name: string, type: Upload['type'], bytes: Uint8Array) {
    return this.mutate('upload.created', async (draft) => {
      if (draft.uploads.length >= 100) throw new StoreError('Upload capacity reached (100)', 409)
      const sha256 = createHash('sha256').update(bytes).digest('hex')
      // User-provided names never participate in filesystem paths.
      await atomicWrite(join(this.dir, 'blobs', sha256), bytes)
      const file: Upload = { id: randomUUID(), name, type, size: bytes.byteLength, sha256, createdAt: new Date().toISOString() }
      draft.uploads.push(file)
      return { value: file, id: file.id }
    })
  }
  async readUpload(file: Upload) {
    const bytes = await readFile(join(this.dir, 'blobs', file.sha256))
    if (bytes.byteLength !== file.size || createHash('sha256').update(bytes).digest('hex') !== file.sha256) {
      throw new Error('Stored upload failed integrity verification')
    }
    return bytes
  }
}
