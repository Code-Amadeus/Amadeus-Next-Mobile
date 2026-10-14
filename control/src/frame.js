// u32(BE) 头长度 | 头（UTF-8 JSON） | u32(BE) 体长度 | 体（二进制，可为 0 字节）
export const MAX_HEAD = 1 << 20
export const MAX_BODY = 16 << 20

export class ProtocolError extends Error {}

export function encodeFrame(head, body = new Uint8Array(0)) {
  const json = Buffer.from(JSON.stringify(head), 'utf8')
  if (json.length > MAX_HEAD) throw new ProtocolError(`frame head too large: ${json.length}`)
  if (body.length > MAX_BODY) throw new ProtocolError(`frame body too large: ${body.length}`)
  const out = Buffer.allocUnsafe(8 + json.length + body.length)
  out.writeUInt32BE(json.length, 0)
  json.copy(out, 4)
  out.writeUInt32BE(body.length, 4 + json.length)
  Buffer.from(body.buffer, body.byteOffset, body.length).copy(out, 8 + json.length)
  return out
}

/** 增量解析：喂入任意切分的字节流，按完整帧回调。 */
export class FrameDecoder {
  #buffer = Buffer.alloc(0)
  #onFrame

  constructor(onFrame) { this.#onFrame = onFrame }

  push(chunk) {
    this.#buffer = this.#buffer.length ? Buffer.concat([this.#buffer, chunk]) : chunk
    for (;;) {
      const buf = this.#buffer
      if (buf.length < 4) return
      const headLen = buf.readUInt32BE(0)
      if (headLen > MAX_HEAD) throw new ProtocolError(`frame head too large: ${headLen}`)
      if (buf.length < 8 + headLen) return
      const bodyLen = buf.readUInt32BE(4 + headLen)
      if (bodyLen > MAX_BODY) throw new ProtocolError(`frame body too large: ${bodyLen}`)
      const end = 8 + headLen + bodyLen
      if (buf.length < end) return
      let head
      try { head = JSON.parse(buf.subarray(4, 4 + headLen).toString('utf8')) } catch (error) {
        throw new ProtocolError(`frame head is not JSON: ${error.message}`)
      }
      if (!head || typeof head !== 'object' || !['req', 'res', 'evt'].includes(head.kind)) {
        throw new ProtocolError('frame head must be an object with kind req/res/evt')
      }
      // 复制出体，避免持有整块缓冲导致后续拼接时内存无法释放
      const body = Buffer.from(buf.subarray(8 + headLen, end))
      this.#buffer = buf.subarray(end)
      this.#onFrame(head, body)
    }
  }
}
