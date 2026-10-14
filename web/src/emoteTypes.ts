// 与 amadeus-next src/shared/emote/{bake,parts}.ts 的烘焙产物格式一致；只取前端需要的类型
export type PartOption = { lower: number; upper: number; writes: Record<string, number> }
export type PartSelector = { label: string; defaultValue: number; options: PartOption[] }
export type PartTable = PartSelector[]

export type EmoteMetadata = {
  variableMetaInfoList: { id: string; defaultValue: number }[]
  eyeControl: { enabled: boolean | number; label: string; varL: string; varR: string }[]
  customSelectorList: { enabled: boolean | number; label: string }[]
  [key: string]: unknown
}
export type Snaps = Record<string, [string, number][]>
export type EmoteMeta = { metadata: EmoteMetadata; snaps: Snaps; parts: PartTable }
