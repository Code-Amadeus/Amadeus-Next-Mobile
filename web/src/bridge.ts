// 与 Kotlin 宿主 WebBridge 的约定：请求走 AmadeusHost.request，应答与事件由宿主调用 window.amadeus.*
type Json = Record<string, any>
type Listener = (params: Json) => void

declare global {
  interface Window {
    AmadeusHost?: { request(id: string, method: string, params: string): void }
    amadeus: { reply(id: string, json: string): void; event(method: string, json: string, seq?: number): void; back?: () => boolean }
  }
}

const pending = new Map<string, { resolve: (value: Json) => void; reject: (error: Error) => void }>()
const listeners = new Map<string, Set<Listener>>()
let nextId = 1

function deliver(method: string, params: Json) {
  for (const listener of listeners.get(method) ?? []) {
    try { listener(params) } catch (error) { console.error(method, error) }
  }
}

// ---- 带序号事件的可靠投递 ----
// 宿主把重要事件编号存进日志（UiFeed）。这里记住处理到哪一条：重复的丢弃，断档就按序号补拉；
// 补不上（日志已覆盖、宿主重启）就做全量同步，同步期间到达的事件先排队，同步完再按序号处理。

let lastSeq = 0
let fullSync: () => Promise<void> = async () => {}
let syncing: Promise<void> | null = null
let queued: { method: string; params: Json; seq: number }[] = []

function accept(method: string, params: Json, seq: number) {
  if (seq <= lastSeq) return
  lastSeq = seq
  deliver(method, params)
}

/** 从 lastSeq 之后补拉；补不上就全量同步。同步期间到达的事件排队，结束后按序号接上，仍有断档就再补一轮。 */
export function catchUp(): Promise<void> {
  syncing ??= (async () => {
    try {
      for (;;) {
        const replay = await request('ui.replay', { after: lastSeq })
        if (replay.complete) {
          for (const event of replay.events) accept(event.method, event.params, event.seq)
        } else {
          await fullSync()
          lastSeq = Math.max(lastSeq, replay.latest)
        }
        queued.sort((a, b) => a.seq - b.seq)
        while (queued.length && queued[0].seq <= lastSeq + 1) {
          const event = queued.shift()!
          accept(event.method, event.params, event.seq)
        }
        if (!queued.length) return
      }
    } finally {
      syncing = null
    }
  })()
  return syncing
}

/**
 * 页面加载时调用：记下此刻的序号，全量渲染，再从这个序号之后接收增量。
 * 渲染期间完成的消息既在记录里又在排队的事件里；回合结束事件会触发再渲染一次，不会留下重复。
 */
export async function startSync(render: () => Promise<void>) {
  fullSync = render
  syncing = (async () => {
    try {
      const replay = await request('ui.replay', { after: 0 }).catch(() => ({ latest: 0 }))
      await render()
      lastSeq = replay.latest
    } finally {
      syncing = null
    }
  })()
  await syncing
  if (queued.length) await catchUp()
}

window.amadeus = {
  reply(id, json) {
    const entry = pending.get(id)
    if (!entry) return
    pending.delete(id)
    const value = JSON.parse(json)
    if (value.status === 'error') entry.reject(Object.assign(new Error(value.message || value.code || '请求失败'), { code: value.code }))
    else entry.resolve(value)
  },
  event(method, json, seq = 0) {
    const params = JSON.parse(json)
    // 瞬时事件（口型、字幕、识别中间结果）不编号，直接处理
    if (!seq) { deliver(method, params); return }
    if (syncing) { queued.push({ method, params, seq }); return }
    if (seq <= lastSeq) return
    if (seq === lastSeq + 1) { accept(method, params, seq); return }
    queued.push({ method, params, seq })
    catchUp()
  },
}

export function request(method: string, params: Json = {}): Promise<Json> {
  if (!window.AmadeusHost) return Promise.reject(new Error('宿主不可用'))
  const id = String(nextId++)
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject })
    window.AmadeusHost!.request(id, method, JSON.stringify(params))
  })
}

export function on(method: string, listener: Listener): () => void {
  const set = listeners.get(method) ?? new Set()
  set.add(listener)
  listeners.set(method, set)
  return () => set.delete(listener)
}
