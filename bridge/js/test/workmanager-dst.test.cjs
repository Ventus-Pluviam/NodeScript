'use strict'
/**
 * `workManager.nextFireAfter` 的 DST 回归（独立文件 = 独立进程，TZ 只影响本文件）。
 *
 * 钉的是什么：daily 的「下一次触发」必须与宿主 `TimedSchedule.Daily` 同一条 —
 * 跨 DST 那天（23h/25h）仍命中**本地同一钟点**，不是固定 +24h 的漂移值。
 * 旧实现 `candidate += 86_400_000`：春日拨快后 09:00 被说成 10:00、秋日拨回后说成 08:00
 * （与宿主 `LocalDate` 逐日重建分歧一小时）。 cron 镜像的候选本来就按
 * `new Date(y, m, d, h, m)` 本地字段重建，不受影响 —— 顺带钉一条「cron 与 daily
 * 在 DST 边界仍同值」。
 *
 * 断言的期望值一律用 `new Date(y, m, d, h, m)` 本地墙钟构造（与 facade.test.cjs 同
 * 一纪律），不写死 epoch —— 换任何 TZ 跑都成立；DST 用例本身依赖 `America/New_York`
 * 的 2026 切换日，TZ 在本文件顶部显式钉死，不读环境。
 */
process.env.TZ = 'America/New_York'

const assert = require('node:assert/strict')
const { test } = require('node:test')
const path = require('node:path')

const auto = require(path.resolve(__dirname, '..', 'dist', 'index.js')).default

test('daily 跨春日拨快（2026-03-08，23h 日）：仍报本地 09:00，不是 +24h 的 10:00', () => {
  // 切换日 02:00 EST→03:00 EDT。从切换前一天的中午问「下一次 09:00」：
  // 宿主（LocalDate 逐日重建）= 2026-03-08 09:00 EDT；
  // 固定 +24h（旧实现）= 2026-03-07 09:00 EST + 24h = 2026-03-08 10:00 EDT（错一小时）。
  const now = new Date(2026, 2, 7, 12, 0, 0, 0).getTime()
  const want = new Date(2026, 2, 8, 9, 0, 0, 0).getTime()
  assert.strictEqual(auto.workManager.nextFireAfter(auto.workManager.daily(9, 0), now), want)
  // 连续两天：切换日之后那天也必须是本地 09:00（漂移只犯一次也不行）。
  const after = new Date(2026, 2, 8, 12, 0, 0, 0).getTime()
  assert.strictEqual(
    auto.workManager.nextFireAfter(auto.workManager.daily(9, 0), after),
    new Date(2026, 2, 9, 9, 0, 0, 0).getTime(),
  )
})

test('daily 跨秋日拨回（2026-11-01，25h 日）：仍报本地 09:00，不是 +24h 的 08:00', () => {
  // 切换日 02:00 EDT→01:00 EST。固定 +24h 会把 2026-11-01 09:00 EST 说成 08:00 EST。
  const now = new Date(2026, 9, 31, 12, 0, 0, 0).getTime()
  const want = new Date(2026, 10, 1, 9, 0, 0, 0).getTime()
  assert.strictEqual(auto.workManager.nextFireAfter(auto.workManager.daily(9, 0), now), want)
})

test('cron 与 daily 在 DST 边界同值（镜像与宿主共用的锚点语义）', () => {
  const before = new Date(2026, 2, 7, 12, 0, 0, 0).getTime()
  assert.strictEqual(
    auto.workManager.nextCronFireAfter('0 9 * * *', before),
    auto.workManager.nextFireAfter(auto.workManager.daily(9, 0), before),
    '春日拨快边界：cron 每日九点与 daily 同值',
  )
  const beforeFall = new Date(2026, 9, 31, 12, 0, 0, 0).getTime()
  assert.strictEqual(
    auto.workManager.nextCronFireAfter('0 9 * * *', beforeFall),
    auto.workManager.nextFireAfter(auto.workManager.daily(9, 0), beforeFall),
    '秋日拨回边界：cron 每日九点与 daily 同值',
  )
})
