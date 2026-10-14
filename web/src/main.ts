import { on, request } from './bridge.ts'
import { createEmoteRenderer, type EmoteCharacter } from './emote.ts'

const $ = <T extends HTMLElement = HTMLElement>(selector: string) => document.querySelector(selector) as T

const log = $('#log')
const chat = $('#chat')
const input = $<HTMLTextAreaElement>('#input')
const subtitle = $('#subtitle')
const stateChip = $('#state')
const hearing = $('#hearing')
const mic = $('#mic')
let character: EmoteCharacter | null = null
let renderer: ReturnType<typeof createEmoteRenderer> | null = null
let assistantBubble: HTMLElement | null = null
let busy = false

const VERB_NAMES: Record<string, string> = {
  observe: '看屏幕', screenshot: '截图', click: '点击', swipe: '滑动', set_value: '输入文字', enter: '确认输入', key: '按键',
  launch_app: '打开应用', list_apps: '查找应用', wait: '等待', camera_snap: '拍照', camera_clip: '录像',
  remind_set: '设提醒', remind_list: '看提醒', remind_cancel: '取消提醒', read: '读文件', write: '写文件', edit: '改文件',
}
const DECISIONS: Record<string, string> = { allowed: '放行', confirmed: '你确认过', denied: '你拒绝了', blocked: '被拦下' }

const vibrate = (ms: number) => { try { navigator.vibrate?.(ms) } catch { /* 部分 WebView 不支持 */ } }

let toastTimer = 0
function toast(text: string) {
  const el = $('#toast')
  el.textContent = text
  el.hidden = false
  clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => { el.hidden = true }, 2400)
}

function setState(text: string | null) {
  stateChip.hidden = !text
  if (text) stateChip.textContent = text
}

// 长按菜单只留给输入框
document.addEventListener('contextmenu', (event) => {
  if (!(event.target instanceof HTMLInputElement || event.target instanceof HTMLTextAreaElement)) event.preventDefault()
})

// ---- 对话浮层：收起时只露出最近几条，上滑展开 ----

function setChatExpanded(expanded: boolean) {
  chat.classList.toggle('expanded', expanded)
  chat.classList.toggle('collapsed', !expanded)
  log.scrollTop = log.scrollHeight
}

function swipeable(handle: HTMLElement, onUp: () => void, onDown: () => void, onTap?: () => void) {
  let startY = 0
  handle.addEventListener('pointerdown', (event) => { startY = event.clientY; handle.setPointerCapture(event.pointerId) })
  handle.addEventListener('pointerup', (event) => {
    const dy = event.clientY - startY
    if (dy < -24) onUp()
    else if (dy > 24) onDown()
    else onTap?.()
  })
}

swipeable($('#chat-grip'), () => setChatExpanded(true), () => setChatExpanded(false),
  () => setChatExpanded(!chat.classList.contains('expanded')))
log.addEventListener('click', () => { if (chat.classList.contains('collapsed')) setChatExpanded(true) })

function bubble(role: 'user' | 'assistant' | 'notice', text: string): HTMLElement {
  const item = document.createElement('div')
  item.className = `bubble ${role}`
  item.textContent = text
  log.append(item)
  while (log.childElementCount > 200) log.firstElementChild!.remove()
  log.scrollTop = log.scrollHeight
  return item
}

function setBusy(value: boolean) {
  busy = value
  $('#stop').hidden = !value
  $('#stop-placeholder').hidden = value
}

// ---- 输入：语音为主，键盘为辅 ----

// WebView 未开 DOM Storage 时 localStorage 为 null，偏好只是记不住，不能让脚本中断
const prefs = { get: (key: string) => { try { return localStorage.getItem(key) } catch { return null } }, set: (key: string, value: string) => { try { localStorage.setItem(key, value) } catch { /* 忽略 */ } } }

function setMode(mode: 'voice' | 'text') {
  $('#voice-mode').hidden = mode !== 'voice'
  $('#text-mode').hidden = mode !== 'text'
  prefs.set('inputMode', mode)
  if (mode === 'text') input.focus()
  else input.blur()
}
$('#to-keyboard').addEventListener('click', () => setMode('text'))
$('#to-voice').addEventListener('click', () => setMode('voice'))
setMode(prefs.get('inputMode') === 'text' ? 'text' : 'voice')

async function send() {
  const text = input.value.trim()
  if (!text) return
  input.value = ''
  input.style.height = ''
  bubble('user', text)
  try { await request('chat.submit', { text }) } catch (error: any) {
    if (error.code === 'not_configured') { toast('先在设置里填好模型'); openSettings() } else bubble('notice', `发送失败：${error.message}`)
  }
}
$('#send').addEventListener('click', send)
input.addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); send() }
})
input.addEventListener('input', () => {
  input.style.height = ''
  input.style.height = `${Math.min(120, input.scrollHeight)}px`
})
$('#stop').addEventListener('click', () => { vibrate(10); request('chat.abort').catch(() => {}) })

// 点一下开始听，停顿 1.5 秒自动发送，或再点一下立即发送。
// 不用"按住说话"：三星等系统的长按识屏会在 1.5 秒左右抢走长按手势，应用层拦不住
function setListening(value: boolean) {
  mic.classList.toggle('active', value)
  mic.querySelector('span')!.textContent = value ? '正在听…点一下发送' : '点一下说话'
}
mic.addEventListener('click', () => {
  vibrate(15)
  if (mic.classList.contains('active')) {
    setListening(false)
    request('voice.listen', { action: 'finish' }).catch(() => {})
    return
  }
  setListening(true)
  if (busy) request('chat.abort').catch(() => {})
  request('voice.listen', { action: 'start' }).catch((error) => { setListening(false); toast(`麦克风不可用：${error.message}`) })
})

on('voice.asr', ({ status, text, error }) => {
  if (status === 'listening') { setState('正在听'); hearing.hidden = false; hearing.textContent = '…' }
  else if (status === 'partial') { hearing.hidden = false; hearing.textContent = text }
  else if (status === 'final') { hearing.hidden = true; setListening(false); bubble('user', text) }
  else if (status === 'error') { hearing.hidden = true; setListening(false); setState(null); toast(error) }
  else if (status === 'idle') { hearing.hidden = true; setListening(false); if (!busy) setState(null) }
})

// ---- 回合 ----

on('turn.begin', () => {
  setBusy(true)
  assistantBubble = null
  subtitle.textContent = ''
  subtitle.hidden = true
  setState('思考中')
})
on('turn.tool', ({ phase, name, isError, summary }) => {
  if (phase === 'start') setState(`正在${VERB_NAMES[name] ?? name}`)
  else if (isError) bubble('notice', `${VERB_NAMES[name] ?? name}没成功：${String(summary || '').split('\n')[0]}`)
})
on('turn.notice', ({ text }) => toast(text))
on('turn.error', ({ message }) => bubble('notice', `出错了：${message}`))
on('turn.end', () => {
  setBusy(false)
  setState(null)
})

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
    const value = event.value
    const display = value.display ?? value.text
    if (display) {
      assistantBubble ??= bubble('assistant', '')
      assistantBubble.textContent += display
      log.scrollTop = log.scrollHeight
      subtitle.hidden = false
      subtitle.textContent = (subtitle.textContent + display).slice(-60)
      if (stateChip.textContent === '思考中') setState(null)
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
    subtitleTimer = window.setTimeout(() => { if (!busy) subtitle.hidden = true }, 2000)
  }
})

on('kernel.state', ({ state, detail }) => {
  const banner = $('#kernel')
  banner.hidden = state === 'READY'
  banner.textContent = `${state === 'STARTING' ? '正在醒来' : state === 'RESTARTING' ? '正在恢复' : '已停止'}：${detail}`
})

// ---- 动作记录（底部面板） ----

