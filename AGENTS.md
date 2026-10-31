# AGENTS.md —— 给接手这个项目的 AI

第二课堂学时答疑工单系统 · Spring Boot 单体 + 内置静态前端 · 一个 jar 跑全套

## 动手前

```bash
bash verify/status.sh   # 代码 / 构建 / 测试 / 线上 / 数据库，各到哪一层
git status              # 有未提交改动先确认归属，别覆盖别人的活
```

按顺序读这三个，读完就够：

1. `HANDOVER.md` —— 结构、启动方式、坑在哪
2. `.workbuddy/memory/CONVENTIONS.md` —— 分层、拦截器、事务，以及几条会静默出错的铁律
3. `.workbuddy/memory/MODULES.md` —— 业务规则（工单状态机、附件、敏感词、缓存）

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
- 交接只改 `HANDOVER.md` 的维护区（下一步 / 风险 / 刻意没做的事），别另开文件。
