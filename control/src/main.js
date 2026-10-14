import path from 'node:path'
import { Brain } from './brain.js'
import { Config } from './config.js'
import { ModelHub } from './models.js'
import { Peer } from './peer.js'
import { OpsClient } from './phone.js'
import { VoiceHub } from './voice/hub.js'

export const PROTOCOL = 1

// stdout 只允许写帧；任何库往 stdout 打日志都会破坏协议，统一改道到 stderr
for (const level of ['log', 'info', 'debug']) console[level] = (...args) => console.error(...args)

const dataDir = process.env.AMADEUS_DATA || path.resolve('data')
const workspace = process.env.AMADEUS_WORKSPACE || path.join(dataDir, 'workspace')
const opsSocket = process.env.AMADEUS_OPS_SOCK || path.join(dataDir, 'ops.sock')

async function main() {
  const host = new Peer({
    write: (bytes) => process.stdout.write(bytes),
    onProtocolError: (error) => {
      // 帧格式错误说明双方已失步，无法再可靠解析，交给宿主重启
      console.error(`protocol error: ${error.message}`)
      process.exit(3)
    },
  })
  process.stdin.on('data', (chunk) => host.push(chunk))

  const config = new Config(dataDir)
  const hub = new ModelHub(config)
  const ops = new OpsClient(opsSocket)
  let voice = null
  const brain = new Brain({
    dataDir, workspace, hub, ops,
    emit: (method, params) => { host.emit(method, params); voice?.onTurnEvent(method, params) },
  })
  voice = new VoiceHub({
    config,
    emit: (method, params, body) => host.emit(method, params, body),
    submit: (text) => brain.submit(text, { source: 'voice' }),
  })
  await brain.open()

  config.onChange(() => {
    hub.apply()
    brain.syncModel().catch((error) => console.error('model sync failed', error))
  })

  host.handle('ping', () => ({ pong: true }))
  host.handle('chat.submit', ({ text, source, requestId }) => {
    if (typeof text !== 'string' || !text.trim()) throw Object.assign(new Error('text is required'), { code: 'bad_args' })
    return brain.submit(text, { source: source === 'voice' ? 'voice' : 'text', requestId })
  })
  host.handle('chat.abort', () => { voice.abort(); return brain.abort() })
  host.handle('chat.history', ({ limit }) => ({ items: brain.history(Number(limit) || 50) }))
  host.handle('config.get', () => config.publicView())
  host.handle('config.set', ({ values, secrets }) => config.update({ values, secrets }))
  // 提醒到点、通知到达由宿主触发；requestId 保证宿主重发时不会重复开口
  host.handle('proactive', ({ kind, text, requestId }) => voice.proactive(() => brain.proactive(kind, String(text ?? ''), requestId)))
  host.handle('voice.listen', ({ action }) => voice.listen(action))
  host.on('asr.message', (message) => voice.asrMessage(message))
  host.on('voice.playback', (event) => voice.playback(event))

  process.stdin.on('end', async () => {
    ops.close()
    await brain.close()
    process.exit(0)
  })

  host.emit('ready', { protocol: PROTOCOL, node: process.version, configured: Boolean(hub.ref()) })
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
