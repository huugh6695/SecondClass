# 接手说明（HANDOVER）

> 这份文档写给「要接手改这个项目的人」。目标：让你在 **30 分钟内跑起来**，**半天内敢动手改**，并且不踩那几个会静默出错的坑。
>
> 如果你只想快速确认「这是什么」，看下面第一节就够了。
>
> **本文档只写稳定信息**（结构、启动方式、坑在哪）。
> 易变信息 —— 脚本数量、断言条数、线上跑的是哪一版 —— **一律不写**，因为写了当天就会过期，
> 而过期的信息比没有信息更糟（它看起来是权威的）。
> 要查现状，跑一条命令：
>
> ```bash
> bash verify/status.sh
> ```

---

## 0. 一句话说清这是什么

**第二课堂学时答疑工单系统**：学生提工单（学时没录上、活动咨询、规则疑问……）→ 干事接单回复 → 学生确认或追问 → 管理员管数据。

- **在线地址**：<http://<你的服务器IP>:8080>
- **形态**：单体 Spring Boot 应用（后端接口 + 内置静态前端，打在同一个 jar 里）
- **规模**：14 张表、58 个接口、前端 4 个文件（这三个数会随开发变化，只用来建立印象，不要当验收标准）
- **现状**：🔴 **不在这里写**。线上跑的是哪一版、本地测试过没过，跑 `bash verify/status.sh` 看，
  它会把「代码 / 本地构建 / 测试 / 线上 / 数据库」五个位置分别报出来，不一致的地方标红。

---

## 1. 项目文件都在哪

### 1.1 源码位置

```
D:\basic codes\second-classroom\        ← 项目根目录（⚠️ 路径含空格，命令行里要加引号）
```

> 建议接手第一件事：**把它复制到没有空格的路径**（如 `D:\projects\second-classroom`）。
> 路径带空格会让某些命令行工具、脚本参数解析出问题（本项目已经被坑过）。

### 1.2 根目录一览（改代码只需要认识这 6 个）

| 路径 | 是什么 | 什么时候需要动它 |
|---|---|---|
| `src/main/java/com/secondclassroom/` | **全部后端代码** | 改业务逻辑 |
| `src/main/resources/static/` | **全部前端代码**（4 个文件） | 改界面 |
| `src/main/resources/application*.yml` | 配置（开发 / 生产两套） | 改数据库、端口、上传目录 |
| `schema.sql` | 建表语句（14 张表） | 加字段、加表 |
| `pom.xml` | 依赖清单 | 加库 |
| `deploy/` | **部署/运维文档与脚本** | 上线、备份、建账号 |

其余：

| 路径 | 说明 |
|---|---|
| `requests.http` | **153 KB 的手工测试集**（29 组请求，A/B/C/D 四组）。IDEA 里可逐条点着跑 |
| `verify/` | **验收脚本与状态工具**：`status.sh`（查五个版本号到哪一层）、`run-regression.sh`（跑全套验收）、`lib.py` 公共库、`verify-*.py` 各模块验收。已纳入 git，不会丢 |
| `target/` | Maven 构建产物。🔴 **别往这里放任何需要长期保存的东西** —— `mvn clean` 会整个删掉 |
| `logs/` | 本机运行日志 |
| `uploads/` | 本机上传的附件 |
| `HELP.md` | Spring Initializr 自动生成的样板文件，**没用，可以删** |

### 1.3 后端代码分层（`src/main/java/com/secondclassroom/`）

| 包 | 文件数 | 干什么 | 关键类 |
|---|---|---|---|
| `controller` | 18 | **只收参数、转交 service、包 `Result`**。没有业务逻辑 | `WorkOrderController`（14 个接口）、`AuthController` |
| `service` | 18 | **全部业务逻辑在这**。写 SQL、判权限、写日志都在这一层 | `WorkOrderService`（2033 行，最核心） |
| `dto` | 53 | 请求体 / 响应体的数据结构（纯 getter/setter） | `LoginRequest`、`WorkOrderDetailResult` |
| `common` | 6 | 全局基础设施 | `Result`、`GlobalExceptionHandler`、`TokenBlacklist`、`IdempotencyStore` |
| `util` | 3 | 工具类 | `JwtUtil`、`DailySeqId`（单号生成）、`WebUtil` |
| `model` | 2 | 只有 `User`、`WorkOrder` 两个实体（其余表直接用 Map/JdbcTemplate） | — |
| `interceptor` | 1 | `JwtInterceptor`：校验 Token、把 `userId`/`userType` 塞进 request | — |
| `config` | 1 | `WebConfig`：注册拦截器 + 放行白名单 | — |

