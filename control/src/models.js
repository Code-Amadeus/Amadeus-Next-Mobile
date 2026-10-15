import { createModels, createProvider, envApiKeyAuth, getSupportedThinkingLevels } from '@earendil-works/pi-ai'
import { anthropicMessagesApi } from '@earendil-works/pi-ai/api/anthropic-messages.lazy'
import { openAICompletionsApi } from '@earendil-works/pi-ai/api/openai-completions.lazy'
import { openAIResponsesApi } from '@earendil-works/pi-ai/api/openai-responses.lazy'

export const PROVIDER_ID = 'amadeus'
const DEFAULT_THINKING_LEVEL = 'medium'
const KEY_ENV = 'AMADEUS_MODEL_API_KEY'

const APIS = {
  'openai-responses': openAIResponsesApi,
  'anthropic-messages': anthropicMessagesApi,
  'openai-completions': openAICompletionsApi,
}

/** 按配置构造唯一的自定义提供方：主人填协议类型、Base URL、模型名与 Key。 */
export function customProvider(model) {
  const api = APIS[model.api]
  if (!api) throw new Error(`unsupported model api: ${model.api}`)
  return createProvider({
    id: PROVIDER_ID,
    name: 'Amadeus',
    baseUrl: model.baseUrl,
    auth: { apiKey: envApiKeyAuth('Model API key', [KEY_ENV]) },
    models: [{
      id: model.modelId,
      name: model.modelId,
      api: model.api,
      provider: PROVIDER_ID,
      baseUrl: model.baseUrl,
      input: model.vision ? ['text', 'image'] : ['text'],
      cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
      reasoning: Boolean(model.reasoning),
      contextWindow: Number(model.contextWindow) || 200_000,
      maxTokens: Number(model.maxTokens) || 16_000,
    }],
    api: api(),
  })
}

export const isConfigured = (model, apiKey) => Boolean(model.baseUrl && model.modelId && apiKey)

/** 模型注册表与配置联动：配置变化时替换提供方并刷新密钥环境变量。 */
export class ModelHub {
  constructor(config, { models = createModels(), provider } = {}) {
    this.config = config
    this.models = models
    this.fixed = provider
    this.apply()
  }

  apply() {
    if (this.fixed) {
      this.models.setProvider(this.fixed)
      return
    }
    const { model } = this.config.values()
    const key = this.config.secret('modelApiKey')
    if (key) process.env[KEY_ENV] = key
    else delete process.env[KEY_ENV]
    if (model.baseUrl && model.modelId) this.models.setProvider(customProvider(model))
  }

  /** 当前应使用的模型引用；未配置时返回 undefined。 */
  ref() {
    if (this.fixed) {
      const first = this.fixed.getModels()[0]
      return first ? { provider: this.fixed.id, modelId: first.id } : undefined
    }
    const { model } = this.config.values()
    return isConfigured(model, this.config.secret('modelApiKey')) ? { provider: PROVIDER_ID, modelId: model.modelId } : undefined
  }

  /** 配置的思考等级按当前模型能力收敛：非推理模型只有 off，不支持的档位退回默认档。 */
  thinkingLevel() {
    const ref = this.ref()
    const model = ref ? this.models.getModel(ref.provider, ref.modelId) : undefined
    const levels = model ? getSupportedThinkingLevels(model) : ['off']
    const wanted = this.config.values().thinkingLevel
    if (levels.includes(wanted)) return wanted
    return levels.includes(DEFAULT_THINKING_LEVEL) ? DEFAULT_THINKING_LEVEL : levels[0] ?? 'off'
  }
}
