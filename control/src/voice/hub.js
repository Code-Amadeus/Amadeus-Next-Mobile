import { FishTts } from './fishTts.ts'
import { PresentationText } from './presentation.ts'

export const PAUSE_MS = 1500
export const NO_TRANSCRIPT_MS = 30_000
export const FINAL_TIMEOUT_MS = 5000
export const SETTLE_MS = 1000

/**
 * 手机端的话筒仲裁与语音管线，规则沿用 amadeus-next 的 Floor / SpeechBridge：
 * - 回合进行中（模型未结束或播放未排空）不开麦，识别结果一律丢弃，避免把她自己的声音当成输入；
 * - partial 停顿 1.5 s 自动收尾，开麦 30 s 无转写自动关闭；
 * - 回合结束、播放排空后静置 1 s，常开模式再自动开麦。
 * 主动开口（提醒、通知）在主人说话或回合进行中时排队，空闲后再起。
 */
export class VoiceHub {
  #recognitionId = 0
  #generation = 0
  #pauseTimer = null
  #guardTimer = null
  #resumeTimer = null
  #turn = null
  #queue = []

  constructor({ config, emit, submit, makeTts }) {
    this.config = config
    this.emit = emit
    this.submit = submit
    this.makeTts = makeTts ?? ((settings, onEvent) => new FishTts(() => settings(), onEvent))
    this.listening = false
    // 视频模式的连续对话：只在这一通"通话"里生效，不改设置里的常开监听
    this.session = false
    this.asrOn = false
    this.hearing = false
    this.finishing = false
    this.transcript = ''
  }

  get settings() { return this.config.values().voice }

