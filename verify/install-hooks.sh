#!/usr/bin/env bash
# ============================================================================
# 安装 git 钩子 —— 一条命令，之后每次 commit 自动检查
#
#   bash verify/install-hooks.sh
#
# 做了什么：
#   把 git 的钩子目录指向 verify/hooks（而不是默认的 .git/hooks）。
#   🔴 为什么不用 .git/hooks：那个目录**不进版本库**，换台机器 / 重新 clone 就没了。
#      指向 verify/hooks 以后，钩子文件本身就是受版本控制的，谁 clone 下来都有。
#
# 注意：core.hooksPath 是**本机配置**，不跟着仓库走。
#       换机器后需要重新跑一次这个脚本（AGENTS.md 里已写明）。
#
# 卸载 / 恢复默认：
#   git config --unset core.hooksPath
# ============================================================================

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR" || { echo "进不去项目目录"; exit 2; }

if ! git rev-parse --git-dir >/dev/null 2>&1; then
  echo "❌ 这里不是 git 仓库：$PROJECT_DIR"
  exit 2
fi

HOOK="$PROJECT_DIR/verify/hooks/pre-commit"
if [ ! -f "$HOOK" ]; then
  echo "❌ 找不到钩子文件：$HOOK"
  exit 2
fi

# Windows 下 chmod 意义不大（git 不看权限位），但换到 Linux/macOS 就需要。
chmod +x "$HOOK" 2>/dev/null || true

git config core.hooksPath verify/hooks || { echo "❌ 写 git 配置失败"; exit 1; }

echo "✅ 钩子已启用"
echo "   core.hooksPath = $(git config core.hooksPath)"
echo "   钩子文件       = verify/hooks/pre-commit"
echo ""
echo "从下一次 git commit 开始生效："
echo "   · 硬拦：明文口令 / 私钥 / 超过 5MB 的大文件 / 本地配置文件"
echo "   · 提醒：测试记录过期 / 前端版本号没改 / 改了表却没写迁移脚本"
echo ""
echo "（core.hooksPath 是本机配置，换机器后要重新跑一次这个脚本）"
