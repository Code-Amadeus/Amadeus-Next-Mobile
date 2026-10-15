import { on, request } from './bridge.ts'
import { closeSwiped, swipeRow } from './swipe.ts'

/**
 * 长期记忆管理：开关、提示词占用、待复核、待确认提议、核心记忆的增删改。
 * 与桌面端设置页「长期记忆」对齐（amadeus-next MemorySettings.tsx）；召回模式依赖 Jev，手机上暂不提供。
 */

const TYPES = ['profile', 'preference', 'relationship', 'feedback', 'ongoing'] as const
type MemoryType = typeof TYPES[number]
const TYPE_NAMES: Record<MemoryType, string> = { profile: '个人信息', preference: '偏好', relationship: '关系', feedback: '反馈', ongoing: '进行中的事' }
const SOURCE_NAMES: Record<string, string> = { manual: '你添加的', explicit: '你要求记住的', extracted: '从对话中推断的' }
const CONFIDENCE_NAMES: Record<string, string> = { high: '高', medium: '中', low: '低' }
const BODY_LIMIT = 120
const REVIEW_EXTENSION_DAYS = 30

type Item = {
  id: string
  type: MemoryType
  status: 'reviewed' | 'unreviewed' | 'archived'
  body: string
  confidence: string
  source: string
  sourceQuote: string
  updatedAt: string
  reviewAfter: string
  expired: boolean
  injected: boolean
  proposal: { kind: 'replace' | 'remove'; targetBody: string } | null
}

type Deps = { toast: (text: string) => void; vibrate: (ms: number) => void }

const codePoints = (value: string) => [...value.replace(/\s+/g, ' ').trim()].length

/** 本地日期，不是 UTC：reviewAfter 按主人所在时区的"今天"判断到期。 */
function dateAfter(days: number): string {
  const date = new Date(Date.now() + days * 86_400_000)
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

function normalize(raw: any): Item | null {
  if (!raw?.id) return null
  const proposal = raw.proposal ?? {}
  return {
    id: String(raw.id),
    type: TYPES.find((type) => type === raw.type) ?? 'preference',
    status: raw.status === 'unreviewed' || raw.status === 'archived' ? raw.status : 'reviewed',
    body: String(raw.body ?? ''),
    confidence: String(raw.confidence ?? ''),
    source: String(raw.source ?? ''),
    sourceQuote: String(raw.sourceQuote ?? ''),
    updatedAt: String(raw.updatedAt ?? ''),
    reviewAfter: String(raw.reviewAfter ?? ''),
    expired: raw.expired === true,
    // 缺省按"已注入"处理：旧版本的返回里没有这个字段时，不该给每条记忆都贴上"未使用"
    injected: raw.injected !== false,
    proposal: proposal.kind === 'replace' || proposal.kind === 'remove' ? { kind: proposal.kind, targetBody: String(proposal.targetBody ?? '') } : null,
  }
}

function el<K extends keyof HTMLElementTagNameMap>(tag: K, className = '', text = ''): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag)
  if (className) node.className = className
  if (text) node.textContent = text
  return node
}

function badge(text: string, kind = '') {
  const node = el('span', 'memory-badge', text)
  if (kind) node.dataset.kind = kind
  return node
}

function button(text: string, onClick: () => void, variant = '') {
  const node = el('button', 'memory-button', text)
  node.type = 'button'
  if (variant) node.dataset.variant = variant
  node.addEventListener('click', (event) => { event.stopPropagation(); onClick() })
  return node
}

