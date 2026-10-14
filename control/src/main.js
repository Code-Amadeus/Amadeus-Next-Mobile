import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { Brain } from './brain.js'
import { Config } from './config.js'
import { EmoteModels } from './emote/models.js'
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
// 源模型随 payload 放在 app/emote/models，烘焙产物缓存在数据目录，宿主让 WebView 从那里读
const emoteSources = process.env.AMADEUS_EMOTE_SOURCES || fileURLToPath(new URL('../../emote/models', import.meta.url))

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
  // 界面回到前台时据此重新同步，补上后台期间可能漏掉的事件
  host.handle('chat.state', () => ({ busy: brain.busy, tool: brain.tool ?? null, turnId: brain.turn ?? null, sessionId: brain.conv.id }))
  host.handle('session.new', () => { voice.abort(); return brain.newSession() })
  host.handle('session.list', () => brain.listSessions())
  host.handle('session.switch', ({ id }) => { voice.abort(); return brain.switchSession(id) })
  host.handle('config.get', () => config.publicView())
  host.handle('config.set', ({ values, secrets }) => config.update({ values, secrets }))
  // 提醒到点、通知到达由宿主触发；requestId 保证宿主重发时不会重复开口
  host.handle('proactive', ({ kind, text, requestId }) => voice.proactive(() => brain.proactive(kind, String(text ?? ''), requestId)))
  const emote = new EmoteModels({ sourceDir: emoteSources, cacheDir: path.join(dataDir, 'emote-cache') })
  host.handle('emote.list', () => ({ looks: emote.list() }))
  host.handle('emote.prepare', ({ model }) => emote.prepare(String(model)))
  host.handle('voice.listen', ({ action, continuous }) => voice.listen(action, { continuous }))
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