  busy() { return Boolean(this.#turn && (!this.#turn.done || !this.#turn.drained)) }

  // ---- 麦克风 ----

  listen(action, { continuous = false } = {}) {
    if (action === 'start') {
      this.listening = true
      this.session = Boolean(continuous)
      this.#pump()
    } else if (action === 'finish') {
      if (!this.settings.continuous && !this.session) this.listening = false
      this.#finish(true)
    } else {
      this.listening = false
      this.session = false
      this.#stopAsr()
    }
    return { state: this.state() }
  }

  state() {
    if (this.busy()) return 'responding'
    if (this.asrOn) return this.hearing ? 'hearing' : 'armed'
    return this.#resumeTimer ? 'settling' : 'idle'
  }

  /** 宿主转来的识别消息，格式与 Apple Speech helper 的 JSON 行一致。 */
  asrMessage(message) {
    if (message.recognition_id !== this.#recognitionId) return
    if (message.type === 'status') {
      if (message.status === 'listening' && !this.transcript) this.#armGuard(NO_TRANSCRIPT_MS, '30 秒内没有识别到语音，已关闭麦克风')
      if (message.status === 'idle') this.#asrEnded()
      this.emit('voice.asr', { status: message.status })
    } else if (message.type === 'error') {
      this.#asrEnded()
      this.emit('voice.asr', { status: 'error', error: message.error })
    } else if (message.type === 'recognized') {
      const text = String(message.text || '').trim()
      if (!message.is_final) {
        if (text && text !== this.transcript) {
          this.transcript = text
          this.hearing = true
          clearTimeout(this.#guardTimer)
          clearTimeout(this.#pauseTimer)
          // 停顿阈值内无新增即判定为断句，自动收尾
          this.#pauseTimer = setTimeout(() => this.#finish(false), PAUSE_MS)
        }
        if (text) this.emit('voice.asr', { status: 'partial', text })
        return
      }
      const manual = this.finishing === 'manual'
      this.#asrEnded()
      if (!text) return
      this.emit('voice.asr', { status: 'final', text })
      if (this.busy()) return
      if (!manual && !this.listening) return
      this.submit(text).catch((error) => this.emit('voice.asr', { status: 'error', error: String(error.message || error) }))
    }
  }

  #pump() {
    if (!this.listening || this.asrOn || this.busy() || this.#resumeTimer) return
    this.asrOn = true
    this.hearing = false
    this.finishing = false
    this.transcript = ''
    this.emit('asr.command', { command: 'start', locale: this.settings.locale || 'zh-CN', recognition_id: ++this.#recognitionId })
  }

  #finish(manual) {
    clearTimeout(this.#pauseTimer)
    if (!this.asrOn || this.finishing) return
    this.finishing = manual ? 'manual' : 'auto'
    this.hearing = true
    this.#armGuard(FINAL_TIMEOUT_MS, '等待最终识别结果超时，请重试')
    this.emit('asr.command', { command: 'finish', recognition_id: this.#recognitionId })
  }

  #stopAsr() {
    if (!this.asrOn) return
    this.emit('asr.command', { command: 'stop', recognition_id: this.#recognitionId })
    this.#recognitionId++
    this.#asrEnded()
  }

  #armGuard(ms, message) {
    clearTimeout(this.#guardTimer)
    this.#guardTimer = setTimeout(() => {
      this.#stopAsr()
      this.emit('voice.asr', { status: 'error', error: message })
    }, ms)
  }

  #asrEnded() {
    clearTimeout(this.#pauseTimer)
    clearTimeout(this.#guardTimer)
    this.#guardTimer = null
    this.asrOn = false
    this.hearing = false
    this.finishing = false
    this.transcript = ''
    this.#flushQueue()
  }

  // ---- 回合与 TTS ----

  /** 主动开口经此排队：主人正在说话或回合进行中时延后。 */
  proactive(run) {
    return new Promise((resolve, reject) => {
      this.#queue.push({ run, resolve, reject })
      this.#flushQueue()
    })
  }

  #flushQueue() {
    if (this.asrOn || this.busy() || !this.#queue.length) return
    const { run, resolve, reject } = this.#queue.shift()
    run().then(resolve, reject)
  }

  /** brain 的回合事件在这里分叉成字幕、表情与 TTS。 */
  onTurnEvent(method, params) {
    if (method === 'turn.begin') this.#begin(params)
    else if (method === 'turn.delta') this.#text(params.text, false)
    else if (method === 'turn.tool' && params.phase === 'start') this.#voice({ type: 'activity', activity: 'tool' })
    else if (method === 'turn.end') this.#end()
  }

  #begin({ turnId }) {
    this.#stopAsr()
    clearTimeout(this.#resumeTimer)
    this.#resumeTimer = null
    const settings = this.settings
    const fishKey = this.config.secret('fishApiKey')
    const speech = Boolean(settings.speech && fishKey)
    const key = { sessionId: 'root', turnId: String(turnId), generation: ++this.#generation }
    this.#turn?.tts?.abort()
    const turn = { key, speech, parser: new PresentationText(), done: false, drained: !speech, spoke: false, tts: null }
    this.#turn = turn
    if (speech) {
      turn.tts = this.makeTts(() => ({ FISH_API_KEY: fishKey, FISH_API_MODEL: settings.fishModel, FISH_VOICE_ID: settings.fishVoiceId || undefined }), (event) => this.#ttsEvent(turn, event))
      turn.tts.start(key)
    }
    this.#voice({ type: 'begin', speech, calibration: { mouthGain: 8, silenceThreshold: 0.08, syncOffsetMs: 0 } })
    this.#voice({ type: 'activity', activity: 'thinking' })
  }

  #text(delta, final) {
    const turn = this.#turn
    if (!turn || turn.done) return
    const presented = final ? turn.parser.finish() : turn.parser.push(delta)
    if (presented.text || presented.display || presented.subtitles.length) {
      if (!turn.spoke) { turn.spoke = true; this.#voice({ type: 'activity', activity: 'idle' }) }
      this.#voice({ type: 'text', value: presented })
    }
    if (presented.speech) turn.tts?.push(presented.speech)
  }

  #end() {
    const turn = this.#turn
    if (!turn) return
    this.#text('', true)
    turn.done = true
    this.#voice({ type: 'activity', activity: 'idle' })
    if (turn.tts) turn.tts.finish()
    this.#maybeSettle()
  }

  #ttsEvent(turn, event) {
    if (turn !== this.#turn) return
    if (event.type === 'audio') {
      const { pcm, ...rest } = event
      this.emit('voice.event', rest, { mime: 'audio/pcm;rate=24000', bytes: pcm })
      return
    }
    if (event.type === 'error') {
      // 与 amadeus-next 一致：TTS 失败只降级为文字，回合照常完成
      turn.drained = true
      this.#maybeSettle()
    }
    this.emit('voice.event', event)
  }

  /** 宿主 AudioTrack 的播放进度。 */
  playback(event) {
    const turn = this.#turn
    if (!turn || event.generation !== turn.key.generation) return
    if (event.type === 'ended' || event.type === 'error') {
      turn.drained = true
      this.#maybeSettle()
    }
  }

  /** 中止当前回合：TTS 断开、generation 前进，宿主据此丢弃过期音频。 */
  abort() {
    const turn = this.#turn
    if (!turn) return
    turn.tts?.abort()
    turn.done = true
    turn.drained = true
    this.#generation++
    this.#voice({ type: 'reset' })
    this.#maybeSettle()
  }

  #maybeSettle() {
    if (this.busy()) return
    clearTimeout(this.#resumeTimer)
    this.#resumeTimer = setTimeout(() => {
      this.#resumeTimer = null
      this.#flushQueue()
      this.#pump()
    }, SETTLE_MS)
  }

  #voice(event) {
    const key = this.#turn?.key ?? { sessionId: 'root', turnId: '', generation: this.#generation }
    this.emit('voice.event', { ...key, ...event })
  }
}
