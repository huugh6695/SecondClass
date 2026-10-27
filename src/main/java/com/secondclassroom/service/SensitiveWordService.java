package com.secondclassroom.service;

import com.secondclassroom.common.Result;
import com.secondclassroom.dto.SensitiveWordAddRequest;
import com.secondclassroom.dto.SensitiveWordBatchRequest;
import com.secondclassroom.dto.SensitiveWordBatchResult;
import com.secondclassroom.dto.SensitiveWordItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 敏感词库管理（文档 §10.2 / #37~#40），全部仅管理员可用。
 *
 * ⚠️ **表名是 `t_sensitive_word`（单数）**，文档里写的 `t_sensitive_words` 是错的。
 *
 * 敏感词库的定位：学生提交工单时会拿这里的词去扫一遍内容，命中的工单打上 is_flagged 标记，
 * 后台工单搜索（#50）里有个 `isFlagged` 维度专门筛它。所以这张表是"减少人工巡查"的钩子，
 * 而不是真的去拦截提交 —— 命中也不会拒绝提交，只是标记出来给管理员看。
 *
 * ──────────────────────────────────────────────────────────────────────
 * 📌 **与文档的两处偏差（答辩时要说清楚）**
 *
 * 1) **缓存的"失效方式"与文档不同：我们删缓存，文档写的是重建缓存**。
 *    文档在每个写操作后面都写了 "Redis SADD sensitive_words:cache" / "从 DB 重建缓存"；
 *    我们改成「**只把缓存删掉**，下次读的时候从库里重新装一份」。观测行为完全一样
 *    （管理员改完立即生效），但更安全、更省 —— 理由写在 `invalidateCache()` 的注释里，
 *    一句话版本：**删缓存永远安全，重建缓存可能喂进未提交的数据**。
 *    另外失效动作挂在 **事务提交之后**（`invalidateAfterCommit()`），不是就地执行。
 *
 * 2) **"禁用后再添加"会被自动恢复启用**（文档没规定，属于补的一个小口子）。
 *    因为 `word` 上有 UNIQUE 约束（uk_word），一个词被禁用后**唯一键仍然占着位置**，
 *    纯 INSERT IGNORE 的话这个词就永远加不回来了 —— 软删除变成单向操作。
 *    所以这里的做法是：插入被忽略时，再看一眼它是不是 DISABLED，是就顺手恢复成 ACTIVE。
 *    在批量接口里表现成"这一条算成功（added）"，而不是"跳过"。
 *    不想要这个行为可以删掉 addOrEnable 里那半段 UPDATE —— 只影响这一处。
 * ──────────────────────────────────────────────────────────────────────
 */
@Service
public class SensitiveWordService {

    private final JdbcTemplate jdbcTemplate;
    private final OperationLogService operationLogService;
    private final StringRedisTemplate redis;

    /** word 是 varchar(100)，超了会被 MySQL 按 IGNORE 语义悄悄截断，所以先自己拦 */
    private static final int WORD_MAX = 100;

    /**
     * 缓存 key（文档 §10.2 规定的名字）：一个 Redis **SET**，成员 = 当前所有 ACTIVE 的词。
     *
     * 为什么缓存"整份词表"而不是"某段内容的命中结果"：
     *   内容千变万化、几乎不会重复，按内容缓存等于每次都不命中，还会把 Redis 撑爆；
     *   而词表就几十~几百条，整份装进来后匹配在内存里做，**一次网络往返都不用再花**。
     */
    private static final String CACHE_KEY = "sensitive_words:cache";

    /** 缓存最多活 30 分钟：万一哪次失效动作没生效，最多也只会错这么久（兜底） */
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    /**
     * Redis 出问题时的日志节流：至少隔 30 秒才再报一次。
     * 命中检测**每提交一张工单就跑一次**，不节流的话 Redis 一挂日志立刻被刷爆。
     */
    private static final long WARN_INTERVAL_MS = 30_000L;

    /** 上次打印"Redis 不可用"的时间，用于节流 */
    private volatile long lastWarnAt = 0L;

