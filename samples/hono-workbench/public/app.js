// SPDX-License-Identifier: GPL-3.0-or-later
'use strict'
const $ = (id) => document.getElementById(id)
let token = '', controller, editTag, generation = 0
const status = (message) => { $('status').textContent = message }
async function api(path, options = {}) {
  if (!token) throw new Error('Connect with a local bearer token first')
  const response = await fetch(path, { ...options, headers: { Authorization: `Bearer ${token}`, ...options.headers } })
  if (!response.ok) {
    const text = await response.text()
    throw new Error(`HTTP ${response.status}: ${text.slice(0, 500)}`)
  }
  return response
}
const jsonRequest = (method, body, headers = {}) => ({ method, headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body) })
function action(text, callback) { const button = document.createElement('button'); button.type = 'button'; button.className = 'secondary'; button.textContent = text; button.onclick = () => void run(callback); return button }
function textElement(tag, text, className) { const node = document.createElement(tag); node.textContent = text; if (className) node.className = className; return node }
async function run(callback) { try { await callback() } catch (error) { status(error.message) } }
function clearEditor() { $('task-form').reset(); $('task-id').value = ''; editTag = undefined; $('save-task').textContent = 'Create task' }
async function refresh() {
  const [tasks, uploads, metrics] = await Promise.all([
    api(`/api/tasks?limit=100&q=${encodeURIComponent($('search').value)}`).then((r) => r.json()),
    api('/api/uploads').then((r) => r.json()), api('/api/status').then((r) => r.json()),
  ])
  $('metrics').textContent = JSON.stringify(metrics, null, 2)
  $('task-count').textContent = `${tasks.total} matching tasks; showing ${tasks.items.length}`
  $('tasks').replaceChildren()
  for (const task of tasks.items) {
    const li = document.createElement('li'); if (task.done) li.className = 'complete'
    li.append(textElement('h3', task.title), textElement('p', task.description), textElement('p', `${task.tags.join(', ')} · revision ${task.revision}`, 'meta'))
    const buttons = document.createElement('div'); buttons.className = 'actions'
    buttons.append(action('Edit', async () => {
      const response = await api(`/api/tasks/${task.id}`); const { task: current } = await response.json()
      editTag = response.headers.get('etag'); $('task-id').value = current.id; $('title').value = current.title
      $('description').value = current.description; $('done').checked = current.done; $('tags').value = current.tags.join(', ')
      $('save-task').textContent = 'Save changes'; $('title').focus()
    }), action(task.done ? 'Reopen' : 'Complete', async () => {
      await api(`/api/tasks/${task.id}`, jsonRequest('PATCH', { done: !task.done }, { 'If-Match': `"${task.id}:${task.revision}"` })); await refresh()
    }), action('Delete', async () => {
      if (!confirm(`Delete “${task.title}”?`)) return
      await api(`/api/tasks/${task.id}`, { method: 'DELETE', headers: { 'If-Match': `"${task.id}:${task.revision}"` } }); await refresh()
    }))
    li.append(buttons); $('tasks').append(li)
  }
  $('uploads').replaceChildren()
  for (const upload of uploads.items) {
    const li = document.createElement('li')
    li.append(textElement('h3', upload.name), textElement('p', `${upload.type} · ${upload.size} bytes`), textElement('p', `SHA-256 ${upload.sha256}`, 'meta'), action('Download', async () => {
      const response = await api(`/api/uploads/${upload.id}`); const url = URL.createObjectURL(await response.blob())
      const link = document.createElement('a'); link.href = url; link.download = upload.name; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
    }))
    $('uploads').append(li)
  }
}
function stopStream() { generation++; controller?.abort(); controller = undefined; $('stream-status').textContent = 'Disconnected' }
async function startStream() {
  stopStream(); const mine = generation; controller = new AbortController()
  let reader
  try {
    const response = await api('/api/events', { signal: controller.signal })
    reader = response.body.getReader(); const decoder = new TextDecoder(); let buffer = ''
    $('stream-status').textContent = 'Connected — ready, task changes and heartbeats stream below'
    while (mine === generation) {
      const { value, done } = await reader.read(); if (done) break
      buffer += decoder.decode(value, { stream: true }); let boundary
      while ((boundary = buffer.indexOf('\n\n')) !== -1) {
        const frame = buffer.slice(0, boundary); buffer = buffer.slice(boundary + 2)
        $('events').textContent = `${frame}\n\n${$('events').textContent}`.slice(0, 12000)
      }
    }
    if (mine === generation) $('stream-status').textContent = 'Stream ended; Connect again and Refresh to resynchronize (no event replay)'
  } catch (error) { if (mine === generation && error.name !== 'AbortError') $('stream-status').textContent = `Stream failed: ${error.message}` }
  finally { if (reader) { await reader.cancel().catch(() => {}); reader.releaseLock() } }
}
$('connect-form').onsubmit = (event) => { event.preventDefault(); void run(async () => { token = $('token').value.trim(); await refresh(); status('Connected to the local Hono server'); void startStream() }) }
$('disconnect').onclick = () => { stopStream(); token = ''; $('token').value = ''; $('tasks').replaceChildren(); $('uploads').replaceChildren(); $('events').textContent = ''; $('metrics').textContent = 'No status loaded'; clearEditor(); status('Disconnected; token cleared') }
$('refresh').onclick = () => void run(refresh)
$('filter-form').onsubmit = (event) => { event.preventDefault(); void run(refresh) }
$('cancel-edit').onclick = clearEditor
$('task-form').onsubmit = (event) => { event.preventDefault(); void run(async () => {
  const id = $('task-id').value
  const body = { title: $('title').value, description: $('description').value, done: $('done').checked, tags: $('tags').value.split(',').map((tag) => tag.trim()).filter(Boolean) }
  await api(id ? `/api/tasks/${id}` : '/api/tasks', jsonRequest(id ? 'PATCH' : 'POST', body, id ? { 'If-Match': editTag } : {}))
  clearEditor(); await refresh(); status(id ? 'Task updated and persisted' : 'Task created and persisted')
}) }
$('upload-form').onsubmit = (event) => { event.preventDefault(); void run(async () => {
  const file = $('file').files[0]; if (!file || file.size < 1 || file.size > 1048576) throw new Error('Choose a nonempty file no larger than 1 MiB')
  const form = new FormData(); form.set('file', file)
  const { upload } = await (await api('/api/uploads', { method: 'POST', body: form })).json()
  $('upload-form').reset(); await refresh(); status(`Stored ${upload.name}; SHA-256 ${upload.sha256}`)
}) }
window.addEventListener('pagehide', stopStream)
