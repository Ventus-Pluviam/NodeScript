// typedoc@0.28 兼容补丁：typedoc 的目录递归优化调 minimatch 的 `matchOne`，
// 而 minimatch v11 把这个（早已废弃的）API 删了 —— typedoc 上游仍钉在 ^10，
// minimatch 10.x 又带着 GHSA-g3hv-75wg-m249（globparts 放大 DoS），audit 门
// `--audit-level=low` 不许它留在锁里。两难的这个补丁是第三条路：
//
//   `shouldRecurse` 只是一层**性能剪枝**（"这个目录不可能命中就别下去"）。
//   返回 true = 不剪枝、照常递归，最终过滤由 `matches()`（用的是 v11 还在的
//   `Minimatch#match`）负责 —— 结果集不变，只是多走几个目录。node_modules
//   的特判在它上面一行，不受影响。
//
// 幂等：找不到目标段就原样退出（typedoc 哪天发了兼容 v11 的版本，这个补丁
// 自然变成 no-op，那时连同本文件与 package.json 里的 overrides 一起删）。
import { readFileSync, writeFileSync } from 'node:fs'

const target = new URL('../node_modules/typedoc/dist/node-utils.js', import.meta.url)
const NEEDLE = `      return mini.set.some((row) => mini.matchOne(
        childPath,
        row,
        /* partial */
        true
      ));`
const REPLACEMENT = '      return true; // patched: minimatch v11 无 matchOne，剪枝退化为全递归（见 scripts/patch-typedoc-matchone.mjs）'

let src
try {
  src = readFileSync(target, 'utf8')
} catch {
  console.log('[patch-typedoc] node_modules/typedoc 不在（npm ci --omit=dev？），跳过')
  process.exit(0)
}
if (src.includes(REPLACEMENT)) {
  console.log('[patch-typedoc] 已打过，跳过')
} else if (src.includes(NEEDLE)) {
  writeFileSync(target, src.replace(NEEDLE, REPLACEMENT))
  console.log('[patch-typedoc] typedoc matchOne 剪枝已替换为全递归')
} else {
  console.log('[patch-typedoc] 目标段不存在（typedoc 可能已修复 minimatch v11 兼容），未改动')
}