    private static final Logger log = LoggerFactory.getLogger(SensitiveWordService.class);

    /** addOrEnable 的三种结果 */
    private static final int RESULT_INSERTED = 1;   // 新词入库
    private static final int RESULT_RESTORED = 2;   // 之前被禁用，这次恢复启用
    private static final int RESULT_EXISTS = 0;     // 已经生效中，什么都没做

    public SensitiveWordService(JdbcTemplate jdbcTemplate, OperationLogService operationLogService,
                                StringRedisTemplate redis) {
        this.jdbcTemplate = jdbcTemplate;
        this.operationLogService = operationLogService;
        this.redis = redis;
    }

    // ============ 列表（GET /api/admin/sensitive-words） ============

    /**
     * 敏感词列表。文档给的是 `SELECT * FROM t_sensitive_words ORDER BY create_time DESC`。
     *
     * 三个刻意的选择：
     *  · **不分页**（照文档）：敏感词库是几十到几百条的量级，一次给全，前端本地筛选更顺手。
     *    真涨到几千条再说 —— 加 PageResult 是十分钟的事。
     *  · **不过滤 status**（照文档的 `SELECT *`）：管理员得能看见被禁用的词，
     *    否则禁错了想恢复都找不到它在哪（禁用是软删除，行还在库里）。
     *  · 末尾补 `word_id DESC`：同一秒批量导入的词，不加主键排序两次查询顺序可能不一样。
     */
    public Result<List<SensitiveWordItem>> listWords(String userType) {
        if (!"ADMIN".equals(userType)) {
            return Result.fail(403, "仅管理员可管理敏感词");
        }
        List<SensitiveWordItem> list = jdbcTemplate.query(
                "SELECT word_id, word, status, creator_name, create_time, update_time "
                        + "FROM t_sensitive_word ORDER BY create_time DESC, word_id DESC",
                ItemRowMapper.INSTANCE);
        return Result.success("success", list);
    }

    // ============ 单个新增（POST /api/admin/sensitive-words） ============

    /**
     * 新增一个敏感词（仅管理员）。文档要求用 `INSERT IGNORE` 靠 UNIQUE 去重。
     *
     * 返回的 message 会区分三种情况（前端可以直接弹给管理员看）：
     *   新增成功 / 该词之前被禁用，已重新启用 / 该词已存在，未重复添加
     */
    @Transactional
    public Result<SensitiveWordItem> addWord(String userId, String userType,
                                            SensitiveWordAddRequest req) {
        // 1. 角色校验
        if (!"ADMIN".equals(userType)) {
            return Result.fail(403, "仅管理员可管理敏感词");
        }
        // 2. 入参校验（单词必须非空、不超长）
        String word = req == null ? null : req.getWord();
        if (word == null || word.isBlank()) {
            return Result.fail(400, "敏感词不能为空");
        }
        word = word.trim();
        if (word.length() > WORD_MAX) {
            return Result.fail(400, "敏感词不能超过 " + WORD_MAX + " 个字");
        }
        // 3. 查操作人姓名（审计用）
        String userName = loadUserName(userId);
        if (userName == null) {
            return Result.fail(404, "用户不存在");
        }

        // 4. 写库（插入 or 恢复启用）
        int outcome = addOrEnable(word, userId, userName);
        SensitiveWordItem saved = loadWord(word);

        // 5. 让敏感词缓存失效（文档 §10.2 要求的"SADD 更新缓存"就落在这里；
        //    我们改成"把缓存删掉、下次读时重装"，理由见 invalidateCache 的注释）。
        //    只有真改动了才失效 —— "点了一下但库里什么都没变"就别白删一次。
        if (outcome != RESULT_EXISTS) {
            invalidateAfterCommit();
        }

        // 6. 审计日志：只有真改动了才记（"点了一下但什么都没发生"不该留痕）
        //
        //    ⚠️ target_id 放的是 **word_id（数字）**，不是词文本 —— 这两个坑都真踩过：
        //       · t_operation_log.target_id 是 varchar(64)，而敏感词最长 100 字（varchar(100)）。
        //         直接把词塞进去，加一个 65~100 字的词就会「Data too long for column」→ 500。
        //       · 项目惯例本来就是 target_id 放"记录的业务主键"（FAQ 放 faqId、公告放 noticeId），
        //         人的可读信息一律放 operation_content。
        if (outcome != RESULT_EXISTS && saved != null) {
            operationLogService.record(userId, userName, userType,
                    "ADD_SENSITIVE_WORD", "SENSITIVE_WORD", String.valueOf(saved.getWordId()),
                    outcome == RESULT_RESTORED ? "启用敏感词（原为禁用状态）：" + word
                            : "新增敏感词：" + word);
        }

        String message = switch (outcome) {
            case RESULT_INSERTED -> "敏感词新增成功";
            case RESULT_RESTORED -> "该词之前被禁用，已重新启用";
            default -> "该词已存在，未重复添加";
        };
        return Result.success(message, saved);
    }

