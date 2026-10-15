import { catchUp, on, request, startSync } from './bridge.ts'
import { setupMemory } from './memory.ts'
import { closeSwiped, swipeRow } from './swipe.ts'
import { createEmoteRenderer, type EmoteCharacter } from './emote.ts'

const $ = <T extends HTMLElement = HTMLElement>(selector: string) => document.querySelector(selector) as T

const log = $('#log')
const input = $<HTMLTextAreaElement>('#input')
const mic = $('#mic')
const sendButton = $('#send')
const stopButton = $('#stop')
const hearing = $('#hearing')
const callView = $('#call-view')
const subtitle = $('#subtitle')
const youSaid = $('#you-said')

let busy = false
// 回复气泡按 (回合, 第几条消息) 索引：同一条消息的全文反复到达时只覆盖，不追加
const bubbles = new Map<string, HTMLElement>()
let lastSpeaker: 'me' | 'her' | null = null

const VERB_NAMES: Record<string, string> = {
  observe: '看屏幕', screenshot: '截图', click: '点击', swipe: '滑动', set_value: '输入文字', enter: '确认输入', key: '按键',
  launch_app: '打开应用', list_apps: '查找应用', wait: '等待', camera_snap: '拍照', camera_clip: '录像',
  remind_set: '设提醒', remind_list: '看提醒', remind_cancel: '取消提醒', read: '读文件', write: '写文件', edit: '改文件',
}

// WebView 未开 DOM Storage 时 localStorage 为 null，偏好只是记不住，不能让脚本中断
const prefs = {
  get: (key: string) => { try { return localStorage.getItem(key) } catch { return null } },
  set: (key: string, value: string) => { try { localStorage.setItem(key, value) } catch { /* 忽略 */ } },
}

const vibrate = (ms: number) => { try { navigator.vibrate?.(ms) } catch { /* 部分 WebView 不支持 */ } }

let toastTimer = 0
function toast(text: string) {
  const el = $('#toast')
  el.textContent = text
  el.hidden = false
  clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => { el.hidden = true }, 2400)
}

// 长按菜单只留给输入框
document.addEventListener('contextmenu', (event) => {
  if (!(event.target instanceof HTMLInputElement || event.target instanceof HTMLTextAreaElement)) event.preventDefault()
})

/** 状态同时显示在聊天顶栏和视频模式的胶囊里；null 表示空闲。 */
function setPresence(text: string | null) {
  const presence = $('#presence')
  presence.textContent = text ?? '在线'
  presence.parentElement!.classList.toggle('busy', Boolean(text))
  const chip = $('#call-state')
  chip.hidden = !text
  if (text) chip.textContent = text
}

// ---- 对话模式 ----

function scrollToEnd() { log.scrollTop = log.scrollHeight }

function message(who: 'me' | 'her', text: string): HTMLElement {
  $('#welcome').hidden = true
  const row = document.createElement('div')
  row.className = `msg ${who}`
  if (lastSpeaker !== who) log.append(Object.assign(document.createElement('div'), { className: 'gap' }))
  if (who === 'her') {
    const face = document.createElement('img')
    face.src = 'ui/logo.png'
    // 连续几条只在第一条显示头像
    face.className = lastSpeaker === 'her' ? 'face ghost' : 'face'
    row.append(face)
  }
  const bubble = document.createElement('div')
  bubble.className = 'bubble'
  bubble.textContent = text
  row.append(bubble)
  log.append(row)
  lastSpeaker = who
  while (log.childElementCount > 300) log.children[1].remove()
  scrollToEnd()
  return bubble
}

function notice(text: string) {
  $('#welcome').hidden = true
  log.append(Object.assign(document.createElement('div'), { className: 'notice', textContent: text }))
  lastSpeaker = null
  scrollToEnd()
}

/** 输入框有字时显示发送，没字时显示麦克风；她回复时都换成停止。 */
function syncComposer() {
  const typed = input.value.trim().length > 0
  stopButton.hidden = !busy
  sendButton.hidden = busy || !typed
  mic.hidden = busy || typed
}

