// 与 Kotlin 宿主 WebBridge 的约定：请求走 AmadeusHost.request，应答与事件由宿主调用 window.amadeus.*
type Json = Record<string, any>
type Listener = (params: Json) => void

declare global {
  interface Window {
    AmadeusHost?: { request(id: string, method: string, params: string): void }
    amadeus: { reply(id: string, json: string): void; event(method: string, json: string): void }
  }
}

const pending = new Map<string, { resolve: (value: Json) => void; reject: (error: Error) => void }>()
const listeners = new Map<string, Set<Listener>>()
let nextId = 1

window.amadeus = {
  reply(id, json) {
    const entry = pending.get(id)
    if (!entry) return
    pending.delete(id)
    const value = JSON.parse(json)
    if (value.status === 'error') entry.reject(Object.assign(new Error(value.message || value.code || '请求失败'), { code: value.code }))
    else entry.resolve(value)
  },
  event(method, json) {
    const params = JSON.parse(json)
    for (const listener of listeners.get(method) ?? []) {
      try { listener(params) } catch (error) { console.error(method, error) }
    }
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
