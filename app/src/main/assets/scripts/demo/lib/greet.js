/**
 * 示例项目的子目录文件：验证 assets 递归部署（`lib/` 也要落到
 * `files/scripts/demo/lib/greet.js`，否则 `require('./lib/greet')` 解析不到）。
 */
function greet(who) {
  return '你好，' + who;
}

module.exports = { greet };
