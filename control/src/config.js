import { chmodSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import path from 'node:path'

export const MODEL_APIS = ['openai-responses', 'anthropic-messages', 'openai-completions']
export const THINKING_LEVELS = ['off', 'minimal', 'low', 'medium', 'high']

const DEFAULTS = {
  model: { api: 'openai-responses', baseUrl: '', modelId: '', reasoning: true, vision: true, contextWindow: 200_000, maxTokens: 16_000 },
  thinkingLevel: 'medium',
  voice: { fishModel: 's2.1-pro-free', fishVoiceId: '', speech: true, continuous: false, locale: 'zh-CN' },
  notify: { packages: [] },
}
// 密钥只能写入、不能经协议读回；读取时只报告是否已配置
const SECRET_KEYS = ['modelApiKey', 'fishApiKey']

const readJson = (file) => {
  try { return JSON.parse(readFileSync(file, 'utf8')) } catch { return {} }
}

function writeAtomic(file, value, mode) {
  const temporary = `${file}.${process.pid}.tmp`
  writeFileSync(temporary, JSON.stringify(value, null, 2), { mode })
  chmodSync(temporary, mode)
  renameSync(temporary, file)
}

const merge = (base, change) => {
  const out = { ...base }
  for (const [key, value] of Object.entries(change || {})) {
    out[key] = value && typeof value === 'object' && !Array.isArray(value) && base[key] && typeof base[key] === 'object' ? merge(base[key], value) : value
  }
  return out
}

export class Config {
  #dir
  #listeners = new Set()

  constructor(dir) {
    this.#dir = dir
    mkdirSync(dir, { recursive: true })
  }

  get #configFile() { return path.join(this.#dir, 'config.json') }
  get #secretsFile() { return path.join(this.#dir, 'secrets.json') }

  values() { return merge(DEFAULTS, readJson(this.#configFile)) }

  secret(name) {
    const value = readJson(this.#secretsFile)[name]
    return typeof value === 'string' ? value : ''
  }

  /** 给界面看的视图：密钥只给出是否已配置。 */
  publicView() {
    const secrets = readJson(this.#secretsFile)
    return { ...this.values(), secrets: Object.fromEntries(SECRET_KEYS.map((key) => [key, typeof secrets[key] === 'string' && secrets[key] !== ''])) }
  }

  update({ values, secrets } = {}) {
    if (values) {
      const next = merge(this.values(), values)
      if (!MODEL_APIS.includes(next.model.api)) throw Object.assign(new Error(`unsupported model api: ${next.model.api}`), { code: 'bad_args' })
      if (!THINKING_LEVELS.includes(next.thinkingLevel)) throw Object.assign(new Error(`unsupported thinking level: ${next.thinkingLevel}`), { code: 'bad_args' })
      writeAtomic(this.#configFile, next, 0o600)
    }
    if (secrets) {
      const current = readJson(this.#secretsFile)
      for (const [key, value] of Object.entries(secrets)) {
        if (!SECRET_KEYS.includes(key)) throw Object.assign(new Error(`unknown secret: ${key}`), { code: 'bad_args' })
        if (value === null || value === '') delete current[key]
        else if (typeof value === 'string') current[key] = value.trim()
      }
      writeAtomic(this.#secretsFile, current, 0o600)
    }
    for (const listener of this.#listeners) listener()
    return this.publicView()
  }

  onChange(listener) {
    this.#listeners.add(listener)
    return () => this.#listeners.delete(listener)
  }
}