    // ============ 批量导入（POST /api/admin/sensitive-words/batch） ============

    /**
     * 批量导入敏感词（仅管理员）。文档给的是逗号分隔的长字符串，比如
     * `{"words": "违规词1,违规词2,违规词3"}`。
     *
     * 处理顺序：split → trim → **去重** → 逐个 addOrEnable → 汇总数量。
     *
     * 为什么要先去重：管理员从 Excel 粘过来的列经常自带重复行。不去重的话，
     * 重复项第一次会新增、第二次会因为 INSERT IGNORE 变"跳过"，报出来的
     * "跳过 3" 其实是自己跟自己重复，看着像库里有别的数据 —— 数字会误导人。
     * 去重之后 `added + skipped == total` 才是一句真话。
     */
    @Transactional
    public Result<SensitiveWordBatchResult> batchAdd(String userId, String userType,
                                                     SensitiveWordBatchRequest req) {
        // 1. 角色校验
        if (!"ADMIN".equals(userType)) {
            return Result.fail(403, "仅管理员可管理敏感词");
        }
        // 2. 拆词
        List<String> words = splitWords(req == null ? null : req.getWords());
        if (words.isEmpty()) {
            return Result.fail(400, "没有解析到有效的敏感词（请用逗号分隔）");
        }
        // 2.1 超长的单独报错，**不能悄悄丢掉**：
        //     管理员粘一列进来，其中一个被默默忽略，返回的 total 又对不上他数出来的个数，
        //     他只会以为"系统吃了我一个词"（想查都无从查起）。
        //     （这一条和单个新增的 400 保持同一个口径：超长就是错，不是忽略。）
        for (String w : words) {
            if (w.length() > WORD_MAX) {
                return Result.fail(400, "敏感词「" + w.substring(0, 20) + "…」超过 "
                        + WORD_MAX + " 个字，请先检查粘贴的内容");
            }
        }
        // 3. 查操作人
        String userName = loadUserName(userId);
        if (userName == null) {
            return Result.fail(404, "用户不存在");
        }

        // 4. 逐个入库
        int added = 0;
        int skipped = 0;
        List<String> changed = new ArrayList<>();   // 本次真正写动了库的词
        List<String> restored = new ArrayList<>();  // 其中属于"恢复启用"的
        for (String word : words) {
            int outcome = addOrEnable(word, userId, userName);
            if (outcome == RESULT_EXISTS) {
                skipped++;
            } else {
                added++;
                changed.add(word);
                if (outcome == RESULT_RESTORED) {
                    restored.add(word);
                }
            }
        }

        // 5. 让缓存失效（批量导入同理，只要真改了就去删；一个都没改就什么都不用做）
        if (added > 0) {
            invalidateAfterCommit();
        }

        // 6. 审计日志（批量只记一条汇总，不然一次导入 500 个词就写 500 行审计，把表撑爆）
        //
        //    ⚠️ target_id 传的是哨兵值 "BATCH" 而不是 null：
        //       t_operation_log.target_id 是 **NOT NULL**，传 null 会直接 500（真踩到过）。
        //       批量操作本来就没有单一的"目标主键"，所以用哨兵值占位，
        //       具体改了哪些词全部写在 operation_content 里（text 字段，装得下）。
        operationLogService.record(userId, userName, userType,
                "BATCH_ADD_SENSITIVE_WORD", "SENSITIVE_WORD", "BATCH",
                "批量导入敏感词：提交 " + words.size() + " 个（去重后），新增/启用 " + added
                        + " 个，跳过（已存在）" + skipped + " 个"
                        + "；本次新增/启用的词：" + joinCapped(changed, 50)
                        + (restored.isEmpty() ? "" : "；其中恢复启用：" + joinCapped(restored, 20)));

        return Result.success("批量导入完成，新增/启用 " + added + " 个，跳过 " + skipped + " 个",
                new SensitiveWordBatchResult(words.size(), added, skipped));
    }