**一句话记住分层规矩**：

> Controller 不写业务，Service 不碰 `HttpServletRequest`，SQL 只出现在 Service 里。

---

## 2. 30 秒跑起来

### 2.1 前置条件

| 依赖 | 版本要求 | 本机已知可用版本 |
|---|---|---|
| JDK | 17（必须，别用 21/25） | `C:\Users\joya\.jdks\jdk-17.0.14+7` |
| MySQL | 8.0 | 8.0.46，端口 3306 |
| Maven | 3.9+ | 项目自带 `mvnw`，或 `C:\Users\joya\maven\apache-maven-3.9.16` |
| Redis | 5.0+（用于 Token 黑名单 + 敏感词缓存） | 5.0.14，端口 6379 |

### 2.2 三条命令

```bash
# 1) 建库建表（只需一次；schema.sql 不含 CREATE DATABASE，先手工建库）
mysql -uroot -p -e "CREATE DATABASE second_classroom DEFAULT CHARSET utf8mb4"
mysql -uroot -p --default-character-set=utf8mb4 second_classroom < schema.sql

# 2) 灌一个管理员（初始密码 123456；管理员仍由后台/导入预置）
mysql -uroot -p --default-character-set=utf8mb4 second_classroom < deploy/seed.sql

# 3) 启动（IDEA 里直接跑 SecondClassroomApplication 也行）
./mvnw spring-boot:run
```

打开 <http://localhost:8080> → 用 `2023003001 / 123456` 登录。

### 2.3 想多加几个账号（学生/干事）

学生可以在登录页自助注册：`学号 + 姓名 + 手机验证码 + 自设密码`。注册接口固定创建
`STUDENT`，不能传角色；管理员和干事账号仍由后台导入/部署预置：

```bash
cd deploy
# 1) 准备名单（UTF-8，一行一个：学号,姓名,角色）
#    角色写 STUDENT / STAFF / ADMIN，写中文"学生/干事/管理员"也认
# 2) 生成 INSERT（密码是 bcrypt 哈希，SQL 算不出来，必须用代码生成）
gen-import-sql.bat 名单.csv 输出.sql      # Windows 双击/命令行
# 3) 导入
mysql -uroot -p --default-character-set=utf8mb4 second_classroom < 输出.sql
```

历史预置账号可能仍使用**学号后 6 位**作为初始密码；自助注册账号由学生自行设置密码。
新设备登录、忘记密码、更换手机号需要短信验证。短信网关和数据库升级见
`deploy/README-auth-registration.md`。

---

## 3. 一个请求是怎么走的（以「提交工单」为例）

看懂这条链，后面改任何接口都是同一个套路。

```
① 前端 app.js
   fetch POST /api/work-orders
   body: { category, title, content, attachmentIds }
   header: Authorization: Bearer <token>
        ↓
② JwtInterceptor
   校验 Token 签名 / 是否过期 / 是否在 Redis 黑名单
   → 通过则 request.setAttribute("userId", ...) 和 ("userType", ...)
        ↓
③ WorkOrderController.submit()
   只做三件事：取 request 里的 userId、接 @RequestBody、调 service
        ↓
④ WorkOrderService.submit()
   ├─ @Transactional  ← 事务开始
   ├─ 参数校验（敏感词、内容长度、分类枚举…）★ 必须排在所有写库之前
   ├─ 幂等检查（requestId 是否重复）
   ├─ 生成单号（DailySeqId：WO + 日期 + 序号）
   ├─ INSERT t_work_order
   ├─ INSERT t_status_log（状态流水）
   ├─ INSERT t_attachment（绑定附件）
   ├─ 重复检测（jieba 分词比对未完结工单，只提示不拦截）
   └─ 写操作日志（t_operation_log）
        ↓
⑤ 返回 Result.success(单号)   → 前端跳转到详情页
```

### 3.1 统一返回格式

```json
{ "code": 0, "message": "提交成功", "data": { "workOrderId": "WO20261025000001" } }
```

- `code = 0` 表示业务成功，**HTTP 状态码恒为 200**
- `code ≠ 0` 是业务失败（如 40001 参数错误），**HTTP 仍然是 200**
- 只有 401（未登录/Token 失效）、403（角色不够）由拦截器 `setStatus` 真的改成 HTTP 状态码

