# 模块级业务规则

> **动某个模块之前，先读对应小节。** 这里是业务规则的唯一来源，不依赖仓库外的文件；
> 工程约定见 `CONVENTIONS.md`，环境与部署约束见 `ENVIRONMENT.md`。

## 一、工单主流程
- 三类日志表：`t_status_log`=状态流转；`t_notification_log`=推给谁（通知+红点）；`t_operation_log`=谁干了什么。
- **关闭三入口**共用 `closeOrder(...)`：`/confirm`（提交人，REPLIED，不写 remark）/ `/close`（负责人或 ADMIN，PROCESSING|REPLIED）/ `/admin-close`（仅 ADMIN，PENDING，remark 必填）；均 version 必传、条件 UPDATE（→CLOSED + finish_time + version+1）+ `t_status_log` + SYSTEM 消息；冲突 409 `WO_VERSION_CONFLICT`。**CLOSED 后终态锁定**（禁重开/回复/修改/接单/合并）。
- **通知只写不发**：INSERT `t_notification_log`（PENDING/PLATFORM/WORK_ORDER/is_read=0；`title`/`channel` NOT NULL 要自补）。路由 NEW_TICKET→所有 STAFF/ADMIN、ACCEPT/REPLY→提交人、FOLLOW_UP→handler、CLOSE→对方、MERGE→子单提交人（`target_id`=主单号）。系统消息只在 ACCEPT/三关闭/MERGE 发。
- **两种红点并存**：`t_notification_log.is_read` 与 `t_message_read` 有无记录；`messages/read` 同事务清两种。未读口径 = 我能看的单 + 非自己发的 + SYSTEM 也算。
- `/return`（方法名 `returnOrder`）：PROCESSING→PENDING，**handler_id/handler_name 清 NULL**、不写 finish_time；**不写通知**、不动未读（ENUM 无 RETURN 值）；退回后原干事再点 403（验 409 用 ADMIN）。
- `/re-consult`：基于 CLOSED 老单**另开新单**（老单不动），`source_order_id`+`relation_type='RE_CONSULTATION'`，**无 version**，仅提交人本人；老单非 CLOSED→409、同老单 7 天内重开→429；幂等指纹 = category+content+attachmentIds。⚠️ 审计写 `RE_CONSULTATION`，但 `t_status_log.action` 写 `'SUBMIT'`（ENUM 没有）。
- **审计 12 埋点**：LOGIN/LOGOUT/CHANGE_PASSWORD + SUBMIT/ACCEPT/REPLY/FOLLOW_UP/CONFIRM/MANUAL_CLOSE/ADMIN_CLOSE/RETURN/MERGE/RE_CONSULTATION，**只在操作真正生效后记**；MERGE 操作人取**执行合并的人**。⚠️ 改密码测试要存哈希/写回（学生哈希 `$2a$10$MYD8338x8UBTV6Uxpbm0z.H4NysTsPPeBxZUkWT1qiswrLErea8ie`）。登出后同 Token 立刻 401。
- 取 IP：`util/WebUtil.getClientIp()`（XFF 首个→X-Real-IP→Proxy-Client-IP→WL-Proxy-Client-IP→remoteAddr）。
- **M 异步通知派发**（无 HTTP 接口）：`dispatchPending()` 每 10s 扫 PENDING；成功→SUCCESS，失败未用尽→**留 PENDING**，用尽→FAILED；首次立即、之后 30/60/120s = **最多 4 次尝试**。⚠️ `send_time`=**最后一次尝试时间**；派发方法**故意不加 `@Transactional`**；抢占式 `UPDATE ... WHERE status='PENDING' AND retry_count=?`（affected=0 跳过）。`BROWSER`/`WECHAT` 未实现→走失败重试（验重试的抓手）。
- **`/merge`（#15 合并工单，干事/管理员）**：把「URL 里的子单」并入「body 里的主单」，子单进 `MERGED` 终态。
  ⭐ **只动子单，主单一动不动**（`status` / `main_order_id` / `version` 全不变）—— 已由 `verify-merge.py` A11~A13 钉死。
  - 两行 `SELECT ... FOR UPDATE` **按 `work_order_id` 升序**加锁（防 A→B、B→A 死锁）；`subFirst = 子单号 <= 主单号`。
  - **实测校验顺序**：入参(400) → 角色(403) → 规则0 自合(400 `WO_CANNOT_MERGE_SELF`) → 查操作人 → 加锁(404，报**具体哪个号**) → 干事越权(403，`handler=我 OR PENDING`，子单主单都要满足) → 子单状态(409) → 规则1 子单 `main_order_id` 须 NULL → 规则2 主单 `main_order_id` 须 NULL → 主单非 MERGED → 规则4 关系表无记录 → 乐观锁 UPDATE(409 `WO_VERSION_CONFLICT`) → INSERT 关系(`DuplicateKeyException` **必须 throw**，见铁律 1)。
  - 🔴 **主单可以是一张已 CLOSED 的单**（契约只挡 MERGED，实测**能合**）。2026-10-10 用户拍板**保持现状**，前端给提醒。
  - 副作用：`t_merge_relation` 1 条（`UNIQUE(sub_order_id)` 兜底）+ `t_status_log.action='MERGE'` + 审计 `MERGE`（**target_id=子单号**、操作人=**执行合并的人**，不是子单提交人）+ 两条 SYSTEM 消息（子单「工单已合并至 {主单}」/ 主单「工单 {子单} 已合并至此」）+ 通知子单提交人（**`target_id`=主单号**、`work_order_id`=子单号、title「您的工单已合并」）。**`is_repeat` / `duplicate_of_order_id` 不碰**。
  - ⚠️ **没有幂等**：请求体只有 `mainOrderId` + `version`（见 `WorkOrderMergeRequest`），塞 `requestId` 会被**完全忽略**。
  - 合并后子单锁死：`reply` / `close` / `admin-close` / `accept` 四种全 **409**。
  - 回归：`verify/verify-merge.py`（**62 条**，2026-10-10 补 —— 此前这个接口**从没进过回归**）。探查脚本 `（该探查脚本已丢失）`。

## 二、N 附件
- `POST /api/upload`（5MB + **Magic Number** 校验 + 图片长边 ≤1920px）。`GET /api/files/{id}` 是**文档没有的**、**第一个"成功时不返 `Result`"**的接口（文档假设用 OSS）。
- 类型放宽为 图片+PDF+Office+压缩包（用户确认，**唯一功能性偏差**）；ENUM 只有 TEMP/BOUND，**不实现 EXPIRED**。
- 绑定四入口 submit/reConsult→WORK_ORDER、reply/followUp→MESSAGE；校验序：不存在 400 → 已绑定 400 → 非本人 **403** → 超 7 天 400。
- `AttachmentItem` 工单级/消息级共用（**空给 `[]`**）；`canViewWorkOrder(...)` **三处共用**；`loadMessages` 用**一条 JOIN**（防 N+1）。
- ⚠️ 文档错误码是「HTTP码+两位序号」(40902)，项目**全量短码**，既定偏差不改。

## 三、P FAQ
- `/api/faqs` 公开读 + `/api/admin/faqs` 写。**未发布对非 ADMIN 一律 404**；`view_count` 只在详情、权限过后 +1；PUT **部分更新**（null=不动，空白=400）；DELETE 软删除。
- keyword 用 `MATCH(question,keywords) AGAINST(? IN NATURAL LANGUAGE MODE)`（ngram）；⚠️ **关键词 <2 字搜不到**。
- ✅ **游客（不登录）可读**（2026-10-10 起，跟公告同一轮放开）：`/api/faqs` 在 `OPTIONAL_AUTH_PREFIXES` 里，
  列表和详情**都不需要 Token** ⇒ 游客能搜、能点开看答案（列表故意不给 `answer`，详情才给）。

## 四、S 公告
- `/api/announcements` 公开读 + `/api/admin/announcements` 写，编号 `NOTICE+6位`→`nextGlobal`。
- 归档 = 软删除 ARCHIVED；**过期 ≠ 删除**（`expire_time` 过了只是不入列表，NULL=永久有效）。`expireTime` 在 DTO 里是 **String 自己解析**，格式错 400。补了文档没有的 `?status`（仅 ADMIN）。
- ✅ **游客（不登录）可读**（2026-10-10 起）：后端 `JwtInterceptor.OPTIONAL_AUTH_PREFIXES` 含 `/api/announcements`
  ⇒ 不带 Token 也 `code=0`；游客只拿**已发布未过期**的，条数 ≤ 管理员。前端 2026-10-10 同步放开（见 §十一 第十二步）。

## 五、T 敏感词
- `/api/admin/sensitive-words` **仅 ADMIN**；⚠️ **表名单数 `t_sensitive_word`**（接口路径按文档保持复数）。
- 定位：**不是提交闸门**，是打 `is_flagged` 标记配合 #50 复核。去重 `INSERT IGNORE`；⚠️ `word` 有 UNIQUE → 插入被忽略时若 DISABLED 就**恢复 ACTIVE**。列表**照文档不分页、不过滤 status**。
- ✅ **打标已接**：`SensitiveWordService.hitsSensitiveWord(content)`；生效**四处 = submit / reConsult / reply / followUp**（后两个是 2026-10-11 补的）；命中就 `is_flagged=1`，**绝不拦截、响应里也不提示**。
- 🔴 **reply / followUp 的标记落在「工单」上** —— `t_work_order_message` **没有** `is_flagged` 列，所以语义是"这张单里的任何内容命中过"，
  **单向**（为 1 后不清回 0，是历史事实）。实现是"**写库前**只读地算结果 → 业务写入成功后再 `UPDATE t_work_order SET is_flagged=1`"。
  契约 #9/#10 第 6 步原本只写了「敏感词检测」四个字、**没写命中后怎么办** ⇒ 2026-10-11 按用户拍板补齐
  （口径：标记打在工单上 + 放行不拦截）并回填契约（`doc.txt` 统一修订项第 19 条）。守卫 = `verify-flag.py` **F 组**（10 条）。
  - 老实现 = SQL `WHERE status='ACTIVE' AND ? LIKE CONCAT('%', word, '%')`（contains 语义、无词边界）；
    **⑧⑤ 起改为「取词表 → 内存 contains」**（含英文大小写不敏感，见 §十）。

## 六、U 模板
- `/api/templates` 读 + `/api/admin/templates` 写，编号 `TPL+4位`→`nextGlobal`。列表只返 ACTIVE，补 `?status=DISABLED|ALL`；禁用可复活 `PUT {"status":"ACTIVE"}`。