    // ============ 禁用（DELETE /api/admin/sensitive-words/{wordId}） ============

    /**
     * 禁用敏感词（仅管理员）—— **软删除**：status 改成 DISABLED，行留在库里。
     *
     * 为什么不物理 DELETE？和 FAQ 下架、公告归档同一个理由：
     * 审计日志里记着这个词，删了引用就悬空了；而且禁用是可逆的（再 POST 一次同一个词就会恢复启用）。
     *
     * ⚠️ wordId 收的是 **String**，这里自己 parse 成 int。
     *    如果 Controller 直接声明成 `@PathVariable int wordId`，用户路径上写个 `/abc`
     *    会让 Spring 抛 MethodArgumentTypeMismatchException，全局异常处理器没覆盖 → 500。
     *    自己解析才能给出"敏感词 ID 必须是数字"这种能看懂的 400。
     */
    @Transactional
    public Result<Void> disableWord(String userId, String userType, String wordIdRaw) {
        // 1. 角色校验
        if (!"ADMIN".equals(userType)) {
            return Result.fail(403, "仅管理员可管理敏感词");
        }
        // 2. 参数解析
        Integer wordId = parseWordId(wordIdRaw);
        if (wordId == null) {
            return Result.fail(400, "敏感词 ID 必须是数字");
        }
        // 3. 存在性校验
        SensitiveWordItem exist = loadWordById(wordId);
        if (exist == null) {
            return Result.fail(404, "敏感词不存在");
        }
        // 4. 操作人
        String userName = loadUserName(userId);
        if (userName == null) {
            return Result.fail(404, "用户不存在");
        }

        // 5. 软删除（禁用必须立即生效，否则"撤销这个判定"要等 30 分钟）
        jdbcTemplate.update(
                "UPDATE t_sensitive_word SET status = 'DISABLED' WHERE word_id = ?", wordId);

        // 6. 让缓存失效（文档要求这里"从 DB 重新加载所有 ACTIVE 词重建缓存"，
        //    我们改成删掉缓存、下次读时重装 —— 效果一样，且不怕事务回滚）
        invalidateAfterCommit();

        // 7. 审计日志（target_id 用 word_id，理由见 addWord 里的注释）
        operationLogService.record(userId, userName, userType,
                "DELETE_SENSITIVE_WORD", "SENSITIVE_WORD", String.valueOf(exist.getWordId()),
                "禁用敏感词：" + exist.getWord() + "（软删除）");

        return Result.success("敏感词已禁用", null);
    }

    // ============ 给工单流程用的：内容命中检测 ============

