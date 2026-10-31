# 全局约定：第二课堂学时答疑工单系统

> 从 `MEMORY.md` 拆出来的（2026-09-29，为了压 MEMORY.md 的体量）。
> 动鉴权 / 编号 / 健康检查 / 事务相关的代码前，先读这一份。

## 分层与拦截器

- 分层 `dto` → `service`(JdbcTemplate + `@Transactional`) → `controller`(`@RestController` → `Result<T>`)。
- `JwtInterceptor` 拦 `/**`，排除 `/api/auth/login`、`/health`
  **＋ ⑩ 前端静态资源**（`/`、`/index.html`、`/favicon.ico`、`/css/**`、`/js/**`、`/img/**`）；
  只放 `userId`/`userType`/`token`（**无 userName**，要姓名按 userId 查 `t_user`）。
  - 🔴 前端的 HTML/CSS/JS 是**静态资源**，而拦截器是 `/**` 全拦 ⇒ **不放行的话浏览器请求 `/` 会被拦成 401，页面根本打不开**。
    这就是"加前端"的第一个必改项（很容易卡半天）。
  - 放行静态资源 ≠ 开后门：那些文件里没有任何敏感信息，数据全靠 `/api/**`，而 `/api/**` 仍然全拦。
  - **断言要成对写**：每条"放行"断言都配一条"对应 API 仍 401"。只测"页面能开"是不够的 ——
    万一 exclude 手滑写成 `/**`，页面照样能开，但整个系统就裸奔了（`target/verify-frontend.py` 就是照这个思路写的）。
- 用户 id **一律从 Token 取**；标记他人资源 **404 而非 403**。
- 校验顺序统一：**角色 → 归属 → 状态前置 → 乐观锁**。
- 🔴 **乐观锁 `version` 必传的只有 5 个接口**：`confirm` / `close` / `admin-close`（三者共用 `closeOrder`）
  ＋ `return` / `merge`。实现都是 `version == null → 400 "缺少 version 参数"`。
  **`accept` 是唯一例外**（version 可选：不传就不加版本条件，只按 `status='PENDING'` 抢单）。
  - 这几个校验**排在所有写库之前**（铁律 15：入口校验与写库条件一致）。
  - `admin-close` 在这之上另有 **`remark` 非空**（`null` / 空串 / 全空格 → 400「关闭原因不能为空」）；
    ⚠️ **`return` 的 `remark` 不是必填** —— 两者别一起改。
  - 契约 `target/doc.txt` 原先漏写这些（2026-10-11 已回填，见「统一修订项」第 18 条）。
- **每请求查用户状态**：取 Token 后查 `t_user.status` —— 不存在→401「账号不存在，请重新登录」；DISABLED→403「账号已禁用」。**故意不理会 optional**。
- 🔴 **唯一例外**：`/api/auth/logout` 的重复登出。黑名单命中时直接 `writeSuccess` → **HTTP 200 + `code=0`** 并 `return false` 短路（不查库、不写第二遍审计）。**不能拿 `writeJson` 顶替**（它把 HTTP 状态码和 `code` 写成同值，传 200 会拼出 `{"code":200}`）。

## 🔴 JWT 与登录态（⑩ 前端阶段踩出来的）

- ✅ **2026-10-11 已修：Token 带 jti 了**（`Jwts.builder().id(UUID.randomUUID().toString())`）
  ⇒ payload = `exp / iat / sub / userType / jti`，**每次登录都是独一份**。
  - 修的原因：`iat`/`exp` 是 **NumericDate（秒级）**，HMAC 签名又是确定性的
    ⇒ 加 jti 之前，**同一秒内、同一用户、登录两次拿到的是完全相同的 Token 字符串**。
    后果：登出后同秒再登录，新 Token 被 `TokenBlacklist` 当成"已登出的旧 Token" → **401 假红**
    （真实业务几乎踩不到，但**自动化 / 压测会稳定踩到**，表现为时好时坏，极难查）。
  - 实证脚本：`target/probe-token-reuse.py`（修复前：连续登录 8 次只产出 1 个 Token）。
  - **守卫断言在 `verify-blacklist.py` 的 E 组**（连登 5 次 → 5 个不同 Token + jti 互不相同 +
    登出第 1 个后第 2 个照常可用）⇒ 谁把 jti 去掉，E2 / E6 立刻红。
  - 脚本里的 `time.sleep(1.2)` **保留但已降级为防御性**（注释已改写）：跨秒不再是必需的，
    真正兜底的是 E 组断言，别再把 sleep 当成修法。
  - ⚠️ **黑名单 key 仍然是"整个 Token 的 SHA-256"**（没改成按 jti 存）—— 加 jti 已经够用：
    Token 不同 ⇒ 哈希不同 ⇒ 登出只作废"这一个"。改成 jti 反而要处理"老 Token 没 jti"的兼容。
- `JwtUtil`：secret 64 字节 ⇒ **HS512**；`hmacShaKeyFor` 要求 ≥32 字节（`openssl rand -base64 64`）。
- 黑名单查询**先内存兜底、后 Redis**，Redis 挂时不整体 500（代价：多实例时别的机器看不到本机兜底）。