## 七、管理端（①~⑦ 全部完成）
- **分页**统一 `dto/PageResult<T>{total,page,pageSize,list}`+`PageResult.of(...)`；page 默认 1、pageSize 默认 20、**上限 50**；排序 `ORDER BY 时间 DESC, 主键 DESC`（防同秒翻页重复/漏）。早期 `WorkOrderListResult` 字段相同但**不改**。
- **日期区间**：`>= start 00:00` 且 `< end+1天 00:00`（截止日整天都算）。⚠️ 文档的 `BETWEEN ? AND ?` 会把截止日整天丢掉，**必须偏离文档**。`t_operation_log.operation_type` varchar(50) → 筛选**不校验取值**，传不存在的返回空列表。
- **④用户管理 #34~#36** `/api/admin/users`：**不能改自己**的状态→400（防自锁）；重置密码默认 = **学号后 6 位**、响应不带明文，审计 target_id=目标 userId。**禁用即时生效**，但**重置密码不让已发 Token 失效**（JWT 无状态）。`requests.http` V1~V11；⚠️ 学生 Token 变量名是 `{{token}}`。
- **⑤工单搜索 #50** `/api/admin/work-orders/search`：`WorkOrderSearchItem`（**严格 15 字段**，不复用学生端 DTO）+ `WorkOrderSearchQuery`（13 参数载体；日期/布尔**声明成 String** 自己解析）。9 维度 + 2 开关（`isFlagged`/`isDuplicate` 双向）。⚠️ statusLabel 统一「已关闭」；**不 LEFT JOIN t_user**（本表已冗余 `user_name`）。
- **⑥统计 #45/#46** `/api/admin/statistics/overview` + `/staff-workload`：两个分布**永远给满 5 个键**（=0 也在）；总量 0 时 `duplicateRate=0`（不除零）、平均值 0（不 null）。`duplicateRate` = `duplicate_of_order_id IS NOT NULL`/总量（2 位小数）；`avgResponseMinutes`=create→reply（**只算回复过的**）、`avgCloseMinutes`=create→finish（**只算已完结的**）。`staffWorkload` 返**数组**（无分页），其 `avgCloseMinutes`=受理→完结（只算已完结那些单）。
- **⑦导出 Excel #47** `/api/admin/export/work-orders`：pom 加 **`poi-ooxml` 5.4.1**（⚠️ BOM **不托管** POI，`<version>` 必须自己写；`poi-ooxml` 才带 `XSSFWorkbook`）。15 列同 #50（状态给中文+代码两列）；null 写**空单元格**；**第二个"成功返文件不返 Result"**的接口。筛选**复用 #50**（抽出 `buildFilter`+`listForExport`）。审计 `EXPORT`/`WORK_ORDER`/`"BATCH"`/content=`work_orders: ... rows=N`；**参数错与 403 都不写审计**。⚠️ 无条数上限，几万行要换 SXSSFWorkbook。

## 八、故意没做 / 技术债
- ✅ **「回复」「追问」的敏感词检测已补**（2026-10-11）—— 用户拍板：**标记打在工单上 + 放行不拦截**，详见 §五。
- ✅ **§4 第 5 步 jieba 重复检测已补**（2026-10-14）—— 7 条口径（未完结三态 / 平手取最新 / 停用词+单字+短ASCII / 内容<8跳过 / 池 LIMIT 200 / **交集≥2 且 交集÷新单词数≥40%** / 吞异常绝不拦提交），只提示不拦截。`duplicate_of_order_id` 由恒 NULL → 真实写入；下游三处（提交返回体 `isDuplicate`、#50 `isDuplicate` 筛选、统计 `duplicateRate`）**从"死的"变"活的"**。验证 `verify/verify-duplicate.py`（**54 条，自校验 oracle 口径**）。契约回填 `doc.txt` 统一修订项**第 21 条**。
  - 🔴 **口径本质 = 比"字面共享多少实词"，不是"是不是同一件事"**。→ 已拍板「暂不修」的**已知限制**：`热水机` 切成 `热水+机坏`、`热水器` 切成 `热水器` ⇒ 同义反而**交集 0**（属**漏报**，不会误拦）。治本要换字符二元组或补近义词表，成本高且 54 条断言全要重验。详见 `doc.txt` 第 21 条末段。
  - ⚠️ **写 requests.http 的 D 组时踩过**：我按"我觉得像"写断言，实际算法按"实词共享"判 —— **同一坑第 7 次**。D 组的近义对必须**换虚词、不换实词**（`连续三天`对`连着三天`、`扣款了`对`把钱扣了`）。
- ✅ **XSS 口径已定**（2026-10-14 用户拍板）：**渲染期转义**，后端**刻意不做**任何入库转义 ⇒ 见 §十一「XSS」小节。
- ✅ **已修**（⑩ 后续欠账 A2，2026-10-10）：`POST /api/work-orders/{id}/follow-up` 的状态校验**已收严到只允许 `REPLIED`**。
  ⚠️ **这条以前记错过**：早先写成"实现里没有状态校验"，那是**错的** ——
  修之前实现**确实有**校验，只是覆盖范围不同：
  ```
  契约：status 必须 = 'REPLIED'，否则 409
  收严前（老行为）：挡 PENDING（409「该工单尚未受理，暂不能追问」）
                    挡 CLOSED / MERGED（409「该工单已关闭或已合并，不能追问」）
                    **放行 PROCESSING 和 REPLIED**（← 分歧点）
  收严后（现行）：  只有 REPLIED 放行；PROCESSING → 409「该工单还在处理中，请等干事答复后再追问」
  ```
  「缺校验」和「校验覆盖过宽」的修法完全不同 —— 前者要新加 if，后者是**把 PROCESSING 从白名单里去掉**。
  收严前的实测表现：PROCESSING 的单能连续追问 3 次、version 一路 3→4→5（学生能刷屏）。
  ⚠️ 两个**必须同时改**的地方（只改一处等于没收严）：
    ① 第 5 步入口校验 `if (!"REPLIED".equals(status))`；
    ② 第 9 步乐观锁 `UPDATE ... WHERE work_order_id=? AND status = 'REPLIED'`
       （原来是 `IN ('PROCESSING','REPLIED')`）。
    这条 UPDATE **没有 version 条件**，状态条件就是它唯一的并发闸门 ——
    不一致的话，两个并发追问会在第一个把状态改成 PROCESSING 之后**都成功**，收严被绕过。
  ⚠️ **`reply` 的宽松条件故意保留**（`AND status IN ('PROCESSING','REPLIED')`）：
    两个接口**有意不对称** —— 干事补充回复不改变轮次，学生追问会开新一轮。
    ⇒ 以后搜 "IN ('PROCESSING', 'REPLIED')" 会在 reply 里搜到，**那不是漏改**。
  前端照旧只在 REPLIED 显示追问入口（两者现在一致了）。
- ⚠️ **`POST .../reply` 契约 vs 实现两处不符**（⑩ 第八步读源码核实，**都不是 bug，是文档老化**）：
  ① 请求体**没有 `version` 字段** —— `WorkOrderReplyRequest` 只有 `content/requestId/attachmentIds`，
     实测传 `version: 999` 照样 `code=0`，字段被完全忽略。
  ② **状态校验比契约宽** —— 契约写"status 必须 PROCESSING，否则 409"，
     实现是 `UPDATE ... WHERE status IN ('PROCESSING','REPLIED')`，源码注释原话
     「干事对同一条单补充回复时，before 其实已经是 REPLIED 了」⇒ **故意允许补充回复**。
     只挡 `PENDING`（「请先受理」）和 `CLOSED/MERGED`。
- ⚠️ **`POST .../return` 的 `remark` 后端不校验**（⑩ 第八步实测）：不传就是 `remark=null`，
  系统消息退化成光秃秃一句「XX 退回工单」。契约没写必填，所以**前端自己强制要求填**
  （学生在时间线里只看得见这句理由）。同类：`accept` 的 `version` **可选**（不传就不加版本条件），
  但版本不匹配时它报的是「该工单已被受理或状态不允许受理」而不是「版本冲突」
  —— 两种失败在实现里合并成同一句话。
- 🔴 **`POST .../re-consult` 的「7 天 Redis 锁」在实现里根本不存在**（⑩ 第九步实测 + 读源码）：
  契约 §4 第 6 步整段写的是 `Redis SET re-consult:lock:{原单号} NX EX 604800` + 「锁残留要主动 DEL」
  + 「DB COUNT 才是最终事实」—— 实际实现**只有后半句**：纯 DB 查询
  `WHERE source_order_id=? AND create_time > DATE_SUB(NOW(), INTERVAL 7 DAY) LIMIT 1 FOR UPDATE`，
  一行 Redis 都没碰。这反而是**更好**的写法（把"锁残留拦截正常请求 7 天"这个坑直接绕掉了）。
  ⇒ 契约那段可以作为"历史设计"读，别看代码时找不到锁就以为漏了。
  另外实测：该接口 `requestId` **不传也能建单**（只是失去幂等保护），前端当然要传。
- 🔴 **`Result.fail(429, ...)` ⇒ HTTP 仍是 200**（全站唯一的 429 = 再次咨询 7 天限制）
  ⇒ 前端只能按 `code` 分流；写成 `if (r.httpStatus === 429)` 永远不成立。
- 🔴 **`POST .../admin-close` 契约 vs 实现 4 处不符**（⑩ 第十步实测，探查处方 `target/probe-adminclose.py`）：
  ① **校验顺序和契约相反**：契约把「校验 role = ADMIN」写成第 2 步、状态校验第 4 步；
     实现真实顺序是 **version → remark 非空 → 角色 → 查单 → 归属 → 状态 → 乐观锁 UPDATE**。
     直接后果：**学生不带 remark 去调，拿到的是 `400「关闭原因不能为空」`，不是 403**（实测）。
     同理"单号不存在 + 不带 version"报的是 400 而不是 404 —— version 校验连库都不查。
  ② 契约写 `409 WO_STATUS_INVALID`，实现返回的是**纯中文**「管理员只能关闭待接单的工单」，
     **没有错误码常量**。而且 admin-close 的 409 有**两种正文完全不同**的文案：
     状态不符（上面那句中文）vs 版本冲突（`...（WO_VERSION_CONFLICT）`）。
     前端两种都用同一套善后（`reloadAfterConflict()` 重载最新状态），因为正确反应都是"刷新再决定"。
  ③ 契约**完全没提**写审计日志 `t_operation_log`，实现里写了（`operationType = 'ADMIN_CLOSE'`，
     复用 `action` 字面量）。
  ④ 契约没写通知的 title / priority，实现是 `title='您的工单已关闭'`、`priority='P1'`。
  ⑤ `admin-close` 只允许 `PENDING`；**被干事退回的单（PENDING + handler 已清空）也能被它关掉** ——
     实测成功、version +1，这是真实场景（退回来没人管，管理员一刀切掉）。
     它**不会**顺手写 handler（handlerId/handlerName 全程保持空）。
- ✅ **已修**（⑩ 后续欠账 A1，2026-10-10）：`remark` 长度上限 = **500 字**，超长返回 **400**（不再是 500）。
  背景：理由要写进 `t_status_log.remark`，那一列是 **`VARCHAR(500)`**
  （`t_work_order_message.content` 与 `t_operation_log.operation_content` 都是 TEXT，
  所以**整条写入链上最窄的一环就是 status_log.remark**）。
  修之前后端零校验，实测传 660 个字 → `code=500`「数据库操作失败」+ **整条事务回滚**
  （单子状态、version 都没变，数据没脏）。
  ⇒ **一个用户随手粘贴长文本就能把接口打成 500**，这是必须堵的洞。
  修法：`WorkOrderService` 新增常量 `REMARK_MAX = 500` + 只读私有助手
  `remarkTooLong(label, remark)`，在**任何写库之前**调用（铁律 1）。
  ⚠️ 调用点有**两个**（故意扩大到了三处入口）：
    · `closeOrder` → 一次覆盖 `confirm` / `close` / `admin-close`
    · `returnOrder` → 覆盖 `return`
    理由：它们写的是**同一列**，只堵 admin-close 等于假安全。这不改变任何合法输入的行为
    （≤500 字的理由照常成功），**用户已同意**这个扩大。
  ⚠️ 边界必须是 `> 500` 才拦，**不能写成 `>= 500`** —— 500 是合法值
    （实测 500 字成功、501 字 400）。
  ⚠️ 前端输入框仍是 `maxlength=200`，这**没有**跟着改，是故意的：
    后端守「能不能存进库」（客观容量），前端守「好不好填」（体验值），两层各管一段。
    **跨层断言已写进验证脚本**：前端 200 ≤ 后端 500。
  ✅ **契约已回填**（2026-10-10，用户拍板的待办第 1 项）：`target/doc.txt` 的
     #13 `admin-close` 与 #14 `return` 两处都补上了「remark ≤ 500 字符（`String.length()`）」，
     并把长度校验作为**第 2 步**写进后端处理流程（在 version 之后、角色/查单之前，**与实现顺序一致**），
     后续步骤编号顺延（admin-close 到 9、return 到 8）；「历次修订说明」加了第 17 条。
     ⚠️ 当时只回填了**长度**这一条。
  ✅ **两处遗留偏差已于 2026-10-11 全部补全**（用户拍板"一次补全 6 个接口"）：
     · `admin-close` 的「remark 非空 → 400」已写进 #13（并写明三步前置校验顺序 version→长度→非空）；
     · 「version 必传 → 400」实际**不止 #13**：`confirm`/`close`/`admin-close`（共用 `closeOrder`）
       + `return`/`merge` 共 **5 个接口**都有，已逐个补进契约；
       #8 `accept` 的 version 是**可选**（唯一例外），也在其段落写明 —— 免得读文档的人以为一个规矩。
     `target/doc.txt` 50870 → 52603 字符（+1733），「统一修订项」第 **18** 条记录本次回填。
- ⚠️ `accept / close / return / admin-close` 的 `version` 传**字符串数字**（如 `"0"`）
  Jackson 也能转成 int → 照常成功（实测）。前端不该这么发，但知道后端容得下。
- 🔧 **幂等覆盖范围**：只有 `submit / reply / follow-up / re-consult` 四个接口套了 `IdempotencyStore`；
  **`accept / close / return / confirm / admin-close / merge` 都没有**（前端因此不给它们发 `requestId`）。
  且指纹 = `SHA-256(userId + 接口名 + 单号 + 正文)` ⇒ **同 requestId 必须同内容**，
  换内容复用同号 → `409 IDEMPOTENCY_CONFLICT`（实测过）。`re-consult` 的 Service 层
  还**额外**做了一层"数据库版幂等"（查 `t_work_order.request_id`，指纹比的是
  原单号 + 分类 + 内容三者全一致）—— 两层都拦得住。
- 被 MERGE 合并的单填 **`main_order_id`**，不是 `duplicate_of_order_id` —— #50 的 `isDuplicate=true` 查的是**自动去重标记**，两者不是一回事。
- 🔧 技术债（**已评估，建议不动**）：中文分类列表现 **4 处**重复
  （`WorkOrderService` / `FaqService` / `TemplateService` / `AdminStatisticsService`）；
  `STATUS_LABEL` **2 份**（`WorkOrderService` / `AdminWorkOrderService`）。
  ⚠️ `AdminWorkOrderService` 第 53 行的注释写明这是**故意重复**：要共用就得抽公共常量类，
  而那要**动已经验证过的 `WorkOrderService`** —— 按"动已验证代码的风险大于收益"这个既定原则，先各留一份。
  ⇒ **这不是"漏做的事"，是有意识的暂缓**。别把它当欠账去改，除非用户明确要求。
  （顺带：那条注释还记了「文档 §10.7 把 CLOSED 叫『已完结』，项目里统一叫『已关闭』，跟随项目现状」。）

## 九、幂等 requestId（⑧③，文档 §1.11）
- 作用范围：**四个写接口** —— 提交工单 / 回复 / 追问 / 再次咨询（请求体里 `requestId` **选填**，
  不传就退化成老行为，老客户端不受影响）。
  ⚠️ **其它写接口都没有幂等**：`accept / confirm / close / admin-close / return / merge` ——
  往它们请求体里塞 `requestId` 会被**完全忽略**（DTO 里根本没这个字段，Jackson 不报错）。
  前端**不要**给这些接口传 `requestId`，否则会误以为"重放安全"。
- 实现在 **Controller 层**（`IdempotencyStore.run(...)`），不是 Service —— 结果缓存必须在事务提交后写（铁律 14）。
- Redis 两个 key：`idempotent:{requestId}`=PROCESSING **EX 60**；
  `idempotent:{requestId}:result`=`{"fp":指纹,"data":首次结果}` **EX 3600**。
- **请求指纹** = SHA-256(userId + 接口名 + 工单号 + 参数)，`\u001F` 连接（用户正文里可能有 `|`）。
  同 requestId 不同指纹 → **409 IDEMPOTENCY_CONFLICT**（前端复用 ID 的 bug，绝不重放）。
- 行为：抢到锁的正常干活 → 成功写结果缓存；**业务失败或抛异常一律放开锁**（否则改好参数重试被自己挡 60 秒）。
- **DB 兜底**（Redis 不落盘，重启即丢）：`submit` 撞 `UNIQUE(request_id)` 时走
  `recoverSubmitByRequestId` → 查原单比对「谁+分类+正文」→ 一致**返回首次结果**（不是 409）、
  不一致 409、查不到退回 409 请勿重复提交。reply/followUp 撞 UNIQUE 仍返回 409（消息表没做重建，可接受）。
- ⚠️ Redis 挂时这四个接口直接 500（消息「幂等校验失败：Redis 不可用…」），**不静默降级** ——
  降级等于偷偷放行重复提交。
- 手工验证：`requests.http` 的 **E 组**（E1 提交 → E2 同参重放单号相同 → E3 换内容 409 → E4 新 ID → E5 不传）。
  自动验证：`verify/verify-idempotent.py <端口> default|redis-down`。

## 十、⑧ Redis 其余细节（敏感词缓存 / 登出黑名单 / 单号 / 健康检查）
- **敏感词缓存**（⑧⑤，`SensitiveWordService`）：key `sensitive_words:cache`，Redis **SET**，成员 = 所有 ACTIVE 词，
  **TTL 30 分钟**（兜底）。`hitsSensitiveWord` = `loadActiveWords()` → 内存 `contains`。
  - `loadActiveWords()`：`SMEMBERS` 命中（非 null 且非空）直接用；未命中 → `queryActiveWords()` 查库 + 回填（`SADD`+`EXPIRE`）；
    **Redis 异常 → catch → 直接查库**（副本丢了不算故障）。空词表**不缓存**（Redis 存不住空集合，会自动删）。
  - ⚠️ **大小写不敏感要自己保住**：两边 `toLowerCase(Locale.ROOT)`（`ROOT` 防土耳其语 `I`→`ı`）。
    老实现靠表排序规则 `utf8mb4_unicode_ci` 自动做到 —— `verify-flag.py` B3 覆盖这条。
  - **失效 = 删缓存**（不是文档写的"重建"）：三个写方法都在 `@Transactional` 里，重建会喂进**未提交**数据；
    删掉则下次读从库重装，**永远安全**。且**挂在 `afterCommit`**（`TransactionSynchronizationManager`）——
    否则"事务没提交时别的请求重装了旧数据 + 30min TTL"这个窗口会让缓存错到 TTL 到期。
  - 三个失效点：`addWord`（`outcome != EXISTS` 时）、`batchAdd`（`added > 0` 时）、`disableWord`（总是）。
  - 验证：`verify/verify-sensitive-cache.py <端口> default|redis-down`（default 28 / redis-down 5）。
    三条"看得见实现"的断言：把库改脏但不动缓存 → 仍判命中（证明读缓存）；禁用后 key 立刻消失 + 紧接提交不命中（立即生效）；
    把 cache key 写成 STRING（WRONGTYPE）→ 仍成功且判定正确（降级生效）。
- **登出黑名单**（⑧④，`common/TokenBlacklist`，接口 `add`/`contains` 没变）：
  `SET blacklist:token:{SHA-256(token)} 1 EX <Token 剩余寿命>`；内存兜底 `Map<哈希, 绝对过期毫秒>`（同样 TTL，
  超 1000 条顺手 sweep 一次过期的）；`contains` **先内存后 Redis**；Redis 异常 → 日志**节流 30 秒**（拦截器每请求都查，不节流会刷屏）后继续放行（只信内存）。
  `JwtUtil` 新增 `getRemainingSeconds(token)`（解析 `exp`，已过期/坏 Token 返 0）、`getAlgorithmName()`。
  ⚠️ `jwt.secret` 是 **64 字节 ⇒ 实际算法 HS512**（老注释写 HS256 是错的，已改）。
  🔴 **登出永不失败 + 重复登出幂等**（2026-09-28 定）：`AuthService.logout` 里"写审计"那一步
  **必须 try/catch**（只 `log.warn`，不抛）—— 审计**行**写 MySQL，但它的 `log_id` 要走
  `DailySeqId.next(..., "OLOG", ...)` 发号，**Redis 一挂就抛 `SeqIdException` → 500**。
  只包黑名单写入是修不掉的（`blacklist.add` 本就有兜底、不抛）。
  理由：登出的本质是"客户端丢掉 Token"，服务端拉黑只是防 Token 被截获的保险 ——
  它是系统里最不该失败的动作，报 500 会让用户以为"没退出去"（其实早退干净了）。
  代价：Redis 挂的这段时间登出会少几条审计，`/health` 会如实报 DOWN/SKIPPED 让运维知道。
  **重复登出**由 `JwtInterceptor` 兜：黑名单命中时对 `LOGOUT_URI = "/api/auth/logout"`
  直接回 `writeSuccess(...)` → **HTTP 200 + `code=0`**（⚠️ 不能拿 `writeJson` 顶替！它把 HTTP 状态码
  和 `code` 字段写成同一个值，传 200 会拼出 `{"code":200}`）并 `return false` 短路 ——
  **不查库、不写第二遍审计**。理由：拿已作废的 Token 再作废一次结果相同，本来就该成功；
  回 401 属于"用错误码把已达成目标报成失败"。
  手工验证：`requests.http` **B 组**（B4 已改为期望 `code=0`）；
  自动：`verify/verify-blacklist.py <端口> default|redis-down`（**19 + 6 条**）。
  redis-down 档靠 `--spring.data.redis.port=6399`（无人监听）模拟，
  断言「不全站 500 / **登出依然成功**但会话确实被终止（内存兜底）/ 审计行不增加」。
