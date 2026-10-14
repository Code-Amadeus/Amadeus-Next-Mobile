import assert from 'node:assert/strict'
import { mock, test } from 'node:test'
import { PAUSE_MS, SETTLE_MS, VoiceHub } from '../src/voice/hub.js'

function setup({ fishKey = '', continuous = false } = {}) {
  const emitted = []
  const submitted = []
  const tts = []
  const config = {
    values: () => ({ voice: { speech: true, continuous, locale: 'zh-CN', fishModel: 'm', fishVoiceId: '' } }),
    secret: (name) => (name === 'fishApiKey' ? fishKey : ''),
  }
  const hub = new VoiceHub({
    config,
    emit: (method, params, body) => emitted.push({ method, params, body }),
    submit: async (text) => { submitted.push(text); return { turnId: 'x' } },
    makeTts: (settings, onEvent) => {
      const fake = { calls: [], onEvent, settings }
      for (const name of ['start', 'push', 'finish', 'abort']) fake[name] = (arg) => fake.calls.push([name, arg])
      tts.push(fake)
      return fake
    },
  })
  const commands = () => emitted.filter((e) => e.method === 'asr.command').map((e) => e.params)
  return { hub, emitted, submitted, tts, commands }
}

test('a pause after partial text finishes recognition and submits the final transcript', async (t) => {
  mock.timers.enable({ apis: ['setTimeout'] })
  t.after(() => mock.timers.reset())
  const { hub, submitted, commands } = setup()
  hub.listen('start')
  const { recognition_id: id } = commands()[0]
  hub.asrMessage({ recognition_id: id, type: 'status', status: 'listening' })
  hub.asrMessage({ recognition_id: id, type: 'recognized', text: '打开设', is_final: false })
  hub.asrMessage({ recognition_id: id, type: 'recognized', text: '打开设置', is_final: false })
  assert.equal(hub.state(), 'hearing')
  mock.timers.tick(PAUSE_MS)
  assert.equal(commands().at(-1).command, 'finish')
  hub.asrMessage({ recognition_id: id, type: 'recognized', text: '打开设置', is_final: true })
  await Promise.resolve()
  assert.deepEqual(submitted, ['打开设置'])
  assert.equal(hub.state(), 'idle')
})

test('messages from an old recognition and speech during a turn are ignored', () => {
  const { hub, submitted, commands } = setup()
  hub.listen('start')
  const { recognition_id: id } = commands()[0]
  hub.asrMessage({ recognition_id: id - 1, type: 'recognized', text: '旧的', is_final: true })
  hub.onTurnEvent('turn.begin', { turnId: 't1' })
  // 回合开始会停掉识别
  assert.equal(commands().at(-1).command, 'stop')
  hub.asrMessage({ recognition_id: id, type: 'recognized', text: '她自己的声音', is_final: true })
  assert.deepEqual(submitted, [])
})

test('a spoken turn streams subtitles and speech to TTS and waits for playback to drain', (t) => {
  mock.timers.enable({ apis: ['setTimeout'] })
  t.after(() => mock.timers.reset())
  const { hub, emitted, tts } = setup({ fishKey: 'k' })
  hub.onTurnEvent('turn.begin', { turnId: 't1' })
  const begin = emitted.find((e) => e.params.type === 'begin').params
  assert.equal(begin.speech, true)
  hub.onTurnEvent('turn.delta', { text: '<zh>你好</zh><jp>[EMO preset=smile]こんにちは</jp>' })
  hub.onTurnEvent('turn.end', {})
  const fake = tts[0]
  assert.deepEqual(fake.calls.map((c) => c[0]), ['start', 'push', 'finish'])
  // 只标了表情时按表情补一个语气标签（presentation.ts 的既有行为）
  assert.equal(fake.calls[1][1], '[happy]こんにちは')
  const text = emitted.find((e) => e.params.type === 'text').params.value
  assert.equal(text.display, '你好')
  assert.equal(text.cues[0].emotion, 'smile')

  fake.onEvent({ ...begin, type: 'audio', pcm: new Uint8Array([1, 2]), alignment: null })
  const audio = emitted.find((e) => e.params.type === 'audio')
  assert.deepEqual([...audio.body.bytes], [1, 2])
  assert.equal(audio.params.pcm, undefined)

  assert.equal(hub.busy(), true)
  hub.playback({ generation: begin.generation, type: 'ended' })
  assert.equal(hub.busy(), false)
  mock.timers.tick(SETTLE_MS)
  assert.equal(hub.state(), 'idle')
})

test('without a Fish key the turn is text only and never blocks the floor', () => {
  const { hub, tts } = setup()
  hub.onTurnEvent('turn.begin', { turnId: 't1' })
  hub.onTurnEvent('turn.end', {})
  assert.equal(tts.length, 0)
  assert.equal(hub.busy(), false)
})

test('proactive turns wait while the user is speaking', async () => {
  const { hub, commands } = setup()
  hub.listen('start')
  const { recognition_id: id } = commands()[0]
  let ran = 0
  const pending = hub.proactive(async () => { ran++; return { turnId: 'p' } })
  await Promise.resolve()
  assert.equal(ran, 0)
  hub.asrMessage({ recognition_id: id, type: 'status', status: 'idle' })
  assert.deepEqual(await pending, { turnId: 'p' })
  assert.equal(ran, 1)
})

test('abort resets the turn and advances the generation', () => {
  const { hub, emitted, tts } = setup({ fishKey: 'k' })
  hub.onTurnEvent('turn.begin', { turnId: 't1' })
  hub.abort()
  assert.equal(tts[0].calls.at(-1)[0], 'abort')
  assert.ok(emitted.some((e) => e.params.type === 'reset'))
  assert.equal(hub.busy(), false)
})

test('a continuous session keeps listening after each turn until stopped', (t) => {
  mock.timers.enable({ apis: ['setTimeout'] })
  t.after(() => mock.timers.reset())
  const { hub, commands } = setup()
  hub.listen('start', { continuous: true })
  const first = commands()[0].recognition_id
  hub.asrMessage({ recognition_id: first, type: 'recognized', text: '你好', is_final: true })
  hub.onTurnEvent('turn.begin', { turnId: 't1' })
  hub.onTurnEvent('turn.end', {})
  mock.timers.tick(SETTLE_MS)
  // 回合结束、静置之后自动重新开麦
  assert.equal(commands().at(-1).command, 'start')
  assert.ok(commands().at(-1).recognition_id > first)
  hub.listen('stop')
  assert.equal(hub.session, false)
  assert.equal(hub.listening, false)
})
