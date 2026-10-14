import assert from 'node:assert/strict'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import net from 'node:net'
import os from 'node:os'
import path from 'node:path'
import { test } from 'node:test'
import { fauxAssistantMessage, fauxProvider, fauxText, fauxToolCall } from '@earendil-works/pi-ai'
import { trimScreens } from '../src/agent.js'
import { Brain } from '../src/brain.js'
import { Config } from '../src/config.js'
import { ModelHub } from '../src/models.js'
import { Peer } from '../src/peer.js'
import { OpsClient } from '../src/phone.js'

// 真实模型发起工具调用时 stopReason 为 toolUse；faux 默认是 stop，回合会直接结束
const toolUse = (name, args) => fauxAssistantMessage(fauxToolCall(name, args), { stopReason: 'toolUse' })

/** 假执行面：记录收到的动词并按表应答。 */
function fakeOps(socketPath, handlers) {
  const calls = []
  const server = net.createServer((socket) => {
    const peer = new Peer({ write: (bytes) => socket.write(bytes), onProtocolError: () => socket.destroy() })
    for (const [verb, handler] of Object.entries(handlers)) {
      peer.handle(verb, (args) => { calls.push({ verb, args }); return handler(args) })
    }
    socket.on('data', (chunk) => peer.push(chunk))
  })
  return new Promise((resolve) => server.listen(socketPath, () => resolve({ server, calls })))
}

async function setup(t, responses, handlers) {
  const dir = mkdtempSync(path.join(os.tmpdir(), 'brain-'))
  t.after(() => rmSync(dir, { recursive: true, force: true }))
  const socketPath = path.join(dir, 'ops.sock')
  const { server, calls } = await fakeOps(socketPath, handlers)
  t.after(() => server.close())
  const faux = fauxProvider({ provider: 'faux', models: [{ id: 'faux-1', input: ['text', 'image'] }] })
  faux.setResponses(responses)
  const events = []
  const ops = new OpsClient(socketPath)
  t.after(() => ops.close())
  const brain = new Brain({
    dataDir: dir,
    workspace: path.join(dir, 'workspace'),
    hub: new ModelHub(new Config(dir), { provider: faux.provider }),
    ops,
    emit: (method, params) => events.push({ method, ...params }),
  })
  await brain.open()
  t.after(() => brain.close())
  const settled = () => new Promise((resolve) => {
    const timer = setInterval(() => {
      if (events.some((e) => e.method === 'turn.end')) { clearInterval(timer); resolve() }
    }, 20)
  })
  return { brain, events, calls, dir, settled }
}

test('runs a tool loop through the execution plane and streams turn events', async (t) => {
  const { brain, events, calls, settled } = await setup(t, [
    toolUse('observe', { mode: 'nodes' }),
    toolUse('click', { ref: 's1:3' }),
    fauxAssistantMessage(fauxText('<zh>设置已打开</zh><jp>設定を開いたわ</jp>')),
  ], {
    observe: () => ({ snapshot: 's1', package: 'com.android.launcher', text: '[s1:3] 按钮 "设置" 可点' }),
    click: () => ({}),
  })

  await brain.submit('打开设置')
  await settled()

  assert.deepEqual(calls.map((c) => c.verb), ['observe', 'click'])
  assert.equal(calls[1].args.ref, 's1:3')
  const methods = events.map((e) => e.method)
  assert.equal(methods[0], 'turn.begin')
  assert.equal(methods.at(-1), 'turn.end')
  const tools = events.filter((e) => e.method === 'turn.tool' && e.phase === 'end')
  assert.deepEqual(tools.map((e) => e.name), ['observe', 'click'])
  assert.match(tools[0].summary, /screen_content/)
  const deltas = events.filter((e) => e.method === 'turn.delta')
  assert.ok(deltas.length)
  // full 是累计全文，最后一条等于完整回复
  assert.match(deltas.at(-1).full, /设置已打开/)
  assert.equal(typeof deltas.at(-1).message, 'number')
  const history = brain.history()
  assert.equal(history[0].role, 'user')
  assert.match(history.at(-1).text, /设置已打开/)
})