    /**
     * 这段内容里有没有命中**启用中**的敏感词？
     *
     * 这是敏感词库**唯一真正对外产生作用**的出口：提交工单 / 再次咨询时拿它扫一遍 content，
     * 命中就把工单的 is_flagged 置 1（文档 §4 第 4 步：「命中: isFlagged=true（仍允许提交，标记人工审核）」）。
     *
     * 三个设计选择：
     *  · **只扫 ACTIVE 的词**：被禁用的词就是"管理员撤销了这个判定"，再拿来判就没意义了。
     *  · **contains 语义**（`content LIKE '%词%'`）：文档说的是"遍历敏感词列表"，
     *    也就是"内容里出现这个词就算"，不是全文检索打分。这样最短、最好解释，
     *    管理员加一个"代考"就能命中"我想找人代考"。
     *    ⚠️ 代价是没有词边界概念（英文词 part 会命中 party）—— 中文场景基本无所谓，
     *    真要做词边界得换分词方案，那是另一个量级的事。
     *  · **不做 try-catch 吞异常**：检测只是"打个标记"，不是提交闸门，
     *    但**读库失败**是 **真故障**（配置/表结构出问题），应该让它以 500 冒出来被人看见，
     *    而不是吞掉之后让所有工单都"看起来正常、其实全没检测"。
     *    （Redis 失败是另一回事：那只是一份副本丢了，直接查库即可，不算故障 —— 见 loadActiveWords。）
     *
     * ⑧⑤ 之后：词表从 Redis 缓存里取（`sensitive_words:cache`），匹配在内存里做，
     * 省掉"每提交一张工单就查一次库"。
     *
     * @param content 待检测的文本（工单正文），null / 空白一律视为没命中
     * @return true = 命中至少一个启用中的敏感词
     */
    public boolean hitsSensitiveWord(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        List<String> words = loadActiveWords();
        if (words.isEmpty()) {
            return false;   // 词库为空（或全被禁用）：没什么可比的
        }
        // ⚠️ 两边都要 toLowerCase 再比：老实现是 SQL `? LIKE CONCAT('%', word, '%')`，
        //    靠表的 utf8mb4_unicode_ci 排序规则**自动**做到"英文大小写不敏感"。
        //    换成内存比对之后这个性质得自己保住，否则英文敏感词以前能命中 EXAM、
        //    现在只能命中全小写的 exam —— 这种"悄悄变严"的回归最难被人发现。
        //    用 Locale.ROOT 而不是默认 locale：土耳其语环境里 "I".toLowerCase() 会得到 "ı"。
        String lowerContent = content.toLowerCase(Locale.ROOT);
        for (String word : words) {
            if (!word.isEmpty() && lowerContent.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    // ============ 缓存读取 / 失效 ============

    /**
     * 取出当前所有"启用中"的词。优先走 Redis，没缓存就从库里装一份进去。
     *
     * 降级策略（用户拍板）：**Redis 连不上就直接查库**。
     *   为什么这里可以降级、而单号/幂等那两处"不静默降级"？
     *   因为这两件事的性质不同：单号降级会和计数器**分叉**（恢复后发出已经用过的号），
     *   幂等降级等于**偷偷放行重复提交** —— 都会算错。而敏感词缓存只是一份**副本**，
     *   库里那份才是真的；查库拿到的是**完全一样的结果**，只是慢一点。
     *   而且检测只是"打个标记给人看"，不该让它把"提交工单"整条路堵死。
     */
    private List<String> loadActiveWords() {
        try {
            Set<String> cached = redis.opsForSet().members(CACHE_KEY);
            // Redis 里的空集合会被自动删除，所以"取到了但不是空"才算命中
            if (cached != null && !cached.isEmpty()) {
                return new ArrayList<>(cached);
            }
        } catch (RuntimeException e) {
            warnRedisDown("读取", e);
            return queryActiveWords();   // 连不上：直接查库，结果等价
        }
        List<String> words = queryActiveWords();
        cacheWords(words);               // 回填（失败也无所谓，见方法内注释）
        return words;
    }

    /** 从库里读所有启用中的词 —— 这是敏感词库唯一的事实来源 */
    private List<String> queryActiveWords() {
        return jdbcTemplate.queryForList(
                "SELECT word FROM t_sensitive_word WHERE status = 'ACTIVE'", String.class);
    }

    private void cacheWords(List<String> words) {
        try {
            if (words.isEmpty()) {
                // Redis 存不住"空集合"（会被自动删掉），所以词库为空时干脆不缓存，
                // 代价是这期间每次检测都查一次库 —— 词库全禁用是极少数情况，可以接受。
                redis.delete(CACHE_KEY);
                return;
            }
            redis.opsForSet().add(CACHE_KEY, words.toArray(new String[0]));
            redis.expire(CACHE_KEY, CACHE_TTL);
        } catch (RuntimeException e) {
            warnRedisDown("回填", e);
        }
    }

    /**
     * 让缓存立即失效（下次读的时候从库里重新装一份）。
     *
     * 为什么是「删掉」而不是文档说的「重建」？
     *   ① **删缓存永远安全，重建缓存不一定**：这三个写方法都在 `@Transactional` 里，
     *      事务随时可能因为后面的审计日志写失败而回滚。删掉的话，下次读会从库里重装，
     *      而库里是回滚之后的真状态，照样对；"重建"则是拿事务里那份**还没提交**的数据
     *      去喂缓存，一旦回滚，缓存就和库不一致了，还要等 30 分钟 TTL 才自愈。
     *   ② 更省：重建要先 SELECT 全表；删掉只是把这次 SELECT 推迟到下次读的时候做。
     *   对使用者的观感完全一样（管理员改完立即生效），所以这是"实现更保守"，不是功能差异。
     *
     * ⚠️ 删失败也**不让管理员的写操作失败**：还有 30 分钟 TTL 兜底，最坏情况是
     *    这 30 分钟内检测结果偏旧，远好过"改个敏感词改不动"。
     */
    private void invalidateCache() {
        try {
            redis.delete(CACHE_KEY);
        } catch (RuntimeException e) {
            warnRedisDown("失效", e);
        }
    }

    /**
     * 缓存失效要等**事务提交之后**再做，不能就地删。
     *
     * 就地删有什么问题？删这个动作本身安全，但**时机**会出岔子：
     * 事务还没提交的这几毫秒里，恰好有别的请求来读、发现缓存没了、从库里装了一份
     * **旧数据**并带上 30 分钟 TTL；紧接着本事务提交 —— 缓存就这么一直错到 TTL 到期。
     * 挂到 afterCommit 之后，任何重建都发生在"新数据已提交"之后，这个窗口就没了。
     *
     * （这是"缓存必须在事务提交后处理"的第二次出现：第一次是幂等的结果缓存，
     *   那次干脆挪到了 Controller 层；这次用 Spring 提供的 afterCommit 回调点解决。）
     */
    private void invalidateAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidateCache();
                }
            });
        } else {
            invalidateCache();   // 没开事务（理论上不会）：直接删
        }
    }

    /** Redis 不可用的日志：命中检测每张工单都要跑，必须节流，否则日志被刷爆 */
    private void warnRedisDown(String action, RuntimeException e) {
        long now = System.currentTimeMillis();
        if (now - lastWarnAt < WARN_INTERVAL_MS) {
            return;
        }
        lastWarnAt = now;
        log.warn("Redis {}敏感词缓存失败，本次改为直接查库（结果一致，只是慢一点）：{}",
                action, e.getMessage());
    }

    // ============ 私有辅助 ============

    /**
     * 把一个词写进库；已存在时不报错，返回这次到底做了什么。
     *
     * 用 `INSERT IGNORE` 而不是"先查再插"或"catch DuplicateKeyException"，原因：
     *   · 先查再插：两步之间有并发窗口（两个管理员同时加同一个词，都会查到"不存在"然后都插 → 一个撞唯一键）；
     *   · catch DuplicateKeyException：**在 @Transactional 里很危险** —— MySQL 一旦报唯一键冲突，
     *     当前事务可能已经进入"部分失败"状态，而且 catch 里如果 return 而不是 throw，
     *     外面的事务照样提交（项目里踩过这个坑）。
     *   · INSERT IGNORE 把冲突交给数据库原子处理，affected=0 就代表"已经有了"，干净。
     *
     * ⚠️ INSERT IGNORE 会**连带吞掉其它错误**（比如外键不存在、值超长被截断）。
     *    所以调用前必须把输入校验做完（已做），而且 creator_id 来自有效 Token（必然存在）。
     *
     * @return {@link #RESULT_INSERTED} / {@link #RESULT_RESTORED} / {@link #RESULT_EXISTS}
     */
    private int addOrEnable(String word, String userId, String userName) {
        int affected = jdbcTemplate.update(
                "INSERT IGNORE INTO t_sensitive_word (word, status, creator_id, creator_name) "
                        + "VALUES (?, 'ACTIVE', ?, ?)", word, userId, userName);
        if (affected > 0) {
            return RESULT_INSERTED;
        }
        // 插入被唯一键忽略 → 这个词已经在库里了。再看一眼它是不是被禁用着。
        int restored = jdbcTemplate.update(
                "UPDATE t_sensitive_word SET status = 'ACTIVE' "
                        + "WHERE word = ? AND status = 'DISABLED'", word);
        return restored > 0 ? RESULT_RESTORED : RESULT_EXISTS;
    }

    /**
     * 把批量输入拆成一个个词。
     *
     * 分隔符不止英文逗号：管理员从 Excel/Word 里粘过来时，常见的是中文逗号（，）、顿号（、）、
     * 分号（；）甚至换行。只认英文逗号的话，粘一整列会变成一个巨大的"词"，很难排查。
     *
     * ⚠️ 这里**只过滤空格子，不过滤超长词** —— 超长的留着，交给调用方报 400 把话说清楚，
     *    静默丢掉会让人对着数字对不上账。
     */
    private List<String> splitWords(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();   // LinkedHashSet = 去重且保持原顺序
        for (String piece : raw.split("[,，、;；\\r\\n\\t]")) {
            String word = piece.trim();
            if (!word.isEmpty()) {
                unique.add(word);
            }
        }
        return new ArrayList<>(unique);
    }

    /**
     * 把词列表拼成一句人能读的描述；超过 max 个就截断。
     * 目的是写进审计的 operation_content（text 字段，虽然装得下，但一次导入几百个词
     * 全列出来没人会看，截断反而更好读 —— 前面的部分 + 总数量足够定位问题）。
     */
    private String joinCapped(List<String> words, int max) {
        if (words.isEmpty()) {
            return "（无）";
        }
        String head = String.join("、", words.subList(0, Math.min(max, words.size())));
        return words.size() > max ? head + "…（等共 " + words.size() + " 个）" : head;
    }

    /** 路径参数转 int；不是数字返回 null（交给调用方报 400） */
    private Integer parseWordId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 查操作人姓名（审计要求 NOT NULL，所以查不到就直接失败） */
    private String loadUserName(String userId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT user_name FROM t_user WHERE user_id = ?", String.class, userId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按词查；不存在返回 null */
    private SensitiveWordItem loadWord(String word) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT word_id, word, status, creator_name, create_time, update_time "
                            + "FROM t_sensitive_word WHERE word = ?", ItemRowMapper.INSTANCE, word);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按 id 查；不存在返回 null */
    private SensitiveWordItem loadWordById(Integer wordId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT word_id, word, status, creator_name, create_time, update_time "
                            + "FROM t_sensitive_word WHERE word_id = ?", ItemRowMapper.INSTANCE, wordId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 敏感词行映射 */
    private static class ItemRowMapper implements RowMapper<SensitiveWordItem> {
        static final ItemRowMapper INSTANCE = new ItemRowMapper();

        @Override
        public SensitiveWordItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            SensitiveWordItem w = new SensitiveWordItem();
            w.setWordId(rs.getInt("word_id"));
            w.setWord(rs.getString("word"));
            w.setStatus(rs.getString("status"));
            w.setCreatorName(rs.getString("creator_name"));
            w.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
            w.setUpdateTime(rs.getObject("update_time", LocalDateTime.class));
            return w;
        }
    }
}
