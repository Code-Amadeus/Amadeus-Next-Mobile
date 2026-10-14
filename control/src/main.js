import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { BACKGROUND_CONTEXT } from '@earendil-works/chord/context'
import { createModels } from '@earendil-works/pi-ai/models'
import { createRegistry, defineDoc, Harness } from '@earendil-works/pi-durable'
import { openNodeSqliteStorage } from '@earendil-works/pi-durable/storage/sqlite/node'
import { encodeFrame, FrameDecoder, ProtocolError } from './frame.js'

export const PROTOCOL = 1

// stdout 只允许写帧；任何库往 stdout 打日志都会破坏协议，统一改道到 stderr
for (const level of ['log', 'info', 'debug']) console[level] = (...args) => console.error(...args)

const context = BACKGROUND_CONTEXT
const dataDir = process.env.AMADEUS_DATA || path.resolve('data')

const Boot = defineDoc({
  kind: 'amadeus.boot',
  version: 1,
  scope: 'conversation',
  history: 'latest',
  fork: 'initial',
  initial: () => ({ count: 0 }),
})

function send(head, body) {
  process.stdout.write(encodeFrame(head, body))
}

async function openHarness() {
  mkdirSync(dataDir, { recursive: true })
  const storage = await openNodeSqliteStorage(path.join(dataDir, 'sessions.sqlite'))
  const harness = await Harness.open(storage, { models: createModels(), registry: createRegistry() }, context)
  // 阶段 1 尚未接入模型，占位选择只为让根会话可创建；不会发起任何模型请求
  const root = await harness.root(context, { agent: { model: { provider: 'unconfigured', modelId: 'none' } } })
  return { harness, root }
}

async function main() {
  const { harness, root } = await openHarness()
  await root.commit(async (tx) => { (await tx.doc(Boot, root.id)).count++ }, context)
  const boot = await harness.snapshot(Boot, root.id, context)

  const commands = {
    ping: async () => ({ pong: true }),
    'smoke.status': async () => ({
      node: process.version,
      sqlite: (await import('node:sqlite')).DatabaseSync.name,
      boots: (await harness.snapshot(Boot, root.id, context)).count,
      conversation: root.id,
    }),
  }

  const decoder = new FrameDecoder((head) => {
    if (head.kind !== 'req') return
    const handler = commands[head.method]
    const reply = handler
      ? handler(head.params || {}).then(
        (result) => ({ kind: 'res', id: head.id, status: 'done', ...result }),
        (error) => ({ kind: 'res', id: head.id, status: 'error', code: 'failed', message: String(error?.message || error) }))
      : Promise.resolve({ kind: 'res', id: head.id, status: 'error', code: 'unknown_method', message: `unknown method: ${head.method}` })
    reply.then((frame) => send(frame))
  })

  process.stdin.on('data', (chunk) => {
    try { decoder.push(chunk) } catch (error) {
      // 帧格式错误说明双方已失步，无法再可靠解析，交给宿主重启
      console.error(error instanceof ProtocolError ? `protocol error: ${error.message}` : error)
      process.exit(3)
    }
  })
  process.stdin.on('end', async () => {
    await harness.close(context).catch(() => {})
    process.exit(0)
  })

  send({ kind: 'evt', method: 'ready', params: { protocol: PROTOCOL, node: process.version, boots: boot.count } })
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
