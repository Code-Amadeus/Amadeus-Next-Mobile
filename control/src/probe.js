// 不经过 App 直接验证控制面：拉起 main.js，按帧协议发请求并打印应答
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { encodeFrame, FrameDecoder } from './frame.js'

const main = fileURLToPath(new URL('./main.js', import.meta.url))
const child = spawn(process.execPath, [main], { stdio: ['pipe', 'pipe', 'inherit'] })
const pending = new Map()
let nextId = 1

const request = (method, params) => new Promise((resolve) => {
  const id = String(nextId++)
  pending.set(id, resolve)
  child.stdin.write(encodeFrame({ kind: 'req', id, method, params }))
})

const ready = new Promise((resolve) => {
  const decoder = new FrameDecoder((head) => {
    if (head.kind === 'evt' && head.method === 'ready') resolve(head.params)
    else if (head.kind === 'res') { pending.get(head.id)?.(head); pending.delete(head.id) }
  })
  child.stdout.on('data', (chunk) => decoder.push(chunk))
})

const timer = setTimeout(() => { console.error('probe timed out'); child.kill(); process.exit(2) }, 60_000)
console.log('ready', await ready)
console.log('ping', await request('ping'))
console.log('status', await request('smoke.status'))
console.log('unknown', await request('nope'))
child.stdin.end()
child.on('exit', (code) => { clearTimeout(timer); console.log('exit', code); process.exit(code ?? 1) })
