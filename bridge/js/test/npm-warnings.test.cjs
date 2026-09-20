'use strict'
/**
 * npm 警告种类对齐单测（§10.5-1/§10.5-3 禁止静默）：
 * - onWarning 收宿主经 [feedWarning] 投递的 InstallWarning（五种 kind 全收）
 * - 未知 kind 炸（契约漂移 = Kotlin 新增 InstallEvent.Kind 而 JS 没同步，绝不静默丢弃）
 * - 退订后不再通知
 * - kind 字面量与 :domain InstallEvent.Kind 枚举五个值一一对应（硬编码清单双断言）
 *
 * 对 dist/ 产物断言（npm test 先 tsc）。
 */
const assert = require('node:assert/strict')
const { test } = require('node:test')
const path = require('node:path')

const npmModule = require(path.resolve(__dirname, '..', 'dist', 'npm.js'))
const { npm, feedWarning } = npmModule

function w(kind, pkgs) {
  return { projectId: 'main', handleId: '', kind, pkgs, message: `测试 ${kind}` }
}

test('onWarning 收到五种 kind（与 :domain InstallEvent.Kind 对齐）', () => {
  const got = []
  const off = npm.onWarning((e) => got.push(e))
  try {
    for (const kind of [
      'scripts-skipped',
      'trust-downgraded',
      'registry-fallback',
      'low-memory',
      'disk-quota',
    ]) {
      feedWarning(w(kind, ['esbuild']))
    }
  } finally {
    off()
  }
  assert.deepStrictEqual(
    got.map((e) => e.kind),
    ['scripts-skipped', 'trust-downgraded', 'registry-fallback', 'low-memory', 'disk-quota'],
  )
  // pkgs 透传（脚本据此渲染包清单）；message/ projectId / handleId 原样
  assert.deepStrictEqual(got[1].pkgs, ['esbuild'])
  assert.strictEqual(got[1].projectId, 'main')
  assert.strictEqual(got[1].handleId, '')
})

test('未知 kind 抛错（Kotlin 新增枚举而 JS 没同步 = 契约漂移，禁止静默）', () => {
  assert.throws(
    () => feedWarning(w('supply-chain-brand-new', ['evil'])),
    /未知安装警告种类: supply-chain-brand-new/,
  )
})

test('退订后 feedWarning 不再通知', () => {
  const got = []
  const off = npm.onWarning((e) => got.push(e))
  feedWarning(w('trust-downgraded', ['a']))
  off()
  feedWarning(w('trust-downgraded', ['b']))
  assert.strictEqual(got.length, 1)
})