## 三种"读"模式

1. **可选** Token（游客也能看：`/api/faqs`、`/api/announcements`）→ 进 `OPTIONAL_AUTH_PREFIXES`，无/失效 Token **放行但不设属性**（Controller 读 null 当游客）。
2. **需** Token（**`/api/templates`**）→ **绝不能加进可选名单**。
3. `/api/admin/**` → 拦截器管进门、Service 管角色。

名单匹配 `uri.equals(p) || uri.startsWith(p+"/")` ⇒ `/api/admin/xxx` 不受影响。

## 405 与 404 方向相反

- **方法不匹配**（`GET /api/admin/faqs`）→ **HTTP 200 + `{code:405}`，拦截器没跑**
  （`RequestMappingHandlerMapping` 阶段就抛，早于拦截器链）—— 不是鉴权漏洞，只暴露"路径存在"，要修得把鉴权挪到 **Filter**。
- **路径不存在** → 静态资源处理器 → 拦截器正常跑 ⇒ 无 Token 401、有效 Token 才 404。

## 编号生成器 `DailySeqId`

- `next(前缀,表,列,宽度)` = 前缀 + 8 位日期 + 序号（WO / MSG / SLOG / MRG / NOT / OLOG / ATT）。
- `nextGlobal(前缀,表,列,宽度)` = 前缀 + 序号（FAQ / NOTICE / TPL）。
- **用错必主键冲突**（`next()` 对 9 位 ID 截出空串 → MAX 永远 NULL → 每次算 1）。
- 现由 **Redis INCR** 发号；Redis 挂 → 抛 `SeqIdException`（→500）。

## 健康检查 `/health`

- `WebConfig` 已排除拦截器。`redis` 三取值：
  - **UP**；
  - **DOWN**（连不上就如实把整体拉成 DOWN + HTTP 503）；
  - **SKIPPED**（仅当 `--app.health.redis-enabled=false` 关掉探活开关，不拖累 status）。
- DB 用 `Connection.setQueryTimeout(3)`、Redis 用 `spring.data.redis.timeout=2s` + `connect-timeout=2s`；
  HikariCP `connection-timeout: 3000`（默认 30s 会架空探活）。
- 失败返 HTTP 503 + `Result(500,...)`。
- ⚠️ **第一次探活要 3~5 秒**（Lettuce 首连开销；对照：关掉 Redis 探活首次仅 412ms，预热后都是 10ms）
  ⇒ 验证脚本 `target/verify-health.py` 三档（`default`/`redis-down`/`redis-off`）已拆成
  「首次兜底 <8s」+「预热后连打 5 次 <500ms」。

## 🔴 Redis 使用铁律（⑧）

- **一律注入 `StringRedisTemplate`**，别用 Boot 自动配置的 `RedisTemplate<Object,Object>`
  （JDK 序列化 → key 存进去是二进制乱码）。
- **不自己建 `RedisConfig`**（会和自动配置的 `StringRedisTemplate` 类型重叠、注入歧义）。
- 地址只有一份来源：`spring.data.redis.*`（原有的 `app.health.redis-host/port` 已删）。

## 事务与 SQL 写法

- `OperationLogService.record(...)` **故意不加 `@Transactional`**
  （JdbcTemplate 认线程绑定连接 → 自动加入调用方事务，业务回滚时审计也回滚）。
- **登出永不失败**：`AuthService.logout` 里"写审计"那步必须 try/catch（只 `log.warn`）；
  `blacklist.add` 本就有兜底不抛。
- `SELECT ... FOR UPDATE` 必须在 `@Transactional` 内，锁多行按主键升序防死锁。
- 手工 SQL 别用 `SET @u='...'`（collation 冲突），内联字面量。

## 🔴 命令行坑（写验证脚本时踩的）

- **`mvn ... | tail -5` 的退出码是 `tail` 的**，编译失败也显示 0 ⇒ 别用管道判断成败。
  要么重定向到文件后单独 `echo $?`，要么看 `${PIPESTATUS[0]}`。
  （已踩：以为编译过了，其实 `JAVA_HOME` 没设，编译整个失败。）
- **`sed 's/.*:[[:space:]]*//'` 是贪婪的**：从 JSON 里取字段时会把
  `2026-10-31T18:31:27+0800` 削成 `27+0800`。取字段必须**锚定键名**：
  `sed 's/^"[^"]*"[[:space:]]*:[[:space:]]*"\{0,1\}//'`。
- **别用 `git log --format='%m-%d %H:%M'` 显示日期**：`%m` / `%d` / `%H` 都是 git 的占位符
  （boundary mark / ref 名 / 完整哈希），会输出成 `>- (HEAD -> main) 604b33a…:%M`。
  要自定义日期格式，用 `--date=format:'%m-%d %H:%M'`，或者干脆
  `date -d "@$(git log -1 --format=%ct)" '+%m-%d %H:%M'`。

## 数据枚举

- 数据库枚举一律英文；`t_work_order.category` 是中文 ENUM。