**所以：前端判断成功要看 `code`，不能看 HTTP 状态。**

---

## 4. 数据库：13 张表

| 表 | 作用 | 备注 |
|---|---|---|
| `t_user` | 账号 | `user_id` = 学号/工号，`password` 存 bcrypt，`user_type` = STUDENT/STAFF/ADMIN |
| `t_work_order` | 工单主表 | 核心表。`status`、`version`（乐观锁）、`duplicate_of_order_id`（疑似重复） |
| `t_work_order_message` | 工单对话 | 学生追问 + 干事回复，都在这 |
| `t_message_read` | 已读标记 | 哪个用户读到哪条 |
| `t_attachment` | 附件 | `work_order_id` 为空 = 还没绑定到工单 |
| `t_faq` | 常见问题 | 游客可见（不登录也能看） |
| `t_announcement` | 公告 | 游客可见 |
| `t_merge_relation` | 工单合并关系 | 把重复的工单挂到主单上 |
| `t_status_log` | 状态流水 | 每次状态变更一条，详情页的时间线就是它 |
| `t_operation_log` | 操作日志 | 谁在什么时候干了什么（后台可查） |
| `t_quick_reply_template` | 回复模板 | 干事回复时一键套用 |
| `t_notification_log` | 通知记录 | 含重试计数；当前只发站内通知 |
| `t_sensitive_word` | 敏感词 | 命中后给工单打 `is_flagged` 标记（不拦截） |

### 4.1 工单状态机（改动前必须理解）

```
PENDING（待接单）
   ↓ 干事接单
PROCESSING（处理中）
   ↓ 干事回复
REPLIED（已回复）
   ├─ 学生点「确认解决」→ CLOSED（已完成）
   └─ 学生「再次咨询」→ 新建一张工单，老单挂到新单上
   干事「退回」→ PENDING（附退回理由）
   管理员「强制关闭」→ CLOSED（必填关闭原因）
```

⚠️ 「未完结」= `PENDING + PROCESSING + REPLIED` 三种。重复检测、统计口径都用这个定义。

---

## 5. 接口：58 个

> 🔴 **原来的契约真源 `target/doc.txt`（80 KB）已在 2026-10-28 丢失** —— 和验收脚本一起被
> `mvn clean` 清掉了，git 里没有备份（`target/` 被忽略）。
>
> 现在读接口按这个顺序，可信度从高到低：
>
> 1. **`controller/` + `dto/` 的 Javadoc 注释** —— 第一手依据。这个项目的注释写得比文档还细，
>    很多地方直接标了「文档 §几」和「为什么这么写」
> 2. **`requests.http`（153 KB、29 组）** —— 请求体的实物样例，能直接点着跑
> 3. `schema.sql` —— 字段类型和枚举取值
>
> 重建契约文档时按这个顺序抄，别凭印象补。

按模块分布：

| 模块 | 大致接口数 | 谁能调 |
|---|---|---|
| 认证（登录/注册/短信验证/找回密码/改密/换手机） | 11 | 所有人或登录用户 |
| 工单（提交/列表/详情/回复/追问/确认/关闭/接单/退回/再次咨询…） | 14 | 学生 + 干事 + 管理员（按动作区分） |
| 附件、消息、通知 | 6 | 登录用户 |
| 公告、FAQ、回复模板（读） | 4 | 公告/FAQ 游客可读 |
| 健康检查 `/health` | 1 | 无需登录 |
| 管理端（用户/工单/统计/导出/日志/敏感词/模板/公告/FAQ） | 22 | 仅 ADMIN |

### 5.1 手工测试怎么用

打开 `requests.http`（IDEA 里会有绿色箭头可以逐条点）。四组：

| 组 | 用途 | 注意 |
|---|---|---|
| **A 组** | 主流程（提交 → 接单 → 回复 → 确认 → 关闭） | 需要先执行 A0 登录拿 Token |
| **B 组** | 附件、消息、通知 | 同上 |
| **C 组** | 管理端 | 需要管理员 Token |
| **D 组** | 疑似重复回放（D0–D8） | 可重复跑，用时间戳做唯一内容 |

🔴 **规矩：`requests.http` 里不许出现 `<单号>` 这种占位符。** 每条请求必须是"复制粘贴就能跑"的，否则对方一上手就卡住。

---

## 6. 前端怎么工作