- **手工看 Redis 的小工具**：`python target/redis-cli.py [-h 主机] [-p 端口] <命令>...`（纯标准库手搓 RESP，本机 redis-server 没带 redis-cli）。
- **单号 Redis key**：`work_order:seq:{yyyyMMdd}`、`faq:seq`（文档写明）；同风格补齐
  `work_order_message / status_log / merge_relation / notification / operation_log / attachment` 的 `:seq:{date}`、
  `announcement:seq`、`template:seq`。当天 key **TTL 48h**、全局 key 不过期。
- **播种**：首次用某 key 时先查库 `MAX(序号)` 作起点 → `SETNX` 写入 → 再 `INCR`
  （并发安全：SETNX 只有一个成功）。**Redis 不落盘 ⇒ 重启靠重新播种不会重复发号**。
- **不静默降级**：Redis 挂直接抛 `SeqIdException` → 500，消息里带「单号生成失败 + key + 去启 Redis + 底层原因」。
  降级到 `MAX+1` 既并发不安全，又会与计数器**分叉**（恢复后发出已用过的号）。
- **`/health`**：`redis` 三态 **UP / DOWN（拖累整体 → HTTP 503）/ SKIPPED（仅 `--app.health.redis-enabled=false`）**；
  DB `setQueryTimeout(3)`、Redis `timeout`+`connect-timeout=2s`、HikariCP `connection-timeout:3000`。
  ⚠️ **首次探活 3~5 秒**（Lettuce 首连；关掉探活首次 412ms、预热后 10ms）⇒ `verify-health.py` 三档。
- 验证脚本：`verify-health.py`（三档）、`verify-seqid.py`（default / redis-down）、`verify-blacklist.py`（default / redis-down）。
- **回归运行器**：`bash target/run-regression.sh <端口> [脚本.py ...]`（不传脚本名 = 全套 **19 个**，含 powershell-free 的 start/wait/stop；
  逐脚本输出落 `target/out-<脚本名>.txt`，末尾打印 `REGRESSION OK` 或失败清单）。
  ⚠️ 一轮全套约 2~3 分钟；**被打断过两次**（症状：Exit Code 1 + SIGTERM + 空输出，日志只跑到一半）。
  处理：`sb.sh ps <端口>` 查残留（一般没有，脚本自己收尾了）→ **补跑剩下的脚本**即可，别整轮重来。

## 十一、⑩ 前端（2026-09-29 起：纯静态 HTML + 原生 JS）

**选型**（用户拍板）：纯静态 HTML + 原生 JS + **响应式（手机也要好用）**；范围 = **学生端 + 干事端**。
**零构建**：没有 npm / vite / webpack。用浏览器原生 ES 模块 `<script type="module">` ——
改完直接刷新浏览器就生效，Nginx 一行都不用改（产物就在 jar 里）。

**文件**（`src/main/resources/static/`，由 Spring Boot 直接托管）：

| 文件 | 作用 |
|---|---|
| `index.html` | 唯一页面骨架：只有 `<div id="app">` + `type="module" src="js/app.js"` |
| `css/app.css` | 唯一一份样式；颜色/圆角收在 `:root` 变量；**手机优先** + `@media (max-width:640px)` |
| `js/api.js` | **唯一跟后端说话的地方**（≈ 前端版 service 层）：Token 存取 + fetch 封装 + 各接口函数 |
| `js/app.js` | 总控（≈ controller + view）：hash 路由 + 每个页面一个 `viewXxx()` + 渲染 |

**单页 + hash 路由**（`#/login`、`#/password`、`#/`…）：地址一变就 `render()` 重画 `#app`。
好处：登录态、顶部条、未读红点只写一次，不用在多个 HTML 之间粘 layout。
加新页面三步：写 `viewXxx()` → `render()` 里加分支 → 导航加链接。

🔴 **四条铁律**：
1. **必须放行静态资源**（详见 `CONVENTIONS.md` 拦截器小节）—— 否则浏览器请求 `/` 直接被拦成 401。
2. **业务失败时 HTTP 仍是 200** ⇒ 前端必须**两层都判**：先 `resp.ok`、再 `json.code === 0`。
   只看 `resp.ok` 会把所有业务错误当成成功（`verify-frontend.py` 有专门一条断言钉这个）。
3. **附件下载不能直接 `<a href="/api/files/{id}">`**：那个接口要 Token，而浏览器跳链接**不会带
   Authorization 头**，必然 401。必须 `fetch` 成 blob → `URL.createObjectURL` → 触发下载
   （已备好 `api.requestBlob()` + `api.saveBlob()`）。
4. **上传时不要手写 `Content-Type`**：`FormData` 交给浏览器自己加 multipart 的 boundary。

**其它约定**：Token 存 `localStorage`（key `sc_token`）；"该改密码"标记存 `sc_need_pwd`；
所有用户可见文本过 `esc()` 转义（工单内容/姓名是用户填的，直接塞 HTML 就是 XSS 口子）；
输入框 `font-size:16px`（<16px 时 iPhone 聚焦会自动放大整页）。

#### 🔴 XSS 口径 = **渲染期转义**（2026-10-14 拍板，写死的规矩别再改）

- **后端入库一律原样存，不做任何 HTML 实体转义**；**前端 `esc()` 是唯一的安全出口**。
- 为什么必须二选一：契约 1.10 原文**自相矛盾** —— 前半句「入库前进行 HTML 实体转义」，
  后半句「前端渲染时使用 textContent 而非 innerHTML」（后半句其实就是渲染期转义）。
  **两者同时做 = 双重转义**：页面显示乱码、xlsx 导出出现 `&lt;`、前端比较原文要先猜库里存的哪一层。
- 敲定"渲染期"的三个理由：
  ① **安全效果等价** —— XSS 的根因是"渲染时把用户输入当代码执行"，`esc()` 正好堵这一环；
  ② 入库转义**污染下游**（导出、搜索、比较都得反解）；
  ③ 入库转义**不可逆** —— `&amp;` 分不清原文是 `&` 还是 `&amp;`，改口径时反解极易解错。
- `esc()` 两个易错点（都有断言钉住）：**`&` 必须最先替换**（否则把刚生成的 `&lt;` 里的 `&` 再转一次
  → `&amp;lt;`）；`String(s == null ? '' : s)` 兜住 null（不然渲染出字面量 "null"）。
- 守卫 = `verify-frontend.py` **第三十二节 19 条**，含**反向**断言"后端源码里不许出现
  `HtmlUtils` / `StringEscapeUtils` / `escapeHtml`" —— 本口径在代码里的表现是"什么都没写"，
  没有任何东西能阻止后来者顺手加上去，所以只能这样守。
- 排查手法（可复用）：`target/scan-escape2.py` 扫描"用户可控字段直接拼进 HTML 且没 `esc()`"的点。
  ⚠️ 它**会漏掉经由 setNotice→alertHtml 的路径**（那里 `esc` 在渲染函数里，不在调用行上）——
  扫出来先回源码确认再下结论。

**进度**：
- ✅ 第一步 —— 登录 / 登出 / 改密码（含 `needChangePassword` 提醒）。
- ✅ 第二步 —— **「我的工单」列表页**（`viewMyOrders`）：状态筛选 + 关键词搜索 + 翻页 +
  状态徽章（5 色）+ 未读红点 + 空状态；标题按角色变（学生「我的工单」/ 干事「工单处理」/ 管理员「全部工单」）。
  占位用的 `viewHome` 已删除，`render()` 的 default 分支直接指列表页。
  - 三条实现要点：筛选/分页状态放全局 `state.orderQuery`（将来"进详情再返回"还停在第 3 页、还带搜索词）；
    事件**委托**绑在 `#order-list` / `#order-pager` 容器上（重画列表不丢监听器）；
    总页数用**后端返回的** `page/pageSize`（后端会纠正越界值，拿请求值算会错）。
  - `api.js` 的 `qs()` 会丢掉空筛选条件（后端其实也容错，但 URL 更干净）；
    卡片点击暂时只提示"详情页下一步做"——点了完全没反应会让人以为页面坏了。

- ✅ 第三步 —— **「提交工单」页**（`viewSubmit`，路由 `#/submit`，**只给学生**）：
  5 个分类做成**胶囊按钮**（不用下拉）+ 问题描述（`maxlength=500` + 字数计数）+
  附件多选（选一个传一个、顺序传）+ 提交。
  - 🔴 **幂等号 `requestId` 的生命周期**：进页面生成 → **一直用到提交成功为止** → 成功后换新号；
    **失败不换号**。依据后端 `IdempotencyStore.run`：只有成功才缓存结果，**失败会 `release()` 放开锁**
    ⇒ 沿用同一个号重试不会被 409 挡。（写错过的坑：曾以为"业务失败要换新号否则被 409 卡"。）
  - 附件：`POST /api/upload` 拿 `attachmentId` → 提交时放进 `attachmentIds`；
    **只带上传成功的那些**（失败的一起带给后端会让整单失败）；`input.value=''` 防"同文件再选不触发 change"。
  - `uuid()` 必须有 `Math.random` 降级分支：`crypto.randomUUID` 只在 https/localhost 有，
    部署到内网 IP（`http://192.168.x.x`）会没有。
  - 提交成功后**重置列表筛选**（否则新单可能落在筛选之外，用户以为没提交上）。
  - `api.js` 侧：`submitWorkOrder(body)` / `uploadFile(file)`（FormData 字段名必须是 `file`）。

- ✅ 第四步 —— **「工单详情」页**（`viewOrderDetail`，路由 `#/orders/{单号}`）。
  状态徽章 + 分类 + **四步进度条**（待接单→处理中→已回复→已关闭）+ 工单信息（四个时间点）
  + 问题描述（原文）+ 工单级附件 + 处理记录时间线。
  - 🔴 **路由要按前缀判**：`#/orders/WO2026...` 是"半固定"地址，`switch` 只能精确匹配
    ⇒ 得在 switch **之前**用 `route.indexOf('/orders/') === 0` 拦一道。
  - 🔴 **附件只给 `<button>` 不给链接**：`/api/files/{id}` 要 Token，浏览器跳链接不带
    `Authorization` 头 ⇒ 必然 401。用 `requestBlob()` + `saveBlob()`（§十一 铁律 3）。
  - `white-space: pre-wrap` 保住用户敲的换行；`isFlagged` **故意不展示**（管理员的内部标记）；
    MERGED 单不画进度条，改显示"已合并进主工单 XXX"。
  - 用到的接口：`GET /api/work-orders/{id}`（返回含 `messages`/`attachments`/`statusHistory`）。