async function send(text: string) {
  text = text.trim()
  if (!text) return
  message('me', text)
  try { await request('chat.submit', { text }) } catch (error: any) {
    if (error.code === 'not_configured') { notice('还没有配置模型'); openSettings() } else notice(`发送失败：${error.message}`)
  }
}

input.addEventListener('input', () => {
  input.style.height = ''
  input.style.height = `${Math.min(128, input.scrollHeight)}px`
  syncComposer()
})
input.addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); submitInput() }
})
function submitInput() {
  const text = input.value
  input.value = ''
  input.style.height = ''
  syncComposer()
  send(text)
}
sendButton.addEventListener('click', submitInput)
stopButton.addEventListener('click', () => { vibrate(10); request('chat.abort').catch(() => {}) })
for (const chip of document.querySelectorAll<HTMLButtonElement>('[data-say]')) chip.addEventListener('click', () => send(chip.dataset.say!))

// 点一下开始听，停顿 1.5 秒自动发送，或再点一下立即发送。
// 不用"按住说话"：三星等系统的长按识屏会在 1.5 秒左右抢走长按手势，应用层拦不住
let chatListening = false
function setChatListening(value: boolean) {
  chatListening = value
  mic.classList.toggle('on', value)
  hearing.hidden = !value
  if (value) hearing.textContent = '正在听…'
}
mic.addEventListener('click', () => {
  vibrate(12)
  if (chatListening) {
    setChatListening(false)
    request('voice.listen', { action: 'finish' }).catch(() => {})
    return
  }
  setChatListening(true)
  request('voice.listen', { action: 'start' }).catch((error) => { setChatListening(false); toast(`麦克风不可用：${error.message}`) })
})

// ---- 视频模式 ----

let renderer: ReturnType<typeof createEmoteRenderer> | null = null
let character: EmoteCharacter | null = null
let callActive = false
let muted = false
let callTimer = 0

// ---- 形象 ----

type Look = { id: string; model: string; arm: number | null; label: string }
const DEFAULT_LOOK: Look = { id: 'white-b', model: 'kurisu_ab_white', arm: 1, label: '白衣 · 抱臂' }
let looks: Look[] = []
let look: Look = DEFAULT_LOOK

async function loadLooks() {
  if (looks.length) return
  looks = (await request('emote.list')).looks
  look = looks.find((item) => item.id === prefs.get('look')) ?? looks[0] ?? DEFAULT_LOOK
}

async function ensureCharacter() {
  if (renderer) return
  $('#call-error').textContent = ''
  await loadLooks()
  await (window as any).loadEmoteRuntime()
  // 第一次用某个模型要在手机上烘焙，之后走缓存
  $('#call-error').textContent = '正在准备形象…'
  const baked = await request('emote.prepare', { model: look.model })
  renderer = createEmoteRenderer($('#stage'), { full: true, upper: { head: 0.04, waist: 0.85, shift: 0.12 } })
  character = await renderer.load(
    { psbUrl: `/emote-cache/${baked.psb}`, metaUrl: `/emote-cache/${baked.meta}`, arm: look.arm },
    (text) => { $('#call-error').textContent = text },
  )
  $('#call-error').textContent = ''
  setInterval(() => { if (callActive) character?.dispatch({ type: 'tick', now: performance.now() }) }, 50)
}

function listenInCall() {
  if (muted) return request('voice.listen', { action: 'stop' }).catch(() => {})
  return request('voice.listen', { action: 'start', continuous: true }).catch((error) => toast(`麦克风不可用：${error.message}`))
}

async function startCall() {
  if (callActive) return
  callActive = true
  callView.hidden = false
  subtitle.hidden = true
  youSaid.hidden = true
  $('#call-timer').textContent = '正在接通…'
  overlays.push(() => (callActive ? (endCall(), true) : false))
  setChatListening(false)
  try {
    await ensureCharacter()
    renderer?.controls.pause(false)
  } catch (error) {
    $('#call-error').textContent = `立绘加载失败：${(error as Error).message}`
  }
  if (!callActive) return
  const started = Date.now()
  const tick = () => {
    const s = Math.floor((Date.now() - started) / 1000)
    $('#call-timer').textContent = `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`
  }
  tick()
  callTimer = window.setInterval(tick, 1000)
  listenInCall()
}

