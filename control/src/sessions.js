import { readFileSync, renameSync, writeFileSync } from 'node:fs'
import path from 'node:path'

const TITLE_LENGTH = 24

/**
 * 会话索引：pi-durable 的会话记录不带标题与时间，这里补一份给界面列历史用。
 * 只是展示用的旁路数据，丢了不影响会话本身（重建时标题会变成"新对话"）。
 */
export class SessionIndex {
  #file

  constructor(dataDir) {
    this.#file = path.join(dataDir, 'sessions.json')
    try {
      const stored = JSON.parse(readFileSync(this.#file, 'utf8'))
      this.current = stored.current ?? null
      this.items = Array.isArray(stored.items) ? stored.items : []
      this.deleted = Array.isArray(stored.deleted) ? stored.deleted : []
    } catch {
      this.current = null
      this.items = []
      this.deleted = []
    }
  }

  #save() {
    const temporary = `${this.#file}.${process.pid}.tmp`
    writeFileSync(temporary, JSON.stringify({ current: this.current, items: this.items, deleted: this.deleted }))
    renameSync(temporary, this.#file)
  }

  find(id) { return this.items.find((item) => item.id === id) }

  add(id, title = '') {
    if (!this.find(id)) {
      const now = Date.now()
      this.items.push({ id, title, createdAt: now, updatedAt: now })
    }
    this.current = id
    this.#save()
  }

  /**
   * 从历史里删掉。pi-durable 没有删除会话的接口，记录仍留在 sessions.sqlite；
   * 记下 id，免得启动时把删掉的根会话当成"早于索引的会话"补回来。
   */
  remove(id) {
    this.items = this.items.filter((item) => item.id !== id)
    if (!this.deleted.includes(id)) this.deleted.push(id)
    if (this.current === id) this.current = null
    this.#save()
  }

  isDeleted(id) { return this.deleted.includes(id) }

  select(id) {
    this.current = id
    this.#save()
  }

  /** 有新输入时更新活跃时间；还没有标题就取这句话的开头。 */
  touch(id, text) {
    const item = this.find(id)
    if (!item) return
    item.updatedAt = Date.now()
    if (!item.title && text) item.title = text.replace(/\s+/g, ' ').trim().slice(0, TITLE_LENGTH)
    this.#save()
  }

  list() {
    return [...this.items]
      .sort((a, b) => b.updatedAt - a.updatedAt)
      .map((item) => ({ ...item, title: item.title || '新对话', current: item.id === this.current }))
  }
}