- ✅ 第五步 —— **「消息通知」页**（`viewNotifications`，路由 `#/notifications`）+ 顶部未读红点。
  顶部条加「消息 N」入口；页面 = 通知卡片列表 + 全部/未读/已读筛选 + 分页 + 「全部已读」
  + 点一条 → 标已读 → 跳对应工单详情。三种角色共用同一个页面（后端按 Token 过滤收件人）。
  - 🔴 **两套"未读"是两回事，别混**：`/api/notifications/unread-count`（`t_notification_log`，
    系统提醒）**≠** 工单列表卡片上的 `unreadCount`（`t_work_order_message`，该单对话里的消息）。
    用户拍板：**顶部红点只算通知数**（点进去条数才对得上）；两个数相加会让"外面 5、里面 2"。
  - 🔴 **`POST /api/work-orders/{id}/messages/read` 只在"进详情页"时调**（⑩ 第四步漏了，第五步补上）。
    它一次清两样（该单的消息未读 + 该单的通知未读），返回 `{workOrderId, messageCount, notificationCount}`。
    ⇒ 清完**不能**把 `state.unreadCount` 直接置 0（别的单的通知还是未读），只能 `refreshUnread()` 重问后端。
  - 轮询 15 秒（`UNREAD_POLL_MS`）；`document.hidden` 时不发请求；`visibilitychange` 切回来立刻刷一次。
    只 `paintUnread()` 重画按钮里那一小块，**绝不重画整页**（否则会清掉用户填了一半的表单）。
    登出 / Token 失效要 `stopUnreadPolling()`；换账号要清 `unreadCount` 和 `notifQuery`。
  - 筛选下拉的 `value` 直接就是接口的 `isRead` 值（`''` / `'false'` / `'true'`），发请求时翻成真布尔；
    `''` 靠 `qs()` 丢掉 = 不传这个参数。改筛选回第 1 页。
  - 点一条通知的顺序**必须先标已读、再跳转**（`onNotifClick`，验证脚本钉了这一点）。
  - 实测（`target/probe-notif.py`，新接口一律先探真实行为再写断言）：
    **标不存在的 / 别人的通知 → HTTP 200 + code=404**；`?isRead=abc` → **HTTP 200 + code=400**；
    `read-all` 幂等（第二次 `markedCount=0`，是"本来就没未读"、不是失败）；
    干事每收到一张新单就 +1 条未读 ⇒ 验证脚本用"提交一张单"当**造未读的开关**。

- ✅ 第六步 —— **「公告」页 + 「常见问题」页**（`#/announcements`、`#/faqs`）。
  入口收在顶部条新加的**「更多 ▾」下拉**里 —— 顶部条已经有"品牌+消息+用户名+退出"，
  再加两个平铺按钮在手机上会挤成一团。
  - 🔴 **这两个列表接口返回的都是数组**，不是 `{total,page,list}` 那种分页对象
    （契约里它们连分页参数都没有）⇒ 前端没做分页，`list = r.data || []` 直接用。
  - 🔴 **FAQ 列表接口故意不返回 `answer`**：契约把 `viewCount` 的自增放在详情接口里，
    列表就发答案的话详情永远没人调、浏览量永远 0。所以"展开看答案"必须**再请求一次**
    `faqDetail()`，顺手用返回的 `viewCount` 刷新那一行的"看过 N 次"。
    ⇒ 前端**不能**在页面加载时批量预取答案，否则一屏就把浏览量刷爆。
  - 🔴 **FAQ 非法 category → 400**（不是静默给个空列表）⇒ 前端下拉只能放合法分类
    （复用提交工单那份 `CATEGORIES`），"全部分类"用空串让 `qs()` 丢掉这个参数。
  - 🔴 **公告正文按纯文本渲染**（`esc()` + `pre-wrap`），**不** `innerHTML`。
    后端 `AnnouncementItem` 注释写的是"支持富文本、前端按 HTML 渲染"，但那样等于把 XSS 口子
    开给"能发公告的账号"；而且系统里根本没有富文本编辑器，管理员本来就写不出粗体/超链接，
    纯文本渲染不损失任何实际效果。**用户拍板的。**
  - 下拉菜单的开关**不存 JS 变量**，直接读 DOM 的 `hidden`（每次 render 都重画，变量会和 DOM 对不上）。
    布局上 `.more-menu` 设了 `display:flex` ⇒ 必须自己补 `.more-menu[hidden]{display:none}`，
    否则 `hidden` 属性被覆盖、菜单永远关不掉。点别处收起那个 document 监听**只绑一次**（挂 boot 里）。
  - 这两个页面**只读**：前端不碰 `/api/admin/faqs`、`/api/admin/announcements`（验证脚本钉了这点）。
  - 实测：公告列表只返回 `ACTIVE` 且没过期的，并且已按 `sortNum` 升序排好（前端不用再排）；
    不存在的 FAQ → `code=404`。

- ✅ 第七步 —— **学生端最后两个写操作**：「追问」+「确认解决」，都长在**工单详情页底部**
  （`actionCardHtml`，**只在 `status === 'REPLIED'` 时整块出现**）。
  - 追问 `POST /api/work-orders/{id}/follow-up`，body `{content, requestId, attachmentIds}`。
    🔴 **不传 version** —— 后端 `WorkOrderFollowUpRequest` 没有这个字段（契约文档的示例写错了，实测确认）。
    效果：状态**退回 PROCESSING**（干事要重新回复），干事的未读数 +1。这是契约的设计，不是 bug。
  - 确认解决 `POST /api/work-orders/{id}/confirm`，body `{version}`。
    🔴 **version 必传**：不传 → `code=400`「缺少 version 参数」；传错 → `code=409` 版本冲突。
    效果：`CLOSED` + 写 `finish_time`，并在处理记录里插一条 SYSTEM 消息。**不可逆**。
    前端加了 `window.confirm` 二次确认（用户拍板）。
  - 🔴 **前端只认 REPLIED，虽然后端卡得不严**：实测 PROCESSING 也能连续追问成功
    （后端**有**状态校验但放行了 PROCESSING，见 §八）。前端按业务意图收窄 —— "追问"本就该是"答复不满意"时的动作。
  - 🔴 **`request()` 现在会把业务码 `code` 一起返回**（api.js）。确认解决遇到 409 时前端
    **自动重新加载详情**并提示"这张工单刚刚有变化"，而不是把后端那句话原样甩给用户
    —— 按 `code` 分流，不靠 message 文案匹配（后端改一个字就失效）。
  - 追问的幂等号 `followUpRequestId`：**进详情页重置**（换单不能复用，否则会拿到幂等缓存里
    上一张单的结果）、**成功后才换号**、**失败不换号也不重画详情**（否则用户刚敲的一段话就没了）。

- ✅ 第八步 —— **干事端四个写操作**：接单 / 回复 / 手动完结 / 退回，全部长在**工单详情页底部**
  （新增 `staffActionCardHtml`；`actionCardHtml` 改成**总入口** = 学生版 + 干事版拼接）。
  用户拍板：回复**先纯文字**（不做附件）、接单按钮**放详情页**、完结+退回**都加二次确认**、
  本轮**只做干事四件事**（管理员的 admin-close / merge 留到下一轮）。
  - 🔴 **按状态给按钮，且只给唯一合理的那几个**：
    `PENDING 且 handler_id 为空` → 只给「接单」；
    `PROCESSING`（我的单）→ 「回复」+「完结」+「退回」；
    `REPLIED`（我的单）→ 「回复」（**补充回复**）+「完结」，**没有退回**；
    `CLOSED / MERGED` → 什么都不给。
  - 🔴 前端判"是不是我的单"用 **`d.handlerId === state.me.userId`**（工号），**不是姓名**（会重名）。
    这和后端 `canViewWorkOrder` 完全对齐：STAFF 能看的单就是
    `handler_id = 我 OR (PENDING AND handler_id IS NULL)`，所以 `mine` 判断等于"后端已经放行过"。
  - 🔴 **接单的并发保护是数据库给的**：SQL 条件是 `status='PENDING' AND handler_id IS NULL`，
    两个人同时点只有一个人成功；另一个吃 409「该工单已被受理或状态不允许受理」。
    前端**不做**任何本地锁。⚠️ 这个 409 把"被抢"和"版本过期"合并成一句话了，
    所以前端不细分，一律走 `reloadAfterConflict()` 重新加载。
  - 🔴 **退回只在 PROCESSING 出现**：从 REPLIED 退回后端报 409「只有处理中的工单才能退回」
    （答复已经给学生了，不能把单子连同答复一起扔回池子）。
  - 🔴 **退回理由后端不校验，前端强制填**（`if (!remark)` → 提示 + focus，不发请求）。
    理由会写进系统消息给学生看，没理由等于把人晾着。⇒ 这是**前端按业务意图收窄**，不是改需求。
  - 🔴 **409 统一善后**：`reloadAfterConflict()` —— 提示「这张工单刚刚有变化，已为你重新加载最新状态」
    + **重新请求后端**（不是本地 `detailVersion + 1`，真实版本只有后端知道）。四个动作共用。
  - 🔴 **回复的幂等号和追问的号各管各的**（`replyRequestId` vs `followUpRequestId`）：
    后端指纹里带了接口名，混用必撞 `IDEMPOTENCY_CONFLICT`。
    生命周期照抄旧规矩：进详情页/换单重置、成功才换号、失败不换号不重画。
    ⚠️ 但 409 那条路**会**重画（状态变了不给看旧的），此时草稿丢失是可接受的
    —— 409 多半意味着这单已经轮不到你处理了，操作区整个会消失。
  - 🔴 **回复请求体不传 version**（后端 DTO 里没这个字段，实测传 999 也成功）。
    详情页的 `s-reply` 输入框同理要 `textarea.input.s-reply-input` 才盖得住 120px。
  - 两个输入框（`d-follow`/`s-reply`）的字数计数抽成 `bindCounter(inputId, counterId)`，
    `bindDetailActions()` 各调一次 —— `input` 事件不冒泡，事件委托接不住。

- ✅ 第九步 —— **「再次咨询」`re-consult`**（学生端收尾动作）。
  用户拍板：表单**复用提交页**（跳过去）、成功后**直接跳到新单详情页**、
  详情页**显示"本单由老单再次咨询而来"且可点跳回**、7 天限制**点下去才知道**。
  - 🔴 **入口**：详情页底部，`status === 'CLOSED'` 且是学生才出现（后端也只允许 CLOSED）。
  - 🔴 **表单复用**：`viewSubmit(fromId)` 一张表单两种模式 —— 标题/说明/按钮文字不同、
    多一条 `reConsultBannerHtml()` 说明条、提交时打 `reConsult` 而**不是** `submitWorkOrder`。
    字段完全一样（`category/content/requestId/attachmentIds`），所以**附件上传白得了**。
    分类**不预填**（契约说可以和原单不同，而且 `state.submitCategory` 是跨页残留的，预填反而误导）。
  - 🔴 **老单号写进地址，不写内存**：路由 `#/submit/from/WO2026...`（`route.slice(13)`）。
    理由：存内存里按 F5 就丢了，用户填完描述一点提交会变成**普通新建工单**，
    而且新单看起来也正常、他不一定察觉 —— 典型静默出错。地址里放着，刷新/前进/后退都还在。
    ⚠️ 这个分支必须排在 `switch` 的 `case '/submit'` **之前**（switch 是精确匹配）。
  - 🔴 **429 单独认**：`if (r.code === 429)` → 「这张工单 7 天内已经再次咨询过一次了，过几天再来吧。」
    ⚠️ 前端**无法提前预判**（列表/详情都不返回"最近是否咨询过"），只能点了才知道 —— 用户接受的取舍。
    ⚠️ 429 分支**不换幂等号**（这条根本没建单）。
  - 🔴 **详情页的来源提示** `sourceAlertHtml(d)`：后端详情一直返回 `sourceOrderId`（以前恒 null）。
    ⚠️ **只给学生做成可点**，干事/管理员给纯文字 —— 再次咨询的入口只有提交人能用，学生的老单必然看得到；
    干事看老单要看 `canViewWorkOrder`，有一半概率 403，给个会跳进错误页的链接不如只显示单号。
    ⚠️ 可点的东西用 `<button class="lnk">` 而**不是** `<a href>`（单页应用里 `<a>` 会让浏览器真发请求）。
  - 🔴 **原单完全不被改动**（实测 status/version 前后一致）—— 这是"只新建"设计的核心保证。
  - 顺手修了 `WorkOrderDetailResult` 里那句已经变错的注释
    （原写「再次咨询的来源工单（该功能未做，恒为 null）」）—— 纯注释，零逻辑改动。
  - ⚠️ **断言写法教训**：「清筛选发生在设提示语之前」这条原来写成"提示语前 400 个字符内有没有"，
    加了注释后距离涨到 414 字就误报了。已改成 `rfind(..., 0, _idx) > 0`（窗口无关，永不误报）。

验证：`verify/verify-frontend.py`（**365 条**），回归 18 个脚本 **1004 条断言 FAIL=0**。
学生端 + 干事端已完成；**下一步：管理端操作**（`admin-close` 强制关闭待接单 / `merge` 合并工单）。

- ✅ 第十步 —— **管理端「强制关闭」`admin-close`**（学生端 + 干事端 + 管理端全部收口）。
  用户拍板：**先做便宜的 `admin-close`**（`merge` 另起一轮）、界面**只做写操作要用的最小部分**。
  - 🔴 **入口**：详情页底部，`role === 'ADMIN'` 且 `status === 'PENDING'` 才出现一个卡片。
    管理员能进详情页靠的是既有的列表页（`canViewWorkOrder` 对 ADMIN 全放行，
    列表标题走 `listTitle()` = 「全部工单」）—— **没有为管理端新建任何页面/导航**，
    这就是"最小部分"的落地方式。
  - 🔴 `actionCardHtml(d)` 变成**三段拼接**：`student + staff + admin`。
    三个函数各自**先按角色拦一道**返回空串，所以同一时刻只有一段有内容。
    为什么不像干事那样也放开给 ADMIN：管理员和干事对"待接单的单"想的是两件事
    （干事想「接单」、管理员想「这张单该不该存在」），绝不能互相看见。
  - 🔴 **理由框 `maxlength=200`，是硬约束不是风格**：见 §八「remark 超长直接 500」。
    另外前端自己也挡一道"理由不能为空"（后端也挡，但只有前端知道光标该放回哪个框）。
  - 🔴 **发出去的请求体只有 `{ remark, version }`，不带 `requestId`**：
    admin-close 没套 `IdempotencyStore`（实测带一个也照常成功=被无视）。
    断言直接钉在请求体字面量上（`"{ remark: remark, version: state.detailVersion }" in seg`）。
  - 🔴 **409 有两种原因，但前端用同一套善后**（`reloadAfterConflict()`）：
    「版本冲突」和「状态已不是待接单」，正确反应都是"重新加载最新状态"——
    重载后要么 version 变新能重试，要么卡片直接消失（状态不对时压根不渲染操作区）。
    所以**不需要**让用户读懂那两句。
  - 🔴 **第一次的全站红色按钮**：新增 `.btn-danger`（用既有的 `--danger`），
    **全站就这么一处**。干事的「直接完结/退回」虽然也不可逆，但那是日常操作，
    染红反而让人不敢点 —— 红色留给"平时不点、点了不可逆"的动作。
  - 理由框高度规则同样是 `textarea.input.a-close-input`（0,2,1），
    光写 `.a-close-input`（0,1,0）盖不住 `textarea.input` 的 120px —— 这条坑踩过两次了。
  - ⚠️ **断言写法教训（第二次栽在"文本里含关键词"）**：原来写 `"requestId" not in 段`，
    结果被**文档注释里那句"不用带 requestId"**命中而误报。已改成查
    `requestId:`（带冒号）＋请求体字面量。
    同理之前踩过固定字符窗口的坑 —— **文本型断言要盯着"我要的那个东西"，而不是"这个词"**。

验证：`verify/verify-frontend.py`（**428 条**，+63），回归 18 个脚本 **1067 条断言 FAIL=0**（`REGRESSION OK`）。

- ✅ 第十一步 —— **合并重复工单 `merge`**（管理端最后一块写操作；**干事 + 管理员共用**）。
  用户拍板三件事：**先补接口验证再写界面**、**详情页里就地搜索选中主单**、**主单可以是已完结单但界面给提醒**。
  - 🔴 **先补的窟窿**：`merge` 的后端**早就写完了**（接口阶段 51/51 里就有），但它**从来没进过回归**
    —— `target/` 下没有 `verify-workorder.py`，18 个脚本里也没有它。本轮新建 `verify/verify-merge.py`
    （**62 条**）并加进 `run-regression.sh`（全套 18 → **19** 个脚本）。探查脚本 `（该探查脚本已丢失）`。
    ⚠️ **纠正过一个错前提**：我先前汇报说"管理端只剩 merge 要做"，隐含"后端还没写" —— 是错的。
  - 🔴 **前端入口**：新增 `mergeCardHtml(d)`，作为 `actionCardHtml` 的**第四段**
    （`student + staff + admin + merge`）。**它是唯一同时服务两个角色的段** ——
    塞进 `staffActionCardHtml` 会被那句 `role !== 'STAFF'` 挡掉，管理员就永远看不到。
  - 出现条件（与后端校验一一对应，前端提前拦只是少报无谓的错）：角色 ∈ {STAFF, ADMIN}；
    本单状态 ∈ {PENDING, PROCESSING, REPLIED}（终态整块不渲染）；干事额外要求
    `handler == 我 或 状态是 PENDING`（和后端第 6 步**同条件**）。
  - 🔴 **选主单 = 就地搜索、两步走**：输关键词 → `api.workOrders({page:1, pageSize:6, keyword})`
    → 点「选它当主工单」（**只改内存、不发请求**，选主单可反复改主意）→ 点「确认合并」。
    ⚠️ 复用**一般列表接口**而**不是**管理端 `#50 全维度搜索`：列表接口按角色返回的范围
    （干事 = 自己负责的 + 待接单池；管理员 = 全部）**正好**就是能合并的范围，不必再写一套权限逻辑。
    ⚠️ 三处**故意的克制**：搜索结果**剔除自己**（后端会 400）；`MERGED` 候选**按钮禁掉**（后端会 409）；
    「已完结(CLOSED)」候选**可选但给 `alert-warn` 提醒**（用户拍板：保持现状 + 把后果说明白）。
  - 🔴 **请求体只有 `{ mainOrderId, version }`**：没有 `requestId`（merge 无幂等）、没有 `remark`；
    `version` 用**本单**的（`state.detailVersion`，主单的 version 全程不被碰）。
  - 🔴 **409 三种原因统一善后**（版本冲突 / 子单状态已变 / 主单已被合并）→ `reloadAfterConflict()`，
    不要求用户分辨是哪一种。
  - 🔴 **全站第 2 个红按钮**：`.btn-danger` 复用（第 1 个是 admin-close）——
    红色留给"平时不点、点了不可逆"的动作。verify 断言已从 `btn-danger == 1` 改成 `== 2`。
  - 样式：候选摘要用 `-webkit-line-clamp: 2` 压两行；选中态用 **inset box-shadow** 画左侧竖条
    （**不用 border-left**，避免选中瞬间内容宽度变化、文字抖一下）。
  - ⚠️ **顺手清了两处已经过时的注释**：① `adminActionCardHtml` 里「合并留到下一步做」；
    ② `studentActionCardHtml` 里「实测 PROCESSING 照样能追问成功」（A2 已把后端收严，
    前端后端口径现已一致，不再是"前端比后端严"）。
  - ⚠️ **同一个坑踩第三次**：`"requestId" not in _ms` 被**自己的注释**「不传 requestId」命中而假红。
    ⇒ 改成查**请求体字面量** + `requestId:`（带冒号）。
    **教训（已第三次）**：写文本型断言前先自问"这句话在注释里会不会出现"。

验证：`verify/verify-frontend.py`（**490 条**，+29），回归 **19 个脚本 1191 条断言 FAIL=0**（`REGRESSION OK`）。
**管理端的写操作到此全部收口**（admin-close + merge）。剩下的都属"待用户拍板"项（见 §八；待办清单以 `SESSION.md` 为准）。

