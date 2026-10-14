import { existsSync, mkdirSync, renameSync, rmSync } from 'node:fs'
import path from 'node:path'
import { BAKER_VERSION } from './bake.ts'
import { bakeToFiles } from './bakeNode.ts'

/**
 * 可选的形象：同一个源模型的 A/B 只是手臂选择器（arm_type）不同，切换不用重新烘焙；
 * C 系列没有手臂选择器。源模型随 payload 分发，第一次用到时在本机烘焙并缓存。
 */
export const LOOKS = [
  { id: 'white-b', model: 'kurisu_ab_white', arm: 1, label: '白衣 · 抱臂' },
  { id: 'white-a', model: 'kurisu_ab_white', arm: 0, label: '白衣 · 垂手' },
  { id: 'white-c', model: 'kurisu_c_white', arm: null, label: '白衣 · 扶领带' },
  { id: 'casual-b', model: 'kurisu_ab', arm: 1, label: '常服 · 抱臂' },
  { id: 'casual-a', model: 'kurisu_ab', arm: 0, label: '常服 · 垂手' },
  { id: 'casual-c', model: 'kurisu_c', arm: null, label: '常服 · 扶领带' },
]

export class EmoteModels {
  #pending = new Map()

  constructor({ sourceDir, cacheDir }) {
    this.sourceDir = sourceDir
    this.cacheDir = cacheDir
  }

  list() {
    return LOOKS.filter((look) => existsSync(path.join(this.sourceDir, look.model)))
  }

  /** 返回烘焙产物相对缓存目录的路径；同一模型并发请求只烘焙一次。 */
  prepare(model) {
    if (!LOOKS.some((look) => look.model === model)) throw Object.assign(new Error(`unknown emote model: ${model}`), { code: 'bad_args' })
    const relative = `${model}-${BAKER_VERSION}`
    const dir = path.join(this.cacheDir, relative)
    const result = { psb: `${relative}/${model}.psb`, meta: `${relative}/${model}.meta.json` }
    if (existsSync(path.join(dir, `${model}.meta.json`))) return Promise.resolve({ ...result, baked: false })
    let task = this.#pending.get(model)
    if (!task) {
      task = (async () => {
        // 先烘焙到临时目录再整体改名：烘焙中途被杀不会留下半个 PSB 被当成可用缓存
        const staging = `${dir}.tmp-${process.pid}`
        rmSync(staging, { recursive: true, force: true })
        mkdirSync(staging, { recursive: true })
        const started = Date.now()
        await bakeToFiles({ folder: path.join(this.sourceDir, model), model, psb: path.join(staging, `${model}.psb`), meta: path.join(staging, `${model}.meta.json`) })
        rmSync(dir, { recursive: true, force: true })
        renameSync(staging, dir)
        return { ...result, baked: true, ms: Date.now() - started }
      })().finally(() => this.#pending.delete(model))
      this.#pending.set(model, task)
    }
    return task
  }
}
