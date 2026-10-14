#!/usr/bin/env node
// 组装 payload：Termux aarch64 的 Node 运行时 + 控制面，产出 payload.zip 与清单。
// 用法：node payload/assemble.mjs [--out payload/build]；APK 从 <out>/assets 与 <out>/jniLibs 打包
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { cpSync, existsSync, lstatSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, readlinkSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const repo = path.resolve(here, '..')
const outDir = path.resolve(process.argv.includes('--out') ? process.argv[process.argv.indexOf('--out') + 1] : path.join(here, 'build'))
const cacheDir = path.join(here, '.cache')
const MIRROR = 'https://packages.termux.dev/apt/termux-main'
const INDEX = `${MIRROR}/dists/stable/main/binary-aarch64/Packages`
const ROOTS = ['nodejs-lts', 'ca-certificates']
const TERMUX_PREFIX = 'data/data/com.termux/files/usr'

// 控制面只需要 node 本体；其余可执行文件与开发文件都不进 payload
const KEEP_BIN = new Set(['node'])
const PRUNE = ['include', 'share/doc', 'share/man', 'share/info', 'share/locale', 'share/licenses', 'lib/pkgconfig', 'lib/cmake', 'lib/node_modules', 'libexec', 'var', 'tmp']

const sha256 = (data) => createHash('sha256').update(data).digest('hex')

async function fetchCached(url, file, expected) {
  if (existsSync(file) && (!expected || sha256(readFileSync(file)) === expected)) return readFileSync(file)
  const response = await fetch(url)
  if (!response.ok) throw new Error(`GET ${url} → ${response.status}`)
  const data = Buffer.from(await response.arrayBuffer())
  if (expected && sha256(data) !== expected) throw new Error(`sha256 mismatch for ${url}`)
  mkdirSync(path.dirname(file), { recursive: true })
  writeFileSync(file, data)
  return data
}

function parseIndex(text) {
  const packages = new Map()
  for (const block of text.split(/\n\n+/)) {
    const fields = {}
    for (const line of block.split('\n')) {
      const match = /^([A-Za-z-]+):\s*(.*)$/.exec(line)
      if (match) fields[match[1]] = match[2]
    }
    if (fields.Package) packages.set(fields.Package, fields)
  }
  return packages
}

const dependencies = (fields) => [fields.Depends, fields['Pre-Depends']]
  .filter(Boolean)
  .flatMap((list) => list.split(','))
  // "a | b" 取第一个可选项即可满足
  .map((item) => item.split('|')[0].replace(/\(.*?\)/g, '').trim())
  .filter(Boolean)

function resolve(packages, roots) {
  const seen = new Map()
  const visit = (name) => {
    if (seen.has(name)) return
    const fields = packages.get(name)
    if (!fields) throw new Error(`package not found in index: ${name}`)
    seen.set(name, fields)
    dependencies(fields).forEach(visit)
  }
  roots.forEach(visit)
  return [...seen.values()]
}

function extractDeb(debFile, stageDir) {
  const work = mkdtempSync(path.join(os.tmpdir(), 'deb-'))
  try {
    // macOS 的 ar 不认 .deb 里 GNU 风格的成员名（data.tar.xz/），bsdtar 可以直接解 ar 格式
    execFileSync('tar', ['-xf', debFile, '-C', work])
    const data = readdirSync(work).find((name) => name.startsWith('data.tar'))
    if (!data) throw new Error(`no data.tar in ${debFile}`)
    execFileSync('tar', ['-xf', path.join(work, data), '-C', stageDir])
  } finally {
    rmSync(work, { recursive: true, force: true })
  }
}

function prune(usr) {
  for (const rel of PRUNE) rmSync(path.join(usr, rel), { recursive: true, force: true })
  for (const name of readdirSync(path.join(usr, 'bin'))) if (!KEEP_BIN.has(name)) rmSync(path.join(usr, 'bin', name), { recursive: true, force: true })
  const walk = (dir) => {
    for (const name of readdirSync(dir)) {
      const full = path.join(dir, name)
      const stat = lstatSync(full)
      if (stat.isDirectory()) walk(full)
      else if (name.endsWith('.a') || name.endsWith('.la')) rmSync(full)
    }
  }
  walk(usr)
}

function installControl(root) {
  const control = path.join(repo, 'control')
  if (!existsSync(path.join(control, 'node_modules'))) throw new Error('control/node_modules missing; run npm ci --omit=dev in control/ first')
  const target = path.join(root, 'app', 'control')
  mkdirSync(target, { recursive: true })
  for (const name of ['package.json', 'src', 'node_modules']) cpSync(path.join(control, name), path.join(target, name), { recursive: true, verbatimSymlinks: true })
  // 依赖包的命令行入口运行时用不到，且全是符号链接
  rmSync(path.join(target, 'node_modules', '.bin'), { recursive: true, force: true })
}

const isElf = (file) => {
  const head = readFileSync(file).subarray(0, 4)
  return head.length === 4 && head[0] === 0x7f && head.toString('latin1', 1, 4) === 'ELF'
}

// 安装器只解压 lib*.so，带版本号的 libssl.so.3 → libssl_3.so
const jniName = (name) => /^lib.+\.so$/.test(name) ? name : name.replace(/\.so\.(.+)$/, (_, v) => `_${v.replace(/\./g, '_')}.so`)

/**
 * targetSdk ≥ 29 不能执行私有目录里的文件，所以 node 与它加载的 .so 全部改作 jniLibs，
 * 由系统解压到可执行的 nativeLibraryDir；运行时宿主按 libs 映射在私有目录建同名链接。
 */
function splitNative(usr, jniDir) {
  mkdirSync(jniDir, { recursive: true })
  renameSync(path.join(usr, 'bin', 'node'), path.join(jniDir, 'libnode.so'))
  rmSync(path.join(usr, 'bin'), { recursive: true, force: true })
  // OpenSSL 的可选插件，node 默认不加载
  for (const dir of ['engines-3', 'ossl-modules']) rmSync(path.join(usr, 'lib', dir), { recursive: true, force: true })

  const lib = path.join(usr, 'lib')
  const real = new Map()
  for (const name of readdirSync(lib)) {
    const full = path.join(lib, name)
    const stat = lstatSync(full)
    if (stat.isFile() && isElf(full)) real.set(name, jniName(name))
  }
  const libs = []
  for (const name of readdirSync(lib)) {
    const full = path.join(lib, name)
    const stat = lstatSync(full)
    const target = stat.isSymbolicLink() ? path.basename(readlinkSync(full)) : name
    if (!real.has(target)) continue
    libs.push({ name, file: real.get(target) })
  }
  for (const [name, file] of real) renameSync(path.join(lib, name), path.join(jniDir, file))
  for (const { name } of libs) rmSync(path.join(lib, name), { force: true })
  return { exe: 'libnode.so', libs: libs.sort((a, b) => a.name.localeCompare(b.name)) }
}

/** 清单记录每个文件的 sha256 与可执行位；符号链接不进 zip，由宿主按清单创建。 */
function manifest(root) {
  const files = []
  const links = []
  const walk = (dir) => {
    for (const name of readdirSync(dir).sort()) {
      const full = path.join(dir, name)
      const rel = path.relative(root, full).split(path.sep).join('/')
      const stat = lstatSync(full)
      if (stat.isSymbolicLink()) links.push({ path: rel, target: readlinkSync(full) })
      else if (stat.isDirectory()) walk(full)
      else files.push({ path: rel, size: stat.size, sha256: sha256(readFileSync(full)), exec: (stat.mode & 0o111) !== 0 })
    }
  }
  walk(root)
  return { files, links }
}

async function main() {
  const index = parseIndex((await fetchCached(INDEX, path.join(cacheDir, 'Packages'))).toString('utf8'))
  const packages = resolve(index, ROOTS)
  console.log(`resolved ${packages.length} packages: ${packages.map((p) => `${p.Package}@${p.Version}`).join(', ')}`)

  const stage = mkdtempSync(path.join(os.tmpdir(), 'payload-'))
  try {
    for (const fields of packages) {
      const deb = path.join(cacheDir, 'debs', path.basename(fields.Filename))
      await fetchCached(`${MIRROR}/${fields.Filename}`, deb, fields.SHA256)
      extractDeb(deb, stage)
    }
    rmSync(outDir, { recursive: true, force: true })
    const root = path.join(outDir, 'root')
    mkdirSync(root, { recursive: true })
    renameSync(path.join(stage, TERMUX_PREFIX), path.join(root, 'usr'))
    prune(path.join(root, 'usr'))
    const jniDir = path.join(outDir, 'jniLibs', 'arm64-v8a')
    const native = splitNative(path.join(root, 'usr'), jniDir)
    installControl(root)

    const { files, links } = manifest(root)
    const versions = Object.fromEntries(packages.map((p) => [p.Package, p.Version]))
    const control = JSON.parse(readFileSync(path.join(repo, 'control', 'package.json'), 'utf8')).dependencies
    const nativeHashes = Object.fromEntries(readdirSync(jniDir).sort().map((name) => [name, sha256(readFileSync(path.join(jniDir, name)))]))
    const body = { format: 2, versions, control, native: { ...native, hashes: nativeHashes }, files, links }
    const id = sha256(JSON.stringify(body)).slice(0, 16)
    const assets = path.join(outDir, 'assets')
    mkdirSync(assets, { recursive: true })
    writeFileSync(path.join(assets, 'manifest.json'), JSON.stringify({ id, ...body }))

    const list = files.map((f) => f.path).join('\n')
    execFileSync('zip', ['-q', '-X', '-9', '-@', path.join(assets, 'payload.zip')], { cwd: root, input: list })
    const zipSize = lstatSync(path.join(assets, 'payload.zip')).size
    const total = files.reduce((sum, f) => sum + f.size, 0)
    console.log(`payload ${id}: ${files.length} files, ${links.length} links, ${(total / 2 ** 20).toFixed(1)} MB → zip ${(zipSize / 2 ** 20).toFixed(1)} MB`)
    console.log(`native: ${Object.keys(nativeHashes).length} files in jniLibs, ${native.libs.length} library names`)
  } finally {
    rmSync(stage, { recursive: true, force: true })
  }
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