**4 个文件，零构建、零依赖、没有 npm。** 改完刷新浏览器就生效。

```
src/main/resources/static/
├── index.html      1.2 KB   ← 只有一个空容器 <div id="app">，内容全靠 JS 渲染
├── css/app.css    32 KB     ← 全部样式（1223 行，25 个带注释的章节）
└── js/
    ├── api.js     42 KB     ← 所有 fetch 封装，一个接口一个函数
    └── app.js    198 KB     ← 视图 + 路由 + 交互（15 个 viewXxx() 函数）
```

### 6.1 路由怎么工作

用的是 **hash 路由**（地址栏里 `#` 后面那截），不是 Vue Router：

```
http://站点/          → 我的工单列表（登录后首页）
http://站点/#/submit  → 提交工单
http://站点/#/orders/WO20261025000001  → 工单详情
http://站点/#/admin/users  → 后台管理
```

`app.js` 末尾的 `render()` 函数就是路由表，一个 `switch` 加几个前缀判断。**加新页面 = 加一个 `case` + 写一个 `viewXxx()`**。

### 6.2 页面清单（共 17 个视图）

| 学生 / 干事端（9） | 管理端（6） |
|---|---|
| 登录、学生注册、找回密码、我的工单（干事看到的是「工单处理」）、工单详情、提交工单、再次咨询、消息通知、公告、常见问题、账号安全 | 后台首页、用户管理、敏感词、回复模板、数据统计、数据导出、操作日志 |

### 6.3 改前端的三条实用提示

1. **样式全部在 `app.css`**，`index.html` 里一个 `style` 都没有，`app.js` 里只有 1 处内联（进度条宽度，是动态值）
2. **类名在 `app.js` 里是硬编码的**（145 个），而且有些是拼接出来的，比如 `class="badge ${cls}"`。改 CSS 类名必须同步改 JS 里的拼接逻辑，否则**样式会突然消失但不报错**
3. **`app.css` 里有 40 处颜色没走变量**（状态徽章的浅底色）。改主题色时这些要一起改，否则会残留旧色

---

## 7. 🔴 改代码前必读的 8 个坑

这些不是"最佳实践"，是**已经踩过、而且出错时不给报错提示**的坑。

### 坑 1：`@Transactional` 里"返回错误"不会回滚

```java
@Transactional
public Result<?> doSomething() {
    jdbcTemplate.update("INSERT ...");     // 已经写进去了
    if (不合法) return Result.fail("xxx"); // ❌ 事务照样提交！
}
```

**只有抛异常才回滚。** 所以：任何"要拒绝"的校验，**必须排在所有写库操作之前**（只读预校验 → 业务写 → 最后绑定关系）。

### 坑 2：`IllegalStateException` 会被全局处理器固定映射成 409

想让前端收到 500（服务器内部错误），**必须抛 `RuntimeException`**。`IllegalStateException` 在 `GlobalExceptionHandler` 里被硬编码成 409 了（用于乐观锁冲突这类"状态不对"）。

### 坑 3：`jdbcTemplate.query(sql, 回调)` 不传参数位，`?` 会原样发给数据库

```java
jdbcTemplate.query("SELECT * FROM t WHERE id = ?", rs -> {...});        // ❌ 报 bad SQL grammar near '?'
jdbcTemplate.query("SELECT * FROM t WHERE id = ?", 回调, id);           // ✅ 第三个参数必须给
```

原因是 lambda 会匹配到 `query(String, RowCallbackHandler)` 这个重载。**编译不报错，运行时才炸。**

### 坑 4：Spring Boot 4 用的是 Jackson 3，包名变了

```java
import tools.jackson.databind.ObjectMapper;   // ✅ Boot 4
import com.fasterxml.jackson.databind.*;      // ❌ 不在编译路径里
```

但 `@JsonProperty` 注解**仍然**来自 `com.fasterxml.jackson.annotation`。

### 坑 5：布尔字段的 getter 必须叫 `getIsFlagged()`

```java
public Boolean getIsFlagged() { return isFlagged; }   // ✅ JSON 输出 "isFlagged"
public Boolean isFlagged()    { return isFlagged; }   // ❌ JSON 输出 "flagged"
```

前端按 `isFlagged` 取，改名就变 `undefined`，而且**接口不报错**。

### 坑 6：SQL 里 `col != 'x'` 遇到 NULL 结果是"假"

```sql
-- ❌ 漏掉 col 为 NULL 的行
WHERE status != 'CLOSED'
-- ✅
WHERE status IS NULL OR status != 'CLOSED'
```