- ✅ 第十二步 —— **公告对游客（未登录）开放**（用户原话："公告不登录也能看"）。
  - 🔴 **起因是"两边不一致"**：后端 `JwtInterceptor.OPTIONAL_AUTH_PREFIXES` **早就把
    `/api/announcements` 和 `/api/faqs` 两个前缀都放行了**（不带 Token 也返回 `code=0`），
    但前端 `render()` 未登录时**一律**回登录页 ⇒ 后端放行没人用得上。本轮只改公告（用户只点了公告）。
  - 改了 **5 处，全在 `static/js/app.js`**（纯静态文件 ⇒ **IDEA 重新运行即可，不用改后端、不用重启 Spring**）：
    1. `topbarHtml()` 加 `if (!me)` **游客版**：只给「公告」+「登录」两个按钮。
       🔴 **这一条是必须项不是美化**：登录版那行直接读 `me.userName`，而游客 `state.me` 是 `null`
       ⇒ 会**崩在渲染里**（整页白，控制台报错），不是"少个按钮"这么轻。
    2. `bindTopbar()` 开头加游客分支，绑完就 `return`（否则会去找登录版才有的元素）。
    3. `render()`：未登录时**只有** `currentRoute() === '/announcements'` 才进公告页，其余回登录页 ——
       **白名单写法**（不是"排除登录页"的黑名单），以后加新页面不会被顺手放出去。
    4. `viewAnnouncements()`：返回按钮文案跟着身份走（游客看到「‹ 去登录」而不是「‹ 返回」）。
    5. `viewLogin()`：加「看看公告」入口 `#login-see-ann` —— 游客态**没有任何导航**，
       不给入口的话公告等于"存在但找不到"。
  - 游客**不启动未读轮询**（`boot` 里 `if (state.me) startUnreadPolling();` 本来就有守卫）——
    在断言里钉住，防止以后改坏。
  - ⚠️ **中途改过一次口径**：这一轮先只放开了公告，并把「FAQ 仍要登录」写进断言；
    随后用户拍板 **FAQ 一起放开** ⇒ 前端补了 4 处（顶部条加「常见问题」按钮 `#g-faq`、
    `render()` 白名单加 `/faqs`、`viewFaqs()` 返回按钮也用 `backText`、登录页加 `#login-see-faq`）。
    现在**前端白名单 = 后端两个前缀**，两边一致。
  - 🔴 **顺手补的死循环防护（重要）**：原来 20 处接口失败都是 `state.me = null; render()`。
    游客**已经在**白名单页面时再 `render()` ⇒ 把**同一页**重画一遍 → 再发请求 → 再 401 →
    **浏览器原地打转**。新增 `onNeedLogin(msg)`：已是游客就只 `setNotice` 提示、不重画；
    还没登录才回登录页。**只改了游客能进的 3 处**（公告列表 / FAQ 列表 / FAQ 详情），
    其余 17 处所在页面游客进不去、不存在这个循环，保持原样（不扩大改动面）。
  - 断言写法：**接口侧真打**（游客 = 完全不带 Token，管理员 Token 作对照）：
    公告 `code=0`；游客条数 **≤** 管理员；FAQ 列表 + **详情** `code=0`；
    工单列表不带 Token → **401**（证明没把别的接口一起放开）。

验证：`verify/verify-frontend.py`（**490 → 507 条**，+17，第二十五节 19 条）；
全套回归 **19 个脚本 1208 条断言 FAIL=0**（`REGRESSION OK`，2026-10-10）。
  （2026-10-11 加了 jti 后 → **1215 条**，见 §十二。）

## 十二、JWT / 登录态（jti，2026-10-11）

- ✅ **Token 带上 jti 了**：`JwtUtil.generateToken` 加 `.id(UUID.randomUUID().toString())`
  ⇒ payload 变成 `sub / userType / iat / exp / jti`，**每次登录都是独一份**。
- **修的是什么坑**：`iat`/`exp` 是 **NumericDate（秒级）**，HMAC 签名又是确定性的
  ⇒ 加 jti 之前，**同一秒内、同一用户、登录两次拿到的是一字不差的同一个 Token**。
  于是"登出后同秒再登录"拿到的还是刚被拉黑的那个字符串 → 拦截器查黑名单 → **401**，
  看起来像"刚登录就掉线 / 登录接口坏了"，其实前后是同一个 Token。
  （实证：`target/probe-token-reuse.py` —— 修复前连续登录 8 次只产出 1 个 Token。）
- 🔴 **为什么不动黑名单的 key**：key 仍然是"整个 Token 的 SHA-256"（`blacklist:token:{hash}`）。
  加 jti 已经够解决问题 —— Token 不同 ⇒ 哈希不同 ⇒ 登出只作废"这一个"。
  改成按 jti 存还得处理"老 Token 没有 jti"的兼容，收益不抵改动面。
- **守卫断言**：`verify-blacklist.py` 新增 **E 组 7 条**（20 → 27 条）——
  连登 5 次得 5 个不同 Token（E2）/ payload 里 jti 互不相同（E3）/
  登出第 1 个后第 2 个照常可用（**E6，这条就是原来会假红的那一步**）/ 两个 key 不同（E7）。
  ⇒ 谁把 jti 去掉，E2 和 E6 立刻红。
- ⚠️ 脚本里的 `time.sleep(1.2)` **保留但降级为"防御性"**（frontend 那处注释已改写）：
  跨秒现在**不再是必需的**。⚠️ 另两处（faq / template）的 sleep 是**别的原因**
  （数据库 `update_time` 秒级精度），**跟 jti 无关、不要动**。
- 顺手修了 `generateToken` 里一句过时注释（原写"用 HS256 签名"，实际算法由密钥长度决定）。
⚠️ 这次全套只跑 **4 分钟**（上次显示 47 分钟）—— 上次那个"卡住"纯属计时显示问题，别再当成慢。

- ✅ 第十三步 —— **管理后台：壳 `/admin` + 用户管理 `/admin/users`**（⑩ 第 4 项第一块）。
  用户拍板两件事：**做 /admin 后台首页 + 子导航**（后面 5 个页面直接往 `ADMIN_NAV` 加一行，
  顶部条和路由都不用再动）；**禁用/启用、重置密码两个都要弹确认框**。
  - **api.js 新增三个**：`adminUsers(params)`（GET + qs）、`adminUpdateUserStatus(id, body)`
    （**PUT** + `/status`）、`adminResetPassword(id)`（POST，**无请求体**）。
  - 🔴 **权限两层**：后端 Service 里那句 `role` 校验才是防线（非管理员 403）；
    前端挡两道是"别让人看见一堆点了必定报错的按钮" ——
    ① 顶部条「更多」里 `#mi-admin` **仅 `role === 'ADMIN'` 渲染**；
    ② 路由 `case '/admin'` 和 `case '/admin/users'` 都先 `if (!isAdmin()) { go('/'); return; }`。
    统一走 `isAdmin()`，6 个后台页面共用一句判断。
  - **筛选只给固定枚举**（`ADMIN_USER_TYPES` / `ADMIN_USER_STATUS`）：后端对非法 userType/status
    返回 **400** 而不是空列表 ⇒ 必须用下拉，不能让人手输。唯一的自由输入是关键词。
  - 🔴 **自己那一行不给「禁用/启用」按钮**（后端也会挡：改自己 → 400，否则管理员一下个请求就 403）。
  - ⚠️ **禁用按钮故意不染红**：禁用**可逆**（点"启用"就回来），红色只留给"回不去"的动作
    （现在是强制关闭 + 合并两处）。
  - 两个确认框的文案都写清后果：禁用 = 对方已登录会话立刻失效；
    重置 = **新密码是学号后 6 位**（响应不带明文）+ 已登录会话不受影响（JWT 无状态踢不下来）。
  - 排版沿用"卡片流"不做表格（手机上表格没法看）；分页**自己一套**
    （`adminUserPagerHtml`，id 用 `au-prev/au-next`），**没动**工单那套 `pagerHtml` —— 不扩大改动面。

验证：`verify-frontend.py` 新增第二十六节 **29 条**（507 → 536）；全套 **19 脚本 1244 条 FAIL=0**。

- ✅ 第十四步 —— **敏感词管理 `/admin/words`**（⑩ 第 4 项第二块）。
  用户拍板：**做批量导入** + **加本地过滤（找词 + 只看启用中的）**。
  - **api.js 新增四个**：`adminSensitiveWords()`（GET，返回**数组**不是 PageResult）、
    `adminAddSensitiveWord(body)`（POST 基路径）、`adminBatchSensitiveWords(body)`（POST `/batch`）、
    `adminDisableSensitiveWord(wordId)`（**DELETE**）。
  - 🔴 **最反直觉的一条：这个模块没有启用接口** —— 「启用」是把同一个词**再添加一次**
    （后端发现词已存在且是 DISABLED，会顺便恢复 ACTIVE：见 `addOrEnable`）。
    界面上的「启用」按钮打的就是添加接口（verify 第二十七节专门钉住这条）。
  - 🔴 **单个添加三种结果都是 code=0**，只有 message 能区分：新增成功 / 已存在未重复添加 /
    之前被禁用已重新启用 ⇒ 前端**必须把 message 显示出来**，否则管理员不知道刚才发生了什么。
  - **列表后端不分页、不过滤状态**（禁用词也要看得见，否则禁错了想恢复都找不到）
    ⇒ "找词"和"只看启用中"只能**前端做本地过滤**：整份缓存进 `state.adminWords`，
    纯内存 filter，**改筛选不发请求**（断言：adminWordVisible 那节里一个 api 调用都没有）。
  - ⚠️ **`input` 事件不冒泡** ⇒ 两个筛选控件只能直接绑元素；好在被重画的只有 `#aw-list`，
    控件不在里面 ⇒ 监听器不会丢（paintAdminWords 只动那一块）。
  - ⚠️ 批量导入用 `<details>` 折起来：日常只用"添加单个词"，空的大文本框会把列表顶到屏幕外。
    分隔符的神仙之处在**后端**（中文逗号/顿号/分号/换行都认），前端只需照抄 placeholder 说明。
  - ⚠️ 文本框样式写成 `textarea.input.adm-batch-input`（0,2,1）——
    只写 `.adm-batch-input`（0,1,0）盖不住 `textarea.input` 那条 120px（**这个优先级坑第三次**）。
  - ⚠️ 单词输入框 `maxlength=100`（对齐后端 WORD_MAX，超了是 400，不是静默截断）。

验证：新增第二十七节 **22 条**（536 → 558）；全套 **19 脚本 1266 条 FAIL=0**。

- ✅ 第十五步 —— **回复模板 `/admin/templates`** + **干事回复框套用模板**（⑩ 第 4 项第三块）。
  用户拍板：**顺手把"回复时套用模板"做上**（这才是模板的意义）、**编辑复用顶部表单**。
  - **api.js 新增四个**：`templates(params)`（GET，**不是管理员专属** —— 干事回复要用）、
    `adminCreateTemplate`（POST）、`adminUpdateTemplate`（PUT，**部分更新**）、`adminDisableTemplate`（DELETE）。
  - 🔴 **`?status=ALL/DISABLED` 只有管理员传才生效**：干事传了后端也忽略（照样只给 ACTIVE）。
    管理页靠它看已禁用模板；**干事端不要传**（传了没用，还容易让人误以为生效了）。
  - 🔴 **一个表单兼顾新增 + 编辑**：点「编辑」只把值搬进表单（**不发请求**），
    按钮变「保存修改」；保存时按 `state.adminTplEdit` 是否有值分走 PUT / POST。
    不给编辑另写一套字段 —— 四个字段两份，早晚出现"两处校验不一致"。
  - ⚠️ **"一个字段都没改"也是 code=0**，message = "没有需要修改的字段" ⇒ 前端直接显示后端 message，
    别自己编一句"已保存"（会骗人）。
  - 「启用」= `PUT {status:'ACTIVE'}`（部分更新，其余字段一个都不动）。
  - 🔴 **套用模板（详情页）**：回复卡上加 `#s-tpl` 下拉，`loadDetailTemplates()` 异步拉（**不 await**，
    别让模板拖住整页渲染）。三个坑：
    ① **只填空、不发送**（填完干事还能改），onTplPick 里**一个 api 调用都没有**；
    ② **程序化赋值不触发 `input` 事件** ⇒ 填完必须手动 `syncCounter('s-reply', 's-reply-count')`；
    ③ 异步回来时卡片可能已被重画/切走 ⇒ **await 之后再取元素并判空**，别提前缓存节点。
    另外：回复框已有内容时**先 confirm**（别把干事写了一半的话悄悄顶掉）；
    下拉选完立刻复位（它是"一个动作"不是"当前选中"）；拿不到模板就置灰 + 「暂无可用模板」，不影响回复。
  - ⚠️ 顺手把 `bindCounter` 拆出通用的 `syncCounter(inputId, counterId)`（现在有 4 个字数框共用）。

