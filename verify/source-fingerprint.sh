#!/usr/bin/env bash
# ============================================================================
# source-fingerprint.sh —— 「运行相关源码」的内容指纹（12 位十六进制）
#
# 为什么需要它：
#   判断「jar 是不是最新的」「上次测试还算不算数」，本质是在问
#   「影响运行的那部分内容变了没有」。
#   拿 commit 当判据有两个毛病：
#     · 改一行文档也会让 21 个测试结果作废 —— 假警报多了就没人看了
#     · 反过来，工作区改了没提交时 commit 又没变 —— 假绿
#   所以这里直接对**文件内容**取哈希，既不受提交影响，也不受未提交影响。
#
# 参与指纹（改了它们，才可能改变运行行为或测试结论）：
#   src/main/**        后端代码 + 配置 + 前端静态资源
#   pom.xml            依赖
#   schema.sql         表结构（测试大多查库）
#   verify/*.py        测试断言本身（改了断言，旧结论当然作废）
#   config/application-local.yml   本机数据源（不进 Git，但影响实测）
#
# 不参与（改了不该影响判定）：
#   docs/  AGENTS.md  HANDOVER.md  SESSION.md  deploy/  verify/*.sh
#
# ⚠️ 本脚本必须只有一个实现。run-regression.sh 和 status.sh 都调它，
#    不要在任何一边再写一份 —— 两份算法迟早会漂移，然后天天误报。
#
# 🔴 性能：必须**一次性**把文件列表交给 sha256sum（xargs 批量），
#    绝不能 for/while 循环里逐个调用。Windows 上每次进程启动约 190ms，
#    151 个文件就是 29 秒；批量调用是 0.6 秒（已实测）。
# ============================================================================

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.." || { echo "unknown"; exit 1; }

{
  find src/main -type f 2>/dev/null
  printf '%s\n' pom.xml schema.sql config/application-local.yml
  find verify -maxdepth 1 -type f -name 'verify-*.py' 2>/dev/null
  find verify -maxdepth 1 -type f -name 'lib.py' 2>/dev/null
} | sort -u \
  | xargs -d '\n' sha256sum 2>/dev/null \
  | sha256sum | cut -c1-12