function endCall() {
  if (!callActive) return
  callActive = false
  clearInterval(callTimer)
  request('voice.listen', { action: 'stop' }).catch(() => {})
  if (busy) request('chat.abort').catch(() => {})
  renderer?.controls.pause(true)
  callView.hidden = true
}

$('#start-call').addEventListener('click', () => { vibrate(12); startCall() })
$('#end-call').addEventListener('click', () => { vibrate(20); endCall() })
$('#call-to-chat').addEventListener('click', () => { endCall(); input.focus() })
$('#call-mute').addEventListener('click', () => {
  vibrate(12)
  muted = !muted
  $('#call-mute').classList.toggle('muted', muted)
  $('#mute-label').textContent = muted ? '已静音' : '静音'
  listenInCall()
})

// ---- 语音识别 ----

on('voice.asr', ({ status, text, error }) => {
  if (status === 'listening') {
    if (callActive) setPresence('正在听')
  } else if (status === 'partial') {
    if (callActive) { youSaid.hidden = false; youSaid.textContent = text } else hearing.textContent = text
  } else if (status === 'final') {
    setChatListening(false)
    youSaid.hidden = true
    message('me', text)
  } else if (status === 'error') {
    setChatListening(false)
    youSaid.hidden = true
    toast(error)
  } else if (status === 'idle') {
    if (!callActive) setChatListening(false)
    if (!busy) setPresence(null)
  }
})

// ---- 回合 ----

on('turn.begin', () => {
  busy = true
  subtitle.textContent = ''
  subtitle.hidden = true
  setPresence('思考中…')
  syncComposer()
})
on('turn.tool', ({ phase, name, isError, summary }) => {
  if (phase === 'start') setPresence(`正在${VERB_NAMES[name] ?? name}`)
  else if (isError) notice(`${VERB_NAMES[name] ?? name}没成功：${reason(summary)}`)
})
on('turn.notice', ({ text }) => toast(text))

/** 工具错误里带有 <harness> 之类的内部标签，只取第一句有意义的话。 */
function reason(summary: unknown): string {
  const line = String(summary || '').split('\n').map((l) => l.replace(/<\/?[\w-]+>/g, '').trim()).find(Boolean)
  return line ? line.slice(0, 80) : '原因未知'
}

on('turn.delta', ({ turnId, message: index, full }) => {
  const key = `${turnId}:${index}`
  const text = displayText(full ?? '')
  if (!text) return
  let bubble = bubbles.get(key)
  if (!bubble) { bubble = message('her', ''); bubbles.set(key, bubble) }
  bubble.textContent = text
  scrollToEnd()
  if (busy && $('#presence').textContent === '思考中…') setPresence('正在说话')
})
on('turn.error', ({ message: text }) => notice(`出错了：${text}`))
// 回合结束以控制面的记录为准重新渲染：过程中的流式显示只是预览
on('turn.end', () => { render() })

// 回到前台：按序号补拉后台期间的事件
on('ui.resume', () => { catchUp() })
document.addEventListener('visibilitychange', () => { if (!document.hidden) catchUp() })

// 语音事件：字幕、表情与口型；音频本身由宿主的 AudioTrack 播放
let speech = false
on('voice.event', (event) => {
  if (event.type === 'begin') {
    speech = event.speech
    character?.dispatch({ type: 'reset' })
    if (speech) character?.dispatch({ type: 'playback', status: 'waiting', now: performance.now() })
  } else if (event.type === 'activity') {
    character?.dispatch({ type: 'activity', activity: event.activity })
  } else if (event.type === 'text') {
    // 聊天气泡由 turn.delta 负责；这里只管视频模式的字幕与表情
    const value = event.value
    const display = value.display ?? value.text
    if (display && callActive) {
      youSaid.hidden = true
      subtitle.hidden = false
      subtitle.textContent = (subtitle.textContent + display).slice(-70)
    }
    for (const cue of value.cues) character?.dispatch({ type: 'cue', emotion: cue.emotion })
  } else if (event.type === 'reset') {
    character?.dispatch({ type: 'reset' })
    subtitle.hidden = true
  } else if (event.type === 'error' && speech) {
    toast('语音合成不可用，只显示文字')
  }
})

let subtitleTimer = 0
on('voice.playback', ({ type, rms }) => {
  if (!character) return
  if (type === 'playing' || type === 'buffering' || type === 'ended') character.dispatch({ type: 'playback', status: type, now: performance.now() })
  if (type === 'level') {
    // 与 amadeus-next 的默认校准一致：增益 8、静音阈值 0.08
    const amplitude = Math.min(1, (rms || 0) * 8)
    character.setMouth(amplitude >= 0.08 ? amplitude : 0)
  }
  renderer?.controls.setSpeaking(type === 'playing' || type === 'level')
  if (type === 'ended') {
    clearTimeout(subtitleTimer)
    subtitleTimer = window.setTimeout(() => { if (!busy) subtitle.hidden = true }, 2500)
  }
})

on('kernel.state', ({ state, detail, progress }) => {
  const banner = $('#kernel')
  banner.hidden = state === 'READY'
  // 控制面（重新）就绪：重启前进行中的回合不会再有结束事件，以控制面现状为准重新渲染
  if (state === 'READY') render()
  banner.textContent = state === 'STARTING' ? `正在唤起Amadeus程序:${progress ?? 0}%`
    : `${state === 'RESTARTING' ? '正在恢复' : '已停止'}：${detail}`
})

// ---- 菜单 ----

/** 打开的浮层按栈管理，安卓返回键从最上层开始关。 */
const overlays: (() => boolean)[] = []

function sheet(id: string) {
  const el = $(`#${id}`)
  const close = () => { if (el.hidden) return false; el.hidden = true; return true }
  el.querySelector('.scrim')!.addEventListener('click', close)
  const grip = el.querySelector('.grip') as HTMLElement
  let startY = 0
  grip.addEventListener('pointerdown', (event) => { startY = event.clientY; grip.setPointerCapture(event.pointerId) })
  grip.addEventListener('pointerup', (event) => { if (event.clientY - startY > -24) close() })
  return { open: () => { el.hidden = false; overlays.push(close) }, close }
}

const menu = sheet('menu')
const sessionsSheet = sheet('sessions')

/**
 * 以控制面为准渲染整个对话区：当前会话的记录 + 是否正在回复。
 * 页面加载、补拉不上、回合结束、换会话、控制面重启时都走这里，界面状态因此不会因漏事件而错乱。
 */
let rendering: Promise<void> | null = null
let renderAgain = false
function render(): Promise<void> {
  // 渲染中又有新的触发（比如回合刚结束）：这一轮取到的记录可能已过时，结束后再来一轮
  if (rendering) { renderAgain = true; return rendering }
  rendering = (async () => {
    try {
      const [{ items }, state] = await Promise.all([request('chat.history', { limit: 60 }), request('chat.state')])
      for (const node of [...log.children]) if (node.id !== 'welcome') node.remove()
      bubbles.clear()
      lastSpeaker = null
      $('#welcome').hidden = items.length > 0
      for (const item of items) message(item.role === 'assistant' ? 'her' : 'me', item.role === 'assistant' ? displayText(item.text) : item.text)
      busy = Boolean(state.busy)
      setPresence(busy ? (state.tool ? `正在${VERB_NAMES[state.tool] ?? state.tool}` : '思考中…') : null)
      syncComposer()
    } catch { /* 控制面尚未就绪；就绪后的 kernel.state 会再渲染一次 */ } finally {
      rendering = null
      if (renderAgain) { renderAgain = false; render() }
    }
  })()
  return rendering
}

on('session.changed', () => { render() })

async function newChat() {
  vibrate(10)
  sessionsSheet.close()
  try { await request('session.new') } catch (error: any) { toast(`新对话失败：${error.message}`) }
}
$('#new-chat').addEventListener('click', newChat)
$('#sessions-new').addEventListener('click', newChat)

function when(at: number): string {
  const date = new Date(at)
  const today = new Date()
  const time = date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
  if (date.toDateString() === today.toDateString()) return `今天 ${time}`
  const yesterday = new Date(today.getTime() - 86_400_000)
  if (date.toDateString() === yesterday.toDateString()) return `昨天 ${time}`
  return `${date.getMonth() + 1}月${date.getDate()}日 ${time}`
}