验证：新增第二十八节 **30 条**（558 → 588）；全套 **19 脚本 1296 条 FAIL=0**。

- ✅ 第十六步 —— **数据统计 `/admin/stats`**（⑩ 第 4 项第四块）。
  用户拍板：**CSS 横向条形图**（零依赖、不引图表库）+ **快捷按钮 + 两个日期框**。
  - **api.js 新增两个**：`adminStatisticsOverview(params)`、`adminStaffWorkload(params)`，都带 `qs`。
  - 🔴 **日期红线**：拼 `YYYY-MM-DD` 必须用 **本地** `getFullYear/getMonth/getDate`，
    **不许用"转 ISO 字符串再截前 10 位"那套**（那是 UTC，东八区凌晨会算成昨天 ——
    用户点「今天」查到昨天，界面还看着完全正常）。`app.js` 里连注释都不许提那个方法名，
    否则 verify 那条断言会被自己的注释命中（当天第 5 次栽在"注释命中断言"上）。
  - 🔴 **两个 distribution 的键顺序是随机的**（后端用 HashMap）⇒ 必须按 `FLOW.concat(['MERGED'])`
    固定顺序渲染、固定颜色；直接遍历对象的话每次刷新顺序都在跳。
  - 其他要点：不传日期 = 全量（空串被 `qs()` 丢掉）；`endDate` **整天都算**（界面上要写明）；
  - `duplicateRate` 是**小数**（0.12 = 12%，前端自己 ×100）；
  - 条形占比按**总量**算（不是按本项最大值），这样 5 条加起来才 100%；
  - `fmtMinutes(0)` 显示「—」（后端在"没有样本"时给 0，显示"0 分钟"会被读成快得离谱）；
  - 两个接口**并行** `Promise.all`；「近 7 天」= 含今天往前推 **6** 天（30 天 → 29）；
  - 起始晚于截止**前端先拦**（后端 400，没必要跑一趟服务器）。
  - 样式：`.sb-row/.sb-track/.sb-fill`（状态条颜色**沿用状态徽章那套**）+ `.stat-cards` + `.wl-row`。

验证：新增第二十九节 **28 条**（588 → 616）；全套 **19 脚本 1324 条 FAIL=0**。

- ✅ 第十七步 —— **数据导出 `/admin/export`**（⑩ 第 4 项第五块，契约 §10.5 / #47）。
  用户拍板：筛选 = **日期区间 + 状态 + 分类 + 关键词**（后端的查询对象是 #50 的超集，
  契约 §10.5 只列了前四个）；状态**单选**；文件名**前端自己起**（不动那个已验证的公共下载函数）。
  - **api.js 新增一个**：`exportWorkOrders(params)` → 走 **`requestBlob`**，**不是** `request`。
    🔴 走 `request` 必死：它拿到响应就 `resp.json()`，对着一坨二进制直接报"不是合法 JSON"，文件永远拿不到。
  - 🔴🔴 **这一页的核心坑：后端失败时 HTTP 也是 200**，错误写在一段 JSON 的 `code` 里
    （参数错 400 / 非管理员 403）。而 `requestBlob` **只看 HTTP 状态码** ⇒ 失败时它照样回
    `ok:true`，把那段 JSON 当"文件"交出来。不做分流的话用户会下载到一个**几十字节的假 xlsx**，
    打开是乱码，**而且全程一句报错都没有**（他只会来问你"导出的文件打不开"）。
    ⇒ `onExportRun()` 必须**先看 Content-Type**：含 `json` → `await r.blob.text()` + `JSON.parse`
    掏出 `message` 显示；是 xlsx 才 `saveBlob`。**顺序本身也是断言的一部分**（saveBlob 必须在判断之后）。
  - 文件名 `工单导出-YYYYMMDD-HHmmss.xlsx`，时间戳同样取**本地**时间（不能拿 UTC 字符串截位）。
    带时间戳 = 导两次不互相覆盖。后端其实也在 `Content-Disposition` 里给了名字
    （`work-orders-….xlsx`），但读它得先给 `requestBlob` 加一项返回值 —— 用户选了"前端自己起名"。
    ⚠️ 顺带确认了**前端那两条分流依据真的成立**：成功 → 200 + `…spreadsheetml.sheet` + `PK` 魔数；
    失败 → 200 + `application/json` + `code`（这两条已进断言）。
  - 其它：**筛出 0 条不算失败**（后端照样回 200 + 只有表头的合法 xlsx，前端不该报错）；
    每次**成功**导出后端写一条 `EXPORT` 审计（`target_id=BATCH`、内容含筛选条件和 `rows=`），
    失败/无权限的**不写**；导出**不分页**（传 page 也不生效）；起始晚于截止前端先拦；
    生成期间禁用按钮（防连点）。
  - 顺手修的**真问题**：`app.js` 里 `setNotice('err', …)` 有 **17** 处、`'error'` 有 21 处，
    而 CSS 只定义了 `.alert-error` ⇒ 那 17 处的错误提示**一直没有红底**（只剩 `.alert` 那圈灰边框，
    看着像"普通提示"）。在 CSS 里给 `.alert-err` 补一条同款样式即可（**不**去改那 17 个调用点）。

验证：新增第三十节 **28 条**（616 → 644）；全套 **19 脚本 1352 条 FAIL=0** → `REGRESSION OK`。

- ✅ 第十八步 —— **操作日志 `/admin/logs`**（⑩ 第 4 项第六块，契约 §10.6 / #48、#49）。
  用户拍板：**一页 + 分段切换**（两个接口）；操作类型给**分组下拉**；操作日志**精简列 + 点开看详情**。
  - **api.js 新增两个**：`adminStatusLogs(params)`、`adminOperationLogs(params)`，都带 `qs`。
  - 🔴 **两个接口答的是两个不同的问题，别混**：
    · `/status-logs` → `t_status_log`：**这张单**经历了什么（业务时间线，9 种 action）；
    · `/operation-logs` → `t_operation_log`：**谁**动过系统（查账追责，29 种 operationType）。
  - 🔴🔴 **`operationType` 后端故意不校验取值**（varchar，类型随功能增长）⇒ 传库里没有的值
    **不是错误**，而是 `code=0` + `total=0` 的**空列表**。
    ⇒ 界面上**绝不能让用户手输**（`LOGIN` 打成 `login` 就什么都查不到，他只会以为"这段时间没记录"），
      必须给下拉；且**0 条时的提示要写"换个条件再试试"，不能写"加载失败"**（两件事完全不一样）。
  - 🔴 **`beforeStatus` 可能是 `null`**（一张单的第一跳没有旧状态，库里 2692 行是 NULL）⇒
    前端画成「—」，**绝不能渲染成字符串 "null"**（和导出页那条断言同一个道理）。
  - 前端把三份"码 → 中文"清单写死在 app.js：`LOG_ACTION_LABEL`(9) / `OP_TYPE_GROUPS`(29) /
    `TARGET_TYPE_LABEL`(6)。**代价**：后端加新类型要手动补一行 ⇒ **用三条动态断言兜着**：
    拿 `SELECT DISTINCT` 的库中实况去比前端清单，**库里的必须被前端覆盖**（见下）。
  - 其它：分页器的页数用**后端返回的** total/pageSize 算；点"查询"**页码归 1**（否则停在空页上，
    用户以为真没数据）；两个 tab **各存一套**筛选与页码；展开/收起只重画列表、不重画整页；
    列表容器只绑**一个**事件委托（先判分页按钮、再判"点开了哪一条"）。

验证：新增第三十一节 **32 条**（644 → 676）——其中 **3 条是"前端清单 vs 数据库实况"的对账**
（操作类型 29 种 / 动作 9 种 / 对象 6 种，全部覆盖）；
全套 **19 脚本 1384 条 FAIL=0** → `REGRESSION OK`。

### 🔴 往 app.js / api.js 里插新块，会把旧断言的"切段区间"撑大（2026-10-11，一天内栽两次）

`verify-frontend.py` 里好几处用 `js_app.find("/* ---------- 十二、路由", 起点)` 当**终点**。
导出页插在「统计」和「路由」之间 ⇒ 统计页那条 `count('type="date"') == 2`
把导出页的两个日期框也数了进去 ⇒ **假红**（而且看着像"我改坏了统计页"，其实统计页一个字没动）。
- 修法：在脚本里加了通用 **`cut(start, *next_markers)`** —— 传几个候选锚点、取**最先出现**的那个。
  已给敏感词 / 模板 / 统计三处补上候选。
- ⚠️ **再往 app.js 里插新块时，记得给"它前面那一块"补一个候选锚点**
  （这次做日志页时我又忘了给"导出"那块补 —— 补上了）。
- **第 2 次**（同一轮、换个文件）：api.js 里切 `exportWorkOrders` 那段的终点写的是 `"\n};"`，
  而它当初正好是**最后一个方法**；后面加了两个日志方法之后，`\n};` 被推到文件末尾 ⇒
  切出来的段把新方法里的 `request(` 一起吞了 ⇒ 那条 `"request(" not in _export_api` 假红
  （段长从几百变成 1998）。
  ⇒ 修法：终点改成"**下一个方法定义**"（`re.search(r"\n  [a-zA-Z]\w*\(", ...)` ——
    api.js 方法定义缩进 2 空格、方法体 4 空格，正好只命中定义那一行）。
- 同时把 `onNeedLogin('` 的断言从 `== 3` 改成 `>= 3`（导出页也用同一个善后函数）——
  **"数量写死"这条规矩又栽一次**（累计第 3 次）。

### ⚠️ 两条旧断言被这次改动"弄红"，都改了写法

1. 「app.js 里没有任何内联 style」—— 条形图的**宽度**是数据算出来的（12.3%），CSS 类表达不了。
   改成写语义：**行内 style 只允许表达"数据驱动的宽度"**，其余一律进 CSS
   （`re.findall(r'style="([^"]*)"')` + 非 `width:` 开头的就算违规）。
2. 「bindCounter 一共被调用 3 次」→ 加第 4 个字数框就红。改成**自动配对**：
   凡有 `id="xxx-count"` 就必须有 `bindCounter('xxx','xxx-count')`。
