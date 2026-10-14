import assert from 'node:assert/strict'
import { test } from 'node:test'
import { encodeFrame, FrameDecoder, MAX_HEAD, ProtocolError } from '../src/frame.js'

const collect = () => {
  const frames = []
  return { frames, decoder: new FrameDecoder((head, body) => frames.push({ head, body })) }
}

test('round-trips head and binary body', () => {
  const { frames, decoder } = collect()
  const body = Uint8Array.from([0, 1, 2, 255])
  decoder.push(encodeFrame({ kind: 'evt', method: 'voice.event' }, body))
  assert.equal(frames.length, 1)
  assert.deepEqual(frames[0].head, { kind: 'evt', method: 'voice.event' })
  assert.deepEqual([...frames[0].body], [0, 1, 2, 255])
})

test('reassembles frames split at every byte boundary', () => {
  const bytes = Buffer.concat([
    encodeFrame({ kind: 'req', id: '1', method: 'ping' }),
    encodeFrame({ kind: 'res', id: '1', status: 'done' }, Buffer.from('图片')),
  ])
  for (let cut = 1; cut < bytes.length; cut++) {
    const { frames, decoder } = collect()
    decoder.push(bytes.subarray(0, cut))
    decoder.push(bytes.subarray(cut))
    assert.equal(frames.length, 2, `cut at ${cut}`)
    assert.equal(frames[1].body.toString('utf8'), '图片')
  }
})

test('decodes several frames delivered in one chunk', () => {
  const { frames, decoder } = collect()
  decoder.push(Buffer.concat([1, 2, 3].map((n) => encodeFrame({ kind: 'evt', n }))))
  assert.deepEqual(frames.map((f) => f.head.n), [1, 2, 3])
})

test('rejects oversized head, bad JSON and unknown kind', () => {
  const oversized = Buffer.alloc(4); oversized.writeUInt32BE(MAX_HEAD + 1)
  assert.throws(() => collect().decoder.push(oversized), ProtocolError)

  const json = Buffer.from('{oops')
  const bad = Buffer.alloc(8 + json.length); bad.writeUInt32BE(json.length, 0); json.copy(bad, 4); bad.writeUInt32BE(0, 4 + json.length)
  assert.throws(() => collect().decoder.push(bad), ProtocolError)

  assert.throws(() => collect().decoder.push(encodeFrame({ kind: 'other' })), ProtocolError)
})