### 坑 7：别用字符串替换拼 SQL

```java
sql = sql.replace("WHERE", cond + " AND");   // ❌ 会拼出两个 WHERE
```

正确做法：把条件写成 `List<String>`，最后 `String.join(" AND ", list)`。

### 坑 8：审计日志的 `target_id` 只能放业务主键

`t_operation_log.target_id` 是 `varchar(64)`，**只放主键字符串**（数字也要 `String.valueOf`）。人读的说明放 `operation_content`；批量操作放哨兵值 `"BATCH"`。

### 另外两条业务约定

- **乐观锁 `version` 必传的只有 5 个接口**：确认解决、关闭、管理员关闭、退回、合并。缺失 → 400「缺少 version 参数」。接单是唯一例外（可选）。
- **XSS 口径是"渲染期转义"**：后端入库**原样存不转义**，前端 `esc()` 是唯一安全出口。别在后端加转义，否则会**双重转义**。

---

## 8. 怎么验证改动没把别的弄坏

项目带了 **20 个 `verify-*.py` 验收脚本 + 1 个总调度 `run-regression.sh`**，都在 **`verify/`** 目录下。

> 🔴 **为什么从 `target/` 挪走了（2026-10-28 的真实教训）**
>
> 这些脚本原来就放在 `target/` 里。`target/` 是 Maven 的构建目录，而 `mvn clean` 的职责
> 就是**把它整个删掉从头再来**。一次发布时跑了 `mvn clean package`，20 个脚本连同
> 80 KB 的接口契约 `doc.txt` 一起被清空；又因为 `target/` 在 `.gitignore` 里，**git 里没有备份**，
> 彻底找不回来了。
>
> **铁律：验证脚本放 `verify/` 并纳入 git。`target/` 只放"删了可以重建"的东西。**

```bash
# 跑全套（需要先启动一个服务实例，端口随便给，别占用你 IDEA 的 8080）
bash verify/run-regression.sh 8117
# 全部通过时最后一行是：REGRESSION OK
```

> 调度器里有一份脚本清单，但**只跑实际存在的文件**（清单里写了、文件不存在的不算失败）。
> 所以往 `verify/` 加脚本，记得顺手加进 `run-regression.sh` 的清单，否则它不会被跑到。
>
> 跑完之后会写一份 `verify/.last-run.json`（记下这次对应哪个 git commit），
> `status.sh` 靠它判断「这份测试结果还算不算数」—— 换了 commit 就会标「不可用于本次验收」。

| 脚本 | 覆盖什么 |
|---|---|
| `verify-health.py` | 健康检查 |
| `verify-user.py` / `verify-blacklist.py` | 登录、禁用、Token 黑名单 |
| `verify-detail-display.py` / `verify-search.py` | 工单详情与列表筛选 |
| `verify-idempotent.py` | 幂等（同一 requestId 重复提交） |
| `verify-flag.py` / `verify-sensitive-word.py` / `verify-sensitive-cache.py` | 敏感词与缓存 |
| `verify-merge.py` / `verify-duplicate.py` | 合并、jieba 重复检测 |
| `verify-statistics.py` / `verify-export.py` / `verify-admin-logs.py` | 管理端统计、导出、日志 |
| `verify-faq.py` / `verify-announcement.py` / `verify-template.py` | 内容管理 |
| `verify-attach-bind.py` / `verify-frontend.py` / `verify-seqid.py` | 附件、前端静态资源、单号生成 |

**改后端必做的事**：改动 → 编译 → 跑全套 → 绿了才提交。

---

## 9. 线上部署（现状）

| 项 | 值 |
|---|---|
| 服务器 | 阿里云 ECS 经济型 e，2 核 2G / 40G / Ubuntu 22.04 LTS |
| 地址 | `<你的服务器IP>:8080`（**没有域名、没有 HTTPS**） |
| 应用目录 | `/srv/second-classroom/second-classroom-0.0.1-SNAPSHOT.jar` |
| 运行用户 | `scapp`（非 root） |
| 环境变量 | `/etc/second-classroom/env`（**600 root:root**，含数据库密码和 JWT 密钥） |
| 服务管理 | `systemctl {start\|stop\|restart\|status} second-classroom`（已设开机自启） |
| 日志 | `/var/log/second-classroom/` |
| 上传文件 | `/srv/second-classroom/uploads` |

