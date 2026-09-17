#!/usr/bin/env bash
# skill-designer 自检：校验一个 skill 目录的结构
# 用法: validate-skill.sh <skill-dir>
set -u

dir="${1:?用法: validate-skill.sh <skill-dir>}"
fail=0
say() { printf '%s\n' "$*"; }
err() { say "[✗] $*"; fail=1; }

say "校验: $dir"
[ -d "$dir" ] || { err "目录不存在"; exit 1; }
skill="$dir/SKILL.md"
[ -f "$skill" ] || { err "缺少 SKILL.md"; exit 1; }

# 1) frontmatter 结构：首行 ---，name/description 存在且非空
first=$(awk 'NF { print; exit }' "$skill")
[ "$first" = "---" ] || err "SKILL.md 首非空行应为 '---' (当前: '$first')"

fm=$(awk '/^---$/{n++; next} n==1' "$skill")
echo "$fm" | grep -q '^name:'    || err "frontmatter 缺少 name:"
echo "$fm" | grep -q '^description:' || err "frontmatter 缺少 description:"

name=$(echo "$fm" | sed -n 's/^name:[[:space:]]*\(.*\)/\1/p' | head -1)
desc=$(echo "$fm" | sed -n 's/^description:[[:space:]]*\(.*\)/\1/p' | head -1)
[ -n "$name" ] || err "name 为空"
[ -n "$desc" ] || err "description 为空"
echo "$name" | grep -qE '^[a-z0-9]+(-[a-z0-9]+)*$' || err "name 应 kebab-case 小写: '$name'"
[ "${#name}" -le 64 ] || err "name 超 64 字符"
[ "${#desc}" -le 1024 ] || err "description 过长 (${#desc} 字符)"

# 2) 目录名与 name 一致
[ "$(basename "$dir")" = "$name" ] || err "目录名 '$(basename "$dir")' != frontmatter name '$name'"

# 3) 相对引用可解析（references/ scripts/ resources/）
missing=""
while IFS= read -r p; do
  [ -n "$p" ] || continue
  [ -e "$dir/$p" ] || missing="$missing $p"
done < <(grep -oE '(references|scripts|resources)/[A-Za-z0-9._/-]+' "$skill" | sort -u)
[ -z "$missing" ] || err "引用文件缺失:$missing"

# 4) scripts 下的脚本须可执行
while IFS= read -r s; do
  [ -x "$s" ] || err "脚本不可执行 (chmod +x): ${s#"$dir"/}"
done < <(find "$dir/scripts" -maxdepth 1 -type f 2>/dev/null)

# 5) 行尾与结尾
grep -q $'\r' "$skill" && err "SKILL.md 含 CRLF 行尾"
[ -s "$skill" ] || err "SKILL.md 为空"

if [ "$fail" -eq 0 ]; then
  say "✓ 校验通过: $name"
  exit 0
fi
say "✗ 校验失败"
exit 1