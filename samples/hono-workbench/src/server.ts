// SPDX-License-Identifier: GPL-3.0-or-later
import { serve } from '@hono/node-server'
import { createServer, type Server } from 'node:http'
import type { AddressInfo } from 'node:net'
import { resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { createApp, type AppOptions } from './app.ts'
import { loadToken, Store } from './store.ts'

export interface StartOptions extends Omit<AppOptions, 'store' | 'token'> {
  dataDir: string
  token?: string
  port?: number
}
export async function startWorkbench(options: StartOptions) {
  const dataDir = resolve(options.dataDir)
  const store = await Store.open(dataDir)
  try {
    const token = await loadToken(dataDir, options.token)
    const port = options.port ?? 8787
    const app = await createApp({ ...options, store, token, origins: options.origins ?? [
      `http://127.0.0.1:${port}`, `http://localhost:${port}`,
    ] })
    const server = serve({ fetch: app.fetch, hostname: '127.0.0.1', port, createServer }) as Server
    await new Promise<void>((resolve, reject) => { server.once('listening', resolve); server.once('error', reject) })
    server.requestTimeout = 30000
    server.headersTimeout = 15000
    const address = server.address() as AddressInfo
    let closing: Promise<void> | undefined
    return {
      url: `http://127.0.0.1:${address.port}`, token, store, server,
      close() {
        return closing ??= (async () => {
          await new Promise<void>((resolve, reject) => {
            server.close((error) => error ? reject(error) : resolve())
            server.closeAllConnections() // Includes open SSE clients; release their subscriptions.
          })
          await store.close()
        })()
      },
    }
  } catch (error) { await store.close(); throw error }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const port = Number(process.env.PORT ?? 8787)
  if (!Number.isInteger(port) || port < 0 || port > 65535) throw new Error('PORT must be an integer from 0 through 65535')
  const dataDir = resolve(process.env.DATA_DIR ?? '.data')
  const instance = await startWorkbench({
    dataDir, port, token: process.env.WORKBENCH_TOKEN,
    origins: process.env.CORS_ORIGINS?.split(',').map((value) => value.trim()).filter(Boolean),
    logError: (error, requestId) => console.error(JSON.stringify({ level: 'error', requestId, message: error.message })),
  })
  console.log(JSON.stringify({ status: 'listening', url: instance.url, dataDir, tokenFile: process.env.WORKBENCH_TOKEN ? null : resolve(dataDir, 'local-token') }))
  for (const signal of ['SIGINT', 'SIGTERM'] as const) process.once(signal, () => {
    void instance.close().then(() => process.exit(0), (error) => { console.error(error); process.exit(1) })
  })
}
