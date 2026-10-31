# AGENTS.md —— 给接手这个项目的 AI

第二课堂学时答疑工单系统 · Spring Boot 单体 + 内置静态前端 · 一个 jar 跑全套

## 动手前

```bash
bash verify/install-hooks.sh   # 只需一次：装提交前检查（换机器 / 重新 clone 后要重跑）
bash verify/status.sh          # 代码 / 构建 / 测试 / 线上 / 数据库，各到哪一层
bash verify/session.sh         # 上一轮停在哪、下一步是什么（没有就 new 一份）
git status                     # 有未提交改动先确认归属，别覆盖别人的活
```

提交时钩子会硬拦四类东西（明文口令、私钥、超 5MB 大文件、本地配置文件），
其余问题只提醒不阻断。**不要习惯性用 `--no-verify`** —— 那等于关掉唯一的守门人。

按顺序读这四个，读完就够。**后三份不在本仓库里**，在这个项目的 AI 工作目录下
（`C:\Users\joya\WorkBuddy\2026-09-10-21-34-32\.workbuddy\memory\`），别在项目目录里找：

1. `HANDOVER.md` —— 结构、启动方式、目录说明（仓库内）
2. `…\memory\CONVENTIONS.md` —— 分层、拦截器、事务，以及几条会静默出错的铁律
3. `…\memory\MODULES.md` —— 业务规则（工单状态机、附件、敏感词、缓存）
4. `…\memory\ENV.md` —— 本机路径、临时实例怎么起、上线硬约束

要动部署，加读 `deploy/DEPLOY.md`。

## 改完

```bash
bash verify/run-regression.sh 8117   # 先用临时实例，别占 8080
git commit -m "type(scope): 中文描述"
```

改了表结构：同步 `schema.sql` + 新增 `deploy/migrate-*.sql`，并在 `t_schema_version` 记一行。
只改前端：至少跑 `bash verify/run-regression.sh 8117 verify-frontend.py`。

## 别做

- 别新建状态类文档（`CURRENT_STATE.md` 之类）。状态跑 `status.sh` 拿，手写的必然过期。
- 别在文档里写「已完成」「已上线」。写文件路径、时间戳、命令输出——能验证的才算数。
- 别把新 jar 直接传线上。先看 `status.sh` 里「线上缺认证字段」那条，否则会全站 500。
- 别往 `target/` 放要长期保存的东西，`mvn clean` 会整个删掉。
- 交接只写 `SESSION.md`（`bash verify/session.sh new` 生成骨架），**别开第二个交接文件**。
  `HANDOVER.md` 只放十年不变的东西（结构、启动方式、目录说明）；「下一步 / 卡在哪」属于 `SESSION.md`。
