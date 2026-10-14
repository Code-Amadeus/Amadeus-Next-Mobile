#!/usr/bin/env node
// 准备不进 git 的构建输入：第三方二进制按锁定的 sha256 下载，主人自己的 emote 素材从 amadeus-next 复制。
// 用法：node scripts/fetch-deps.mjs  （可用 AMADEUS_NEXT / AMADEUS_EMOTE_CACHE 覆盖素材来源）
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const cache = path.join(repo, '.cache', 'deps')
const assets = path.join(repo, 'build', 'assets')
const next = process.env.AMADEUS_NEXT || path.resolve(repo, '..', 'amadeus-next')
const emoteCache = process.env.AMADEUS_EMOTE_CACHE || path.join(os.homedir(), 'Library/Application Support/amadeus-next/emote-cache/kurisu_ab_white')

const DOWNLOADS = {
  sherpaAar: {
    url: 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar',
    sha256: '633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96',
  },
  asrModel: {
    url: 'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01.tar.bz2',
    sha256: 'b3b309f7ce4a737195fcc6963ea19b0653a7d3401580af5ae0d3e284cbb71f0b',
  },
}
// 与 amadeus-next src/main/emoteBake.ts 的 EMOTE_RUNTIME 一致，外加 pixi 7.4.3
const PINNED = {
  'FreeMoteDriver.js': '64a38cbc4722ee90d58a7d0d464fe121d1a6453acda21c3fca99712c220d0641',
  'emoteplayer.js': '807e5c6a4d58d99c1e876f4155dd73d096ef92a5650c1c2a314be665dfdb9eba',
  'pixi.min.js': '712a3e1943b062a907d86c5bb56b67c7b32a3f03b3654768b00b7016d30a24c6',
}

const sha256 = (file) => createHash('sha256').update(readFileSync(file)).digest('hex')

async function download({ url, sha256: expected }) {
  const file = path.join(cache, path.basename(url))
  if (existsSync(file) && sha256(file) === expected) return file
  mkdirSync(cache, { recursive: true })
  const response = await fetch(url)
  if (!response.ok) throw new Error(`GET ${url} → ${response.status}`)
  writeFileSync(file, Buffer.from(await response.arrayBuffer()))
  if (sha256(file) !== expected) throw new Error(`sha256 mismatch: ${url}`)
  return file
}

function copyPinned(source, target) {
  const name = path.basename(target)
  if (sha256(source) !== PINNED[name]) throw new Error(`${name} 与锁定的 sha256 不一致：${source}`)
  copyFileSync(source, target)
}

async function main() {
  const aar = await download(DOWNLOADS.sherpaAar)
  const libs = path.join(repo, 'android', 'app', 'libs')
  mkdirSync(libs, { recursive: true })
  copyFileSync(aar, path.join(libs, 'sherpa-onnx.aar'))

  const model = await download(DOWNLOADS.asrModel)
  const asr = path.join(assets, 'asr')
  rmSync(asr, { recursive: true, force: true })
  mkdirSync(asr, { recursive: true })
  const work = mkdtempSync(path.join(os.tmpdir(), 'asr-'))
  try {
    execFileSync('tar', ['-xjf', model, '-C', work])
    const dir = path.join(work, readdirSync(work)[0])
    for (const name of ['model.int8.onnx', 'tokens.txt']) copyFileSync(path.join(dir, name), path.join(asr, name))
  } finally {
    rmSync(work, { recursive: true, force: true })
  }

  const emote = path.join(assets, 'web', 'emote')
  rmSync(emote, { recursive: true, force: true })
  mkdirSync(emote, { recursive: true })
  copyPinned(path.join(next, 'resources/character/emote/FreeMoteDriver.js'), path.join(emote, 'FreeMoteDriver.js'))
  copyPinned(path.join(next, 'resources/character/emote/emoteplayer.js'), path.join(emote, 'emoteplayer.js'))
  copyPinned(path.join(next, 'resources/scene/stage/vendor/pixi.min.js'), path.join(emote, 'pixi.min.js'))
  // 烘焙产物取最新的一份；amadeus-next 首次打开场景时生成
  const baked = readdirSync(emoteCache)
    .map((name) => path.join(emoteCache, name))
    .filter((dir) => existsSync(path.join(dir, 'kurisu_ab_white.psb')) && existsSync(path.join(dir, 'kurisu_ab_white.meta.json')))
    .sort((a, b) => statSync(b).mtimeMs - statSync(a).mtimeMs)[0]
  if (!baked) throw new Error(`没有找到烘焙好的 emote 模型：${emoteCache}（先在 amadeus-next 里打开一次场景）`)
  for (const name of ['kurisu_ab_white.psb', 'kurisu_ab_white.meta.json']) copyFileSync(path.join(baked, name), path.join(emote, name))

  console.log(`sherpa-onnx AAR → android/app/libs; ASR model → build/assets/asr; emote (${path.basename(baked).slice(0, 12)}) → build/assets/web/emote`)
}

main().catch((error) => {
  console.error(error.message || error)
  process.exit(1)
})