function emptyHistory(list: HTMLElement) {
  if (!list.querySelector('.swipe-row:not(.removing)')) list.innerHTML = '<p class="hint">还没有历史对话。</p>'
}

function sessionRow(item: any, list: HTMLElement): HTMLElement {
  const row = document.createElement('button')
  row.className = `session${item.current ? ' current' : ''}`
  row.innerHTML = '<div class="what"><div class="title"></div><time></time></div>'
  row.querySelector('.title')!.textContent = item.title
  row.querySelector('time')!.textContent = when(item.updatedAt)
  return swipeRow(row, {
    onTap: async () => {
      sessionsSheet.close()
      if (item.current) return
      try { await request('session.switch', { id: item.id }) } catch (error: any) { toast(`打开失败：${error.message}`) }
    },
    onDelete: async () => {
      vibrate(15)
      try {
        await request('session.delete', { id: item.id })
        return true
      } catch (error: any) {
        toast(`删除失败：${error.message}`)
        return false
      }
    },
    onRemoved: () => emptyHistory(list),
  })
}

$('#open-sessions').addEventListener('click', async () => {
  menu.close()
  sessionsSheet.open()
  closeSwiped()
  const list = $('#session-list')
  list.textContent = ''
  try {
    const { items } = await request('session.list')
    for (const item of items) list.append(sessionRow(item, list))
    emptyHistory(list)
  } catch { list.innerHTML = '<p class="hint">暂时读不到历史对话。</p>' }
})
const lookSheet = sheet('looks')
$('#call-look').addEventListener('click', async () => {
  await loadLooks().catch(() => {})
  const list = $('#look-list')
  list.textContent = ''
  for (const item of looks) {
    const row = document.createElement('button')
    row.className = `menu-item${item.id === look.id ? ' current' : ''}`
    row.textContent = item.label
    row.addEventListener('click', () => {
      lookSheet.close()
      if (item.id === look.id) return
      prefs.set('look', item.id)
      if (item.model === look.model && renderer) {
        look = item
        renderer.controls.setArm(item.arm)
        return
      }
      // 换模型要整页重载：E-mote 旧运行时释放不了 WebGL 堆，重载是唯一干净的办法；重载后自动回到视频模式
      sessionStorage.setItem('resumeCall', '1')
      location.reload()
    })
    list.append(row)
  }
  lookSheet.open()
})

$('#open-menu').addEventListener('click', () => menu.open())

const memorySheet = sheet('memory')
const memory = setupMemory($('#memory-body'), { toast, vibrate })
$('#open-memory').addEventListener('click', () => {
  menu.close()
  memorySheet.open()
  memory.show()
})
$('#memory-add').addEventListener('click', () => memory.add())

// ---- 设置 ----

const form = $<HTMLFormElement>('#settings-form')
const field = (name: string) => form.elements.namedItem(name) as HTMLInputElement

const PERMISSIONS: [key: string, label: string, hint: string, target: string][] = [
  ['accessibility', '无障碍服务', '让她看屏幕、操作手机', 'accessibility'],
  ['microphone', '麦克风', '语音对话', 'permissions'],
  ['camera', '相机', '拍照与录像', 'permissions'],
  ['notificationListener', '通知读取', '通知来时告诉你', 'notification'],
  ['batteryExempt', '不限制后台', '防止被系统清理', 'battery'],
  ['exactAlarm', '准时提醒', '提醒准点触发', 'appinfo'],
]

async function renderPermissions() {
  const status = await request('host.status').catch(() => null)
  const group = $('#permissions')
  group.textContent = ''
  for (const [key, label, hint, target] of PERMISSIONS) {
    const row = document.createElement('button')
    row.type = 'button'
    row.className = 'perm'
    const ok = Boolean(status?.[key])
    row.innerHTML = `<span></span><b class="pill ${ok ? 'on' : ''}">${ok ? '已开启' : '去开启'}</b><svg viewBox="0 0 24 24"><path d="M9 5l7 7-7 7"/></svg>`
    const name = row.querySelector('span')!
    name.textContent = label
    name.append(Object.assign(document.createElement('small'), { textContent: hint }))
    row.addEventListener('click', () => request('host.open', { target }).catch(() => {}))
    group.append(row)
  }
}

