import { encodeFrame, FrameDecoder, ProtocolError } from './frame.js'

export class RpcError extends Error {
  constructor(code, message) {
    super(message)
    this.code = code
  }
}

/**
 * 帧协议的一端：处理对端请求、发出请求等应答、收发事件。
 * stdio（对宿主）与 UDS（对执行面）共用。
 */
export class Peer {
  #write
  #handlers = new Map()
  #listeners = new Map()
  #pending = new Map()
  #nextId = 1
  #onProtocolError

  constructor({ write, onProtocolError }) {
    this.#write = write
    this.#onProtocolError = onProtocolError
    this.decoder = new FrameDecoder((head, body) => this.#dispatch(head, body))
  }

  push(chunk) {
    try { this.decoder.push(chunk) } catch (error) {
      if (error instanceof ProtocolError) this.#onProtocolError(error)
      else throw error
    }
  }

  handle(method, handler) { this.#handlers.set(method, handler) }

  on(method, listener) {
    const set = this.#listeners.get(method) ?? new Set()
    set.add(listener)
    this.#listeners.set(method, set)
    return () => set.delete(listener)
  }

  emit(method, params = {}, body) {
    this.#write(encodeFrame({ kind: 'evt', method, params, ...(body?.mime ? { body: { mime: body.mime } } : {}) }, body?.bytes))
  }

  request(method, params = {}, { timeoutMs = 60_000 } = {}) {
    const id = String(this.#nextId++)
    return new Promise((resolve, reject) => {
      const timer = timeoutMs ? setTimeout(() => {
        this.#pending.delete(id)
        reject(new RpcError('timeout', `${method} timed out`))
      }, timeoutMs) : null
      this.#pending.set(id, { resolve, reject, timer })
      this.#write(encodeFrame({ kind: 'req', id, method, params }))
    })
  }

  /** 连接断开时让所有等待中的请求失败，避免调用方永远挂起。 */
  failAll(code, message) {
    for (const [id, pending] of this.#pending) {
      clearTimeout(pending.timer)
      pending.reject(new RpcError(code, message))
      this.#pending.delete(id)
    }
  }

  #dispatch(head, body) {
    if (head.kind === 'res') {
      const pending = this.#pending.get(head.id)
      if (!pending) return
      this.#pending.delete(head.id)
      clearTimeout(pending.timer)
      if (head.status === 'done') pending.resolve({ ...head, mime: head.body?.mime, body })
      else pending.reject(new RpcError(head.code || head.status || 'failed', head.reason || head.message || head.code || 'request failed'))
    } else if (head.kind === 'evt') {
      for (const listener of this.#listeners.get(head.method) ?? []) {
        try { listener(head.params || {}, body) } catch (error) { console.error(`listener ${head.method} failed`, error) }
      }
    } else if (head.kind === 'req') {
      const handler = this.#handlers.get(head.method)
      const reply = (frame, bytes) => this.#write(encodeFrame({ kind: 'res', id: head.id, ...frame }, bytes))
      if (!handler) {
        reply({ status: 'error', code: 'unknown_method', message: `unknown method: ${head.method}` })
        return
      }
      Promise.resolve()
        .then(() => handler(head.params || {}, body))
        .then(
          (result) => {
            const { body: out, ...rest } = result || {}
            reply({ status: 'done', ...rest, ...(out?.mime ? { body: { mime: out.mime } } : {}) }, out?.bytes)
          },
          (error) => reply({ status: 'error', code: error?.code || 'failed', message: String(error?.message || error) }),
        )
    }
  }
}