function verbRow(entry: any): HTMLElement {
  const row = document.createElement('div')
  row.className = `verb ${entry.decision}`
  const args = entry.args && Object.keys(entry.args).length ? JSON.stringify(entry.args) : ''
  row.innerHTML = '<span class="dot"></span><div class="what"><div></div><div></div></div><time></time>'
  const [title, detail] = row.querySelectorAll('.what div')
  title.textContent = `${VERB_NAMES[entry.verb] ?? entry.verb} · ${DECISIONS[entry.decision] ?? entry.decision}`
  detail.textContent = entry.code ? `${entry.code} ${args}` : args
  row.querySelector('time')!.textContent = new Date(entry.at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
  return row
}

on('verb.log', (entry) => {
  if (!$('#activity').hidden) $('#verbs').prepend(verbRow(entry))
})

/** 打开的浮层按栈管理，安卓返回键从最上层开始关。 */
const overlays: (() => boolean)[] = []

const activitySheet = $('#activity')
const closeActivity = () => { if (activitySheet.hidden) return false; activitySheet.hidden = true; return true }
activitySheet.querySelector('.scrim')!.addEventListener('click', closeActivity)
swipeable(activitySheet.querySelector('.grip') as HTMLElement, () => {}, closeActivity, closeActivity)

$('#open-activity').addEventListener('click', async () => {
  activitySheet.hidden = false
  overlays.push(closeActivity)
  const verbs = $('#verbs')
  try {
    const { items } = await request('audit.list', { limit: 100 })
    verbs.textContent = ''
    for (const entry of items) verbs.append(verbRow(entry))
    if (!items.length) verbs.innerHTML = '<p class="hint">她还没有动过你的手机。</p>'
  } catch { /* 服务未就绪时保持空 */ }
})

// ---- 设置（全屏页） ----

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
    const small = document.createElement('small')
    small.textContent = hint
    name.append(small)
    row.addEventListener('click', () => request('host.open', { target }).catch(() => {}))
    group.append(row)
  }
}

async function loadSettings() {
  const config = await request('config.get').catch(() => null)
  if (config) {
    field('api').value = config.model.api
    field('baseUrl').value = config.model.baseUrl
    field('modelId').value = config.model.modelId
    field('vision').checked = config.model.vision
    field('reasoning').checked = config.model.reasoning
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

// 安卓返回键：先关最上层的浮层，没有可关的再交给系统
;(window as any).amadeus.back = (): boolean => {
  while (overlays.length) if (overlays.pop()!()) return true
  if (chat.classList.contains('expanded')) { setChatExpanded(false); return true }
  return false
}

// ---- 启动 ----

/** 历史记录里是原始的 <zh>/<jp> 文本，只取字幕部分显示。 */
function displayText(raw: string): string {
  const zh = [...raw.matchAll(/<zh>([\s\S]*?)<\/zh>/g)].map((m) => m[1]).join('')
  return zh || raw.replace(/<[^>]+>/g, '')
}

async function boot() {
  // Pixi 与 E-mote 运行时由 index.html 异步加载，必须等它们就绪后再建渲染器
  try { renderer = createEmoteRenderer($('#stage'), { bottom: 0.78 }) } catch (error) {
    $('#stage-error').textContent = `立绘不可用：${(error as Error).message}`
  }
  if (renderer) {
    try {
      character = await renderer.load(
        { psbUrl: 'emote/kurisu_ab_white.psb', metaUrl: 'emote/kurisu_ab_white.meta.json' },
        (message) => { $('#stage-error').textContent = message },
      )
      setInterval(() => character?.dispatch({ type: 'tick', now: performance.now() }), 50)
    } catch (error) {
      $('#stage-error').textContent = `立绘加载失败：${(error as Error).message}`
    }
  }
  try {
    const { items } = await request('chat.history', { limit: 30 })
    for (const item of items) bubble(item.role, item.role === 'assistant' ? displayText(item.text) : item.text)
  } catch { /* 控制面尚未就绪，后续消息照常显示 */ }
}

if ((window as any).EmotePlayer) boot()
else window.addEventListener('amadeus:runtime-ready', () => boot(), { once: true })