/** 非推理模型只能关闭思考，选了也不生效，就不让选。 */
function syncThinking() {
  field('thinkingLevel').disabled = !field('reasoning').checked
}
field('reasoning').addEventListener('change', syncThinking)

async function loadSettings() {
  const config = await request('config.get').catch(() => null)
  if (config) {
    field('api').value = config.model.api
    field('baseUrl').value = config.model.baseUrl
    field('modelId').value = config.model.modelId
    field('vision').checked = config.model.vision
    field('reasoning').checked = config.model.reasoning
    field('thinkingLevel').value = config.thinkingLevel
    syncThinking()
    field('fishVoiceId').value = config.voice.fishVoiceId
    field('speech').checked = config.voice.speech
    field('continuous').checked = config.voice.continuous
    field('notifyPackages').value = (config.notify.packages || []).join('\n')
    field('modelApiKey').value = ''
    field('fishApiKey').value = ''
    field('modelApiKey').placeholder = config.secrets.modelApiKey ? '已配置，留空不改' : '未配置'
    field('fishApiKey').placeholder = config.secrets.fishApiKey ? '已配置，留空不改' : '未配置'
  }
  await renderPermissions()
}

const settingsPage = $('#settings')
const closeSettings = () => { if (settingsPage.hidden) return false; settingsPage.hidden = true; return true }
function openSettings() {
  menu.close()
  settingsPage.hidden = false
  overlays.push(closeSettings)
  loadSettings()
}
$('#open-settings').addEventListener('click', openSettings)
$('#close-settings').addEventListener('click', closeSettings)
// 从系统设置页回来时刷新权限状态
document.addEventListener('visibilitychange', () => { if (!document.hidden && !settingsPage.hidden) renderPermissions() })

$('#save').addEventListener('click', async () => {
  const data = new FormData(form)
  const text = (name: string) => String(data.get(name) ?? '').trim()
  const secrets: Record<string, string> = {}
  if (text('modelApiKey')) secrets.modelApiKey = text('modelApiKey')
  if (text('fishApiKey')) secrets.fishApiKey = text('fishApiKey')
  try {
    await request('config.set', {
      values: {
        model: { api: text('api'), baseUrl: text('baseUrl'), modelId: text('modelId'), vision: data.has('vision'), reasoning: data.has('reasoning') },
        thinkingLevel: field('thinkingLevel').value,
        voice: { fishVoiceId: text('fishVoiceId'), speech: data.has('speech'), continuous: data.has('continuous') },
        notify: { packages: text('notifyPackages').split(/\s+/).filter(Boolean) },
      },
      secrets,
    })
    vibrate(10)
    toast('已保存')
    await loadSettings()
  } catch (error: any) {
    toast(`保存失败：${error.message}`)
  }
})

// 安卓返回键：先关最上层的浮层（含视频模式），没有可关的再交给系统
;(window as any).amadeus.back = (): boolean => {
  while (overlays.length) if (overlays.pop()!()) return true
  return false
}

// ---- 启动 ----

/**
 * 回复原文是 <zh>字幕</zh><jp>口播</jp>，只显示字幕。流式到达时最后一个标签可能还没闭合：
 * 未闭合的 <zh> 照样显示已到的部分，未闭合的 <jp> 整段跳过。
 */
function displayText(raw: string): string {
  const withoutSpeech = raw.replace(/<jp>[\s\S]*?(<\/jp>|$)/g, '')
  const zh = [...withoutSpeech.matchAll(/<zh>([\s\S]*?)(<\/zh>|$)/g)].map((m) => m[1]).join('')
  if (zh) return zh
  // 没按格式写的回复（偶尔会有）：去掉标签直接显示
  return withoutSpeech.replace(/<\/?[a-z]+>/g, '').replace(/\[EMO[^\]]*\]/g, '').trim()
}

syncComposer()
if (sessionStorage.getItem('resumeCall')) {
  sessionStorage.removeItem('resumeCall')
  startCall()
}
startSync(render)
