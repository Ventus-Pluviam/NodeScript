'use strict'
/**
 * JNI 名字改编对账门（images_jni.cc ⇄ `external fun` 声明类，缺省 JNI 改编）。
 *
 * 全仓没有一处 `RegisterNatives`/`JNI_OnLoad`——五个 JNI 入口全靠缺省名字改编
 * 解析：`Java_<包>_<声明类>_<方法>`。这意味着 `images_jni.cc` 里的类名段必须等于
 * **声明** `external fun` 的那个类的名字，而不是注释里写、也不是历史上叫过的名字。
 * 这道门用三处独立事实互相钉：
 *
 * - 断言 A（声明类）：`platform/system/.../NativeImageAnalyzer.kt` 里**顶层**
 *   `class JniOps`（brace depth 0，不是嵌套类）的类体里有 5 个
 *   `external fun {decode,ingest,match,release,color}Native`，包名
 *   `com.autoscript.platform.system`；
 * - 断言 B（cc 符号）：`images_jni.cc` 的 `^Java_<包>_<类>_<方法>(` 五个的类名段
 *   必须全是 `JniOps`（2026-09-26 之前是 `NativeImageAnalyzer_`，与声明类对不上，
 *   本门抓到后已修；旧前缀出现即回潮）；
 * - 断言 C（改编期望）：按缺省规则算出期望符号
 *   `Java_com_autoscript_platform_system_JniOps_<m>`，五个必须**在场**——缺一个，
 *   真机 `loadOrNull()` 就回 null，那条 images 缝全 NOT_IMPLEMENTED。
 *
 * 手边的 `node-runtime-build/out-opencv/libopencv.so` 是 gitignore 产物，不进门：
 * 它只能证明"构建过一次"（2026-09-25 那份还停在 findColor 之前，符号少一个）。
 */
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')

const ROOT = (() => {
  let p = path.resolve(__dirname, '..', '..')
  for (let i = 0; i < 8; i += 1) {
    if (fs.existsSync(path.join(p, 'settings.gradle.kts'))) return p
    p = path.dirname(p)
  }
  throw new Error('找不到仓库根')
})()

const KT_PATH = 'platform/system/src/main/kotlin/com/autoscript/platform/system/NativeImageAnalyzer.kt'
const CC_PATH = 'bridge/image/src/main/cpp/images_jni.cc'
const KT = fs.readFileSync(path.join(ROOT, KT_PATH), 'utf8')
const CC = fs.readFileSync(path.join(ROOT, CC_PATH), 'utf8')

/** 顶层 `class <name>` 的类体 + 匹配时的 brace depth（嵌套类 depth>0，当场识破）。 */
function topLevelClassBody(src, name) {
  const lines = src.split('\n')
  let depth = 0
  for (let i = 0; i < lines.length; i += 1) {
    if (new RegExp(`^(?:internal\\s+|private\\s+|public\\s+)?class ${name}\\b`).test(lines[i].trim())) {
      const depthAtMatch = depth
      let d = 0
      for (let j = i; j < lines.length; j += 1) {
        d += (lines[j].match(/\{/g) || []).length - (lines[j].match(/\}/g) || []).length
        if (j > i && d === 0) return { body: lines.slice(i, j + 1).join('\n'), depthAtMatch }
      }
      return { body: null, depthAtMatch }
    }
    depth += (lines[i].match(/\{/g) || []).length - (lines[i].match(/\}/g) || []).length
  }
  return { body: null, depthAtMatch: -1 }
}

const METHODS = ['decodeNative', 'ingestNative', 'matchNative', 'releaseNative', 'colorNative']

test('声明类：顶层 class JniOps 的类体里有 5 个 external fun（不是嵌套类）', () => {
  const pkg = (KT.match(/^package\s+([\w.]+)/m) || [])[1]
  assert.strictEqual(pkg, 'com.autoscript.platform.system', `包名漂了: ${pkg}`)
  const { body, depthAtMatch } = topLevelClassBody(KT, 'JniOps')
  assert.ok(body, '找不到 class JniOps——被改名/删了？')
  assert.strictEqual(depthAtMatch, 0, 'JniOps 被嵌进别的类里了——JNI 改编名会带上外部类（Class_Outer_Inner_…），cc 全得改')
  const found = [...body.matchAll(/\bexternal\s+fun\s+([A-Za-z_]\w*)\s*\(/g)].map((m) => m[1]).sort()
  assert.deepStrictEqual(found, [...METHODS].sort(), `JniOps 类体里的 external fun 变了: ${found.join(', ')}`)
})

test('cc 符号：五个 JNI 函数名的类名段全是 JniOps（旧 NativeImageAnalyzer_ 出现即回潮）', () => {
  const syms = [...CC.matchAll(/^Java_([\w]+)_([A-Za-z_]\w+?)_(decodeNative|ingestNative|matchNative|releaseNative|colorNative)\(/gm)]
  assert.strictEqual(syms.length, 5, `cc 里只解析到 ${syms.length} 个 JNI 函数——images_jni.cc 结构漂了`)
  const pkgs = [...new Set(syms.map((m) => m[1]))]
  const classes = [...new Set(syms.map((m) => m[2]))]
  assert.deepStrictEqual(pkgs, ['com_autoscript_platform_system'], `cc 包名段漂了: ${pkgs.join(', ')}`)
  assert.deepStrictEqual(classes, ['JniOps'], `cc 类名段不是 JniOps（声明类是 JniOps，见断言 A）: ${classes.join(', ')}`)
})

test('改编期望：JVM 要找的五个 JniOps_ 符号必须全在 cc 里（缺一个，真机那条缝就全 NOT_IMPLEMENTED）', () => {
  const expected = METHODS.map((m) => `Java_com_autoscript_platform_system_JniOps_${m}`)
  const missing = expected.filter((s) => !CC.includes(s))
  assert.deepStrictEqual(missing, [], `cc 缺 JVM 要找的符号（声明类 JniOps，改编名 JniOps_）: ${missing.join(', ')}`)
})

test('CI 符号面断言同批：check-opencv-alignment.sh 查的必须也是 JniOps_（CI 侧漏改即红）', () => {
  const sh = fs.readFileSync(path.join(ROOT, 'node-runtime-build/scripts/check-opencv-alignment.sh'), 'utf8')
  const prefixes = [...new Set([...sh.matchAll(/Java_com_autoscript_platform_system_([A-Za-z_]+?)_\$\{sym\}/g)].map((m) => m[1]))]
  assert.deepStrictEqual(prefixes, ['JniOps'], `check 脚本查的类名段不是 JniOps（CI 与本门分叉）: ${prefixes.join(', ') || '(没解析到)'}`)
  for (const m of METHODS) {
    assert.ok(sh.includes(m.replace(/Native$/, '')), `check 脚本的符号循环里缺 ${m}`)
  }
})

test('全仓无 RegisterNatives：改编是唯一解析路径（断言 C 的前提）', () => {
  const hits = []
  const walk = (dir) => {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const p = path.join(dir, e.name)
      if (e.isDirectory()) {
        if (['build', 'node_modules', '.git', 'out-opencv'].includes(e.name)) continue
        walk(p)
      } else if (/\.(cc|cpp|c|h|hpp|kt|java)$/.test(e.name)) {
        const src = fs.readFileSync(p, 'utf8')
        if (/RegisterNatives|JNI_OnLoad/.test(src)) hits.push(path.relative(ROOT, p))
      }
    }
  }
  walk(ROOT)
  assert.deepStrictEqual(hits, [], `出现显式 JNI 注册，改编不再是唯一路径，本门前提变了: ${hits.join(', ')}`)
})
