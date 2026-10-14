// 打包 WebView 前端到 build/assets/web（emote 运行时与模型由 scripts/fetch-deps.mjs 放在同目录的 emote/ 下）
import { copyFileSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { build } from 'esbuild'

const here = path.dirname(fileURLToPath(import.meta.url))
const out = path.resolve(here, '..', 'build', 'assets', 'web')
mkdirSync(out, { recursive: true })
await build({
  entryPoints: [path.join(here, 'src', 'main.ts')],
  bundle: true,
  format: 'iife',
  target: 'chrome110',
  outfile: path.join(out, 'app.js'),
  sourcemap: 'inline',
  logLevel: 'warning',
})
for (const name of ['index.html', 'style.css']) copyFileSync(path.join(here, name), path.join(out, name))
console.log(`web → ${path.relative(process.cwd(), out)}`)
