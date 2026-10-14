import assert from 'node:assert/strict'
import { existsSync, mkdtempSync, rmSync, statSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { test } from 'node:test'
import { EmoteModels } from '../src/emote/models.js'

// 源模型是主人自己的素材，不在仓库里；本机有 amadeus-next 时才跑真实烘焙
const SOURCES = process.env.AMADEUS_EMOTE_SOURCES || path.resolve(import.meta.dirname, '../../../amadeus-next/resources/character/emote/models')

test('rejects unknown models and lists only looks whose source exists', (t) => {
  const dir = mkdtempSync(path.join(os.tmpdir(), 'emote-'))
  t.after(() => rmSync(dir, { recursive: true, force: true }))
  const models = new EmoteModels({ sourceDir: path.join(dir, 'none'), cacheDir: dir })
  assert.deepEqual(models.list(), [])
  assert.throws(() => models.prepare('../etc'), /unknown emote model/)
})

test('bakes a model once and reuses the cache', { skip: !existsSync(path.join(SOURCES, 'kurisu_c')) }, async (t) => {
  const dir = mkdtempSync(path.join(os.tmpdir(), 'emote-'))
  t.after(() => rmSync(dir, { recursive: true, force: true }))
  const models = new EmoteModels({ sourceDir: SOURCES, cacheDir: dir })
  assert.equal(models.list().length, 6)
  const [first, again] = await Promise.all([models.prepare('kurisu_c'), models.prepare('kurisu_c')])
  assert.equal(first, again)
  assert.equal(first.baked, true)
  assert.ok(statSync(path.join(dir, first.psb)).size > 1_000_000)
  const cached = await models.prepare('kurisu_c')
  assert.equal(cached.baked, false)
  assert.equal(cached.psb, first.psb)
})