**重新部署**（在本机打包后上传）：

```bash
./mvnw clean package -DskipTests
scp target/second-classroom-0.0.1-SNAPSHOT.jar root@<你的服务器IP>:/srv/second-classroom/
ssh root@<你的服务器IP> 'chown scapp:scapp /srv/second-classroom/*.jar && systemctl restart second-classroom'
```

**完整上线/运维手册见 `deploy/DEPLOY.md`（900+ 行）**，包括：安全组、建库、备份、Nginx + HTTPS 怎么加、常见故障排查。

---

## 10. 已知欠账（不是 bug，是"有意识没做"）

| 项 | 影响 | 建议 |
|---|---|---|
| 重复检测的「同义不同词」漏报 | `热水机` vs `热水器` 会被切成完全不同的词，交集为 0，判不出重复 | 属**漏报**（不会误拦），治本要换字符二元组或近义词表 |
| 405 方法不匹配时绕过了拦截器 | 只暴露"路径存在"，**不是鉴权漏洞** | 要修得把鉴权从 Interceptor 挪到 Filter |
| 中文分类 ENUM 在 4 处重复定义 | 改分类要改 4 个地方，容易漏 | 有空收敛成一处 |
| 工单分类是**中文** ENUM | 与"数据库枚举一律英文"的约定不一致 | 历史遗留，改动面大 |
| 微信公众号推送没实现 | 现在只有站内通知 + 浏览器推送 | 契约里**明确写了当前版本不实现**，属设计范围外 |
| 移动端顶栏在 390px 宽会挤压变形 | 站名被截断、「退出」竖排成两行 | 学生多用手机，**建议优先修** |

---

## 11. 接手第一天：按这个顺序做

- [ ] 1. 把项目复制到**无空格**路径
- [ ] 2. 确认 JDK 17 / MySQL / Redis 都在跑；建库建表灌管理员
- [ ] 3. 启动，用 `2023003001 / 123456` 登录，**改掉这个密码**
- [ ] 4. 打开 `requests.http`，从 A0 登录开始，把 A 组跑一遍（能看懂主流程）
- [ ] 5. 跑一次 `bash verify/run-regression.sh 8117`，确认基线是绿的
- [ ] 6. 读你要改的那个接口的 Controller + DTO 注释（`target/doc.txt` 已丢失，见 §5）
- [ ] 7. 动手改 → 编译 → 跑回归 → 再提交

---

## 12. 排错速查

| 现象 | 大概率原因 |
|---|---|
| 启动报 `Could not resolve placeholder 'DB_PASSWORD'` | 用了 `prod` 配置但没设环境变量（`DB_PASSWORD` / `JWT_SECRET` / `UPLOAD_DIR` 三个必填） |
| 启动报 `Communications link failure` | MySQL 没起，或 `application.yml` 里的密码不对 |
| 登录一直 401「账号或密码错误」 | ① 请求体字段名写错（是 **`userId`**，不是 `userNo`/`account`）；② 这个号被禁用了 |
| 接口报 `bad SQL grammar near '?'` | 见坑 3，`jdbcTemplate.query` 少传参数 |
| 返回 `code=0` 但前端拿不到字段 | 见坑 5，getter 名字导致 JSON 字段名变了 |
| 前端页面白屏、控制台 404 | 静态资源没打进 jar；或加了新页面但 `render()` 里没写 `case` |
| 中文日志 grep 不到 | Windows 下 JVM 默认用 GBK 写日志，启动加 `-Dfile.encoding=UTF-8` |

---

## 附：相关文档在哪

| 文档 | 位置 | 内容 |
|---|---|---|
| **接口契约（原真源，已丢失）** | ~~`target/doc.txt`~~ | 2026-10-28 被 `mvn clean` 清掉。改按 Controller/DTO 注释 + `requests.http` 为准（见 §5） |
| **上线运维手册** | `deploy/DEPLOY.md` | 从买服务器到 HTTPS 全流程、故障排查、备份 |
| 建表语句 | `schema.sql` | 13 张表 |
| 初始数据 | `deploy/seed.sql` | 只有 1 个管理员 |
| 账号导入 | `deploy/README-import.md` + `gen-import-sql.bat` | 批量建号 |
| 手工测试集 | `requests.http` | 29 组可点击执行的请求 |
| 自动化验收 | `verify/verify-*.py` + `verify/run-regression.sh` | 接口黑盒验收（正在重建，见 §8） |