test('verb errors from the execution plane reach the model as tool errors', async (t) => {
  const { brain, events, settled } = await setup(t, [
    toolUse('click', { ref: 's9:1' }),
    fauxAssistantMessage(fauxText('<zh>好</zh>')),
  ], {
    click: () => { throw Object.assign(new Error('screen changed, observe again'), { code: 'stale_ref' }) },
  })
  await brain.submit('点一下')
  await settled()
  const end = events.find((e) => e.method === 'turn.tool' && e.phase === 'end')
  assert.equal(end.isError, true)
  assert.match(end.summary, /stale_ref/)
})

test('file tools cannot leave the workspace', async (t) => {
  const { brain, events, dir, settled } = await setup(t, [
    toolUse('read', { path: '../config.json' }),
    fauxAssistantMessage(fauxText('<zh>好</zh>')),
  ], {})
  writeFileSync(path.join(dir, 'config.json'), '{"secret":true}')
  await brain.submit('读配置')
  await settled()
  const end = events.find((e) => e.method === 'turn.tool' && e.phase === 'end')
  assert.equal(end.isError, true)
  assert.match(end.summary, /outside the workspace/)
})

test('proactive turns are framed as data, and unknown kinds are rejected', async (t) => {
  const { brain, settled } = await setup(t, [fauxAssistantMessage(fauxText('<zh>提醒你喝水</zh>'))], {})
  await brain.proactive('reminder', '喝水')
  await settled()
  assert.match(brain.history()[0].text, /Reminder due/)
  await assert.rejects(brain.proactive('other', 'x'), /unknown proactive kind/)
})

test('trimScreens keeps only recent screen reads and images', () => {
  const result = (name, content) => ({ role: 'toolResult', toolName: name, toolCallId: name, content, isError: false })
  const image = { type: 'image', data: 'AAAA', mimeType: 'image/png' }
  const messages = [
    result('observe', [{ type: 'text', text: 'old 1' }]),
    result('screenshot', [{ type: 'text', text: 'shot 1' }, image]),
    result('observe', [{ type: 'text', text: 'old 2' }]),
    result('camera_snap', [{ type: 'text', text: 'photo' }, image]),
    result('screenshot', [{ type: 'text', text: 'shot 2' }, image]),
    result('observe', [{ type: 'text', text: 'new' }]),
  ]
  const trimmed = trimScreens(messages)
  // 从新到旧：读屏保留 new / shot 2 / old 2，图片保留 shot 2 / photo
  assert.equal(trimmed[0].content[0].text, '[earlier screen content omitted]')
  assert.equal(trimmed[1].content[0].text, '[earlier screen content omitted]')
  assert.equal(trimmed[1].content[1].text, '[earlier image omitted]')
  assert.equal(trimmed[2].content[0].text, 'old 2')
  assert.equal(trimmed[3].content[1].type, 'image')
  assert.equal(trimmed[4].content[1].type, 'image')
  assert.equal(trimmed[5].content[0].text, 'new')
  assert.equal(trimScreens(messages.slice(-1)), undefined)
})

test('new sessions start empty, old ones can be reopened, and the choice survives a restart', async (t) => {
  const { brain, events, dir, settled } = await setup(t, [
    fauxAssistantMessage(fauxText('<zh>第一个会话</zh>')),
    fauxAssistantMessage(fauxText('<zh>第二个会话</zh>')),
  ], {})
  await brain.submit('你好，这是第一段')
  await settled()
  const first = brain.conv.id

  // 空会话不会因为连点"新对话"而堆积
  const fresh = await brain.newSession()
  assert.notEqual(fresh.id, first)
  assert.equal((await brain.newSession()).id, fresh.id)
  assert.deepEqual(brain.history(), [])
  assert.ok(events.some((e) => e.method === 'session.changed'))

  events.length = 0
  await brain.submit('第二段话')
  await settled()
  const list = brain.listSessions().items
  assert.deepEqual(list.map((item) => item.title), ['第二段话', '你好，这是第一段'])
  assert.equal(list[0].current, true)

  await brain.switchSession(first)
  assert.match(brain.history().at(-1).text, /第一个会话/)
  await assert.rejects(brain.switchSession(9999), /unknown session/)
  await brain.close()

  // 重启后回到上次所在的会话
  const reopened = new Brain({ dataDir: dir, workspace: path.join(dir, 'workspace'), hub: brain.hub, ops: brain.ops, emit: () => {} })
  await reopened.open()
  t.after(() => reopened.close())
  assert.equal(reopened.conv.id, first)
  assert.match(reopened.history().at(-1).text, /第一个会话/)
})