export function setupMemory(body: HTMLElement, { toast, vibrate }: Deps) {
  let items: Item[] = []
  let enabled = true
  let stats = { snapshotBytes: 0, snapshotLimit: 3072, truncated: false }
  let editing: string | null = null
  let adding: MemoryType | null = null
  let loaded = false
  const sheet = body.closest('.sheet') as HTMLElement

  /** memory.* 的业务失败是 { ok: false, error }，不抛异常；两种失败都提示出来。 */
  async function call(method: string, params: Record<string, unknown> = {}): Promise<any | null> {
    try {
      const result: any = await request(method, params)
      if (result?.ok === false) {
        toast(`记忆更新失败：${result.error || result.code || method}`)
        return null
      }
      return result
    } catch (error: any) {
      toast(`记忆更新失败：${error.message}`)
      return null
    }
  }

  async function refresh() {
    try {
      const [listed, status]: any[] = await Promise.all([request('memory.list'), request('memory.status').catch(() => ({ ok: false }))])
      if (listed?.ok === false) throw new Error(listed.error || 'memory.list rejected')
      items = (Array.isArray(listed.items) ? listed.items : []).flatMap((raw: any) => normalize(raw) ?? [])
      const raw = listed.stats ?? {}
      stats = {
        snapshotBytes: Number(raw.snapshotBytes) || 0,
        snapshotLimit: Number(raw.snapshotLimit) || 3072,
        truncated: raw.truncated === true,
      }
      if (status?.ok !== false && typeof status.enabled === 'boolean') enabled = status.enabled
      loaded = true
    } catch {
      if (!loaded) {
        body.innerHTML = '<p class="hint">记忆服务暂不可用。</p>'
        return
      }
      toast('记忆刷新失败')
    }
    render()
  }

  /** 改动成功后收起编辑并重新拉取。 */
  async function mutate(method: string, params: Record<string, unknown>) {
    const result = await call(method, params)
    if (!result) return null
    editing = null
    await refresh()
    return result
  }

  function editor(initial: string, date: string, withDate: boolean, saveLabel: string, onSave: (body: string, reviewAfter: string) => void, onCancel: () => void) {
    const form = el('form', 'memory-editor')
    const text = el('textarea')
    text.rows = 3
    text.value = initial
    text.placeholder = '一句简短、自包含的话（不超过 120 字）'
    const foot = el('div', 'memory-meta')
    const counter = el('span', 'memory-counter')
    const dateInput = el('input')
    dateInput.type = 'date'
    dateInput.value = date
    const save = el('button', 'memory-button', saveLabel)
    save.type = 'submit'
    save.dataset.variant = 'primary'
    const update = () => {
      const length = codePoints(text.value)
      counter.textContent = `${length}/${BODY_LIMIT}`
      counter.dataset.over = String(length > BODY_LIMIT)
      save.disabled = length === 0 || length > BODY_LIMIT || (withDate && !dateInput.value)
    }
    text.addEventListener('input', update)
    dateInput.addEventListener('input', update)
    foot.append(counter)
    if (withDate) {
      const label = el('label', 'memory-date', '复核日期')
      label.append(dateInput)
      foot.append(label)
    }
    foot.append(el('span', 'memory-spacer'), button('取消', onCancel), save)
    form.append(text, foot)
    form.addEventListener('submit', (event) => {
      event.preventDefault()
      if (!save.disabled) onSave(text.value.trim(), dateInput.value)
    })
    update()
    requestAnimationFrame(() => text.focus())
    return form
  }

  function coreItem(item: Item): HTMLElement {
    const card = el('div', 'memory-item')
    if (editing === item.id) {
      card.append(editor(item.body, item.reviewAfter || dateAfter(REVIEW_EXTENSION_DAYS), item.type === 'ongoing', '保存',
        (text, reviewAfter) => { mutate('memory.write', { id: item.id, body: text, ...(item.type === 'ongoing' ? { reviewAfter } : {}) }) },
        () => { editing = null; render() }))
      return card
    }
    card.append(el('div', 'memory-body', item.body))
    const meta = el('div', 'memory-meta')
    if (!item.injected && !item.expired) meta.append(badge('未使用', 'muted'))
    if (item.type === 'ongoing' && item.reviewAfter) meta.append(badge(`复核于 ${item.reviewAfter}`, 'muted'))
    if (SOURCE_NAMES[item.source]) meta.append(el('span', 'memory-source', SOURCE_NAMES[item.source]))
    card.append(meta)
    return swipeRow(card, {
      onTap: () => { editing = item.id; adding = null; render() },
      onDelete: async () => {
        vibrate(15)
        if (!(await call('memory.delete', { id: item.id }))) return false
        items = items.filter((other) => other.id !== item.id)
        return true
      },
      onRemoved: () => render(),
    })
  }

  function dueItem(item: Item): HTMLElement {
    const card = el('div', 'memory-item')
    card.append(el('div', 'memory-body', item.body))
    const meta = el('div', 'memory-meta')
    meta.append(badge('待复核', 'warn'), el('span', 'memory-source', `复核于 ${item.reviewAfter}`))
    card.append(meta, el('p', 'memory-hint', '已过复核日期，不再被使用。这件事还成立吗？'))
    const actions = el('div', 'memory-meta')
    actions.append(el('span', 'memory-spacer'),
      button('已结束', () => { mutate('memory.delete', { id: item.id }) }),
      button('仍然成立（顺延 30 天）', () => { mutate('memory.write', { id: item.id, reviewAfter: dateAfter(REVIEW_EXTENSION_DAYS) }) }, 'primary'))
    card.append(actions)
    return card
  }

  function pendingItem(item: Item): HTMLElement {
    const card = el('div', 'memory-item')
    if (item.proposal) {
      const proposal = el('div', 'memory-proposal')
      proposal.dataset.kind = item.proposal.kind
      proposal.append(el('span', '', item.proposal.kind === 'replace' ? '将替换：' : '将移除：'), el('span', 'memory-proposal-target', item.proposal.targetBody))
      card.append(proposal)
    }
    if (editing === item.id) {
      card.append(editor(item.body, item.reviewAfter || dateAfter(REVIEW_EXTENSION_DAYS), item.type === 'ongoing', '接受',
        (text, reviewAfter) => { mutate('memory.accept', { id: item.id, edit: { body: text, ...(item.type === 'ongoing' ? { reviewAfter } : {}) } }) },
        () => { editing = null; render() }))
      return card
    }
    if (!(item.proposal?.kind === 'remove' && !item.body)) card.append(el('div', 'memory-body', item.body))
    if (item.sourceQuote) card.append(el('div', 'memory-quote', `你说过：“${item.sourceQuote}”`))
    const meta = el('div', 'memory-meta')
    meta.append(badge(`可信度：${CONFIDENCE_NAMES[item.confidence] ?? item.confidence}`, item.confidence === 'high' ? 'ok' : 'muted'),
      el('span', 'memory-source', TYPE_NAMES[item.type]))
    card.append(meta)
    const actions = el('div', 'memory-meta')
    actions.append(el('span', 'memory-spacer'))
    if (item.proposal?.kind !== 'remove') actions.append(button('编辑', () => { editing = item.id; adding = null; render() }))
    actions.append(button('拒绝', () => { mutate('memory.reject', { id: item.id }) }),
      button('接受', () => { mutate('memory.accept', { id: item.id }) }, 'primary'))
    card.append(actions)
    return card
  }

  function addForm(type: MemoryType): HTMLElement {
    const card = el('div', 'memory-item')
    const select = el('select', 'memory-select')
    for (const option of TYPES) {
      const node = el('option', '', TYPE_NAMES[option])
      node.value = option
      node.selected = option === type
      select.append(node)
    }
    select.addEventListener('change', () => { adding = select.value as MemoryType; render() })
    card.append(select, editor('', dateAfter(REVIEW_EXTENSION_DAYS), type === 'ongoing', '添加',
      async (text, reviewAfter) => {
        if (!(await call('memory.write', { type, body: text, ...(type === 'ongoing' ? { reviewAfter } : {}) }))) return
        adding = null
        await refresh()
      },
      () => { adding = null; render() }))
    return card
  }

  function section(title: string, ...children: HTMLElement[]) {
    const node = el('section', 'memory-section')
    node.append(el('h3', '', title), ...children)
    return node
  }

  function render() {
    closeSwiped()
    body.textContent = ''
    const byUpdated = (a: Item, b: Item) => b.updatedAt.localeCompare(a.updatedAt)
    const reviewed = items.filter((item) => item.status === 'reviewed').sort(byUpdated)
    const due = reviewed.filter((item) => item.type === 'ongoing' && item.expired)
    const pending = items.filter((item) => item.status === 'unreviewed').sort(byUpdated)
    const archived = items.filter((item) => item.status === 'archived').sort(byUpdated)

    const head = el('div', 'group')
    const toggle = el('label', 'switch')
    const input = el('input')
    input.type = 'checkbox'
    input.checked = enabled
    input.addEventListener('change', async () => {
      const next = input.checked
      enabled = next
      if (!(await call('memory.configure', { enabled: next }))) { enabled = !next; input.checked = !next }
    })
    const label = el('span', '', '跨会话记住')
    label.append(el('small', '', '关闭后不再写入，也不把记忆带进新对话'))
    toggle.append(label, input, el('i'))
    const meter = el('div', 'field memory-meter-row')
    const percent = stats.snapshotLimit > 0 ? Math.min(100, Math.round((stats.snapshotBytes / stats.snapshotLimit) * 100)) : 0
    const bar = el('div', 'memory-meter')
    bar.dataset.full = String(percent >= 100)
    const fill = el('i')
    fill.style.width = `${percent}%`
    bar.append(fill)
    const kb = (bytes: number) => (bytes / 1024).toFixed(1)
    meter.append(el('span', '', '提示词占用'), bar, el('small', 'memory-source', `${kb(stats.snapshotBytes)} / ${kb(stats.snapshotLimit)} KB`))
    head.append(toggle, meter)
    body.append(head)
    if (stats.truncated) body.append(el('p', 'hint', '部分记忆放不下，未被使用。'))

    if (adding) body.append(section('添加记忆', addForm(adding)))
    if (due.length) body.append(section('待复核', ...due.map(dueItem)))
    body.append(section('等待确认', ...(pending.length ? pending.map(pendingItem) : [el('p', 'hint', '目前没有待确认的提议。')])))

    const core = section(`核心记忆 · ${reviewed.length}`)
    const groups = TYPES.map((type) => ({ type, items: reviewed.filter((item) => item.type === type && !due.includes(item)) })).filter((group) => group.items.length)
    if (!groups.length && !due.length) core.append(el('p', 'hint', '还没有内容。可以添加希望 Amadeus 一直记住的事。'))
    for (const group of groups) core.append(el('h4', 'memory-group-title', TYPE_NAMES[group.type]), ...group.items.map(coreItem))
    if (groups.length) core.append(el('p', 'hint', '点按编辑，左滑删除。'))
    body.append(core)

    if (archived.length) {
      const details = el('details', 'memory-archived')
      details.append(el('summary', '', `已归档的提议（${archived.length}）`))
      for (const item of archived) {
        const card = el('div', 'memory-item')
        const meta = el('div', 'memory-meta')
        meta.append(el('span', 'memory-spacer'), button('删除', () => { mutate('memory.delete', { id: item.id }) }, 'danger'))
        card.append(el('div', 'memory-body', item.body), meta)
        details.append(card)
      }
      body.append(details)
    }

    if (reviewed.length + pending.length) {
      const clear = el('div', 'memory-toolbar')
      clear.append(button('清空全部记忆', async () => {
        if (!window.confirm('清空全部长期记忆？\n核心记忆和待确认提议会移入回收站，保留 7 天。')) return
        await mutate('memory.clear', {})
      }, 'danger'))
      body.append(clear)
    }
  }

  let timer = 0
  on('memory.changed', () => {
    if (sheet.hidden) return
    clearTimeout(timer)
    timer = window.setTimeout(() => {
      // 正在编辑时别把输入框刷掉
      if (editing === null && adding === null) refresh()
    }, 250)
  })

  return {
    async show() {
      editing = null
      adding = null
      if (!loaded) body.innerHTML = '<p class="hint">正在加载…</p>'
      await refresh()
    },
    add() {
      adding = 'preference'
      editing = null
      render()
      body.scrollTop = 0
    },
  }
}
