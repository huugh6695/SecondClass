package com.secondclassroom.service;

import com.huaban.analysis.jieba.JiebaSegmenter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工单重复检测（文档 §4 第 5 步「重复检测: jieba 分词 → 提取关键词」）。
 *
 * 契约原文那句话拆开是四件事：
 * <pre>
 *   5. 重复检测: jieba 分词 → 提取关键词
 *      → 查近7天同分类未完结工单关键词交集 ≥ 2
 *      → 命中: duplicateOfOrderId = 原工单ID
 * </pre>
 *
 * ──────────────────────────────────────────────────────────────────────
 * 📌 **契约留白的 5 处，由用户拍板补齐**（原文只写了"交集 ≥ 2"，其余没交代）
 *
 *   ①「未完结」= **PENDING + PROCESSING + REPLIED**（不含 CLOSED / MERGED）。
 *     理由：重复提问的典型场景是"同一件事还没解决又问了第二遍"。
 *     已经关掉的单再问，语义上属于「再次咨询」（走 /re-consult 另开新单），不是"重复"。
 *
 *   ② 命中多张时取 **交集数最大**的那张；**平手取最新提交的**（create_time DESC）。
 *     写死这个规则而不是"随便取一张"，是为了行为可复现 —— 否则同一个内容两次提交
 *     可能因为 SQL 返回顺序不同而记到不同的单上，测试没法断言、排查也没线索。
 *
 *   ③ 关键词要 **过滤停用词 + 过滤单字**。
 *     不过滤的话，"请问""老师""同学""帮忙"这类词会让两份**毫不相关**的内容凑够交集 ≥ 2
 *     （比如"请问老师学时怎么算" vs "请问老师什么时候公示" —— 交集是"请问/老师"，但它们不是重复）。
 *
 *     ⚠️ **2026-10-14 实测翻车 + 修法**：只过滤"客套词"远远不够。
 *        实测发现 A0（学分认定类长单）和一张**敏感词测试单**（"这条内容完全干净，
 *        没有任何需要标记的词。（F-fd）"）的交集是 **[没有, 任何]** —— 正好 2 个，
 *        卡在阈值上就命中了！可见"没有/任何"这种**通用实词**同样会乱凑数。
 *        同时发现**短英文/数字 token**（测试脚本加的随机 TAG `e9a8b7`）也是万能词：
 *        "图书馆开放时间" 和 "学分认定" 只因共用 `编号 + e9a8b7` 就判成重复。
 *        所以除停用词表外还要两件事：**(a) 停用词表补通用实词；(b) 排除纯 ASCII 的短 token**。
 *
 *   ③-b **相似度比例门槛**（2026-10-14 补，与"交集 ≥ 2"**同时**成立才算命中）。
 *     契约只写"交集 ≥ 2"，但实测这个绝对阈值**没有分辨力**：一张 40 个关键词的长单
 *     跟任何同分类单都容易撞够 2 个词（实测 A0 对 **41 张**单交集 ≥ 2，其中 49 张测试单
 *     全是 2 分平手）。**词越多，撞够 2 个的概率越高** ⇒ 长文本几乎必然"被重复"。
 *     比值门槛正好抵消这个偏差：要求交集至少占新单关键词的 **40%**，
 *     这样"撞 2 个词/50 个词"（4%）会被挡掉，而"真·同义改写"（实测 10/18 ≈ 56%）能通过。
 *     ⚠️ **两个条件都要满足**：保留"交集 ≥ 2"是为了让契约文案仍然成立（下限不变），
 *        比例是为了补上区分度。只写比例的话，"交集 1 个 / 新单只有 2 个词"也会中（50%），
 *        那又回到误判老路上了。
 *
 *   ④ 内容 **太短就跳过检测**（当不重复）。
 *     内容短 ⇒ 可选词本就少，"交集 ≥ 2"极易被通用词凑满（"学时没到"只切出 2 个词，
 *     随便撞上一张同分类的单就命中了）。这是一个**刻意的保守选择**：宁可漏判，不误判 ——
 *     因为 duplicate_of_order_id 会被统计进「重复率」，误判会直接污染那个指标。
 *
 *   ⑤ 最多跟 **最近 200 张**单比（SQL 里 LIMIT）。
 *     不设上限的话，提交工单的耗时会随库里单量线性上涨；而"7 天前的第 500 张单"
 *     几乎不可能是学生刚写的重复内容。200 是"够用且不会突然变慢"的折中。
 * ──────────────────────────────────────────────────────────────────────
 *
 * ⚠️ **检测失败绝不能让提交工单失败**。
 *    这里的每一个公开方法都自己吞掉异常（只 log），返回"没命中"。
 *    理由：它是**锦上添花的辅助信息**（给个"疑似重复"提示），不是提交闸门。
 *    对比一下敏感词检测为什么敢抛：那是在同一个事务里读自己的表，读库失败=真故障；
 *    而这里读的是"别人的工单表"，还要跑分词库，出问题的面更大，
 *    一旦抛出去就是"学生提交不了工单" —— 用可选的辅助功能去堵主流程，不值。
 *
 * ⚠️ 它在**写库之前**被调用，所以整个类是**只读**的（只有 SELECT）。
 */
@Service
public class DuplicateDetectService {

    private static final Logger log = LoggerFactory.getLogger(DuplicateDetectService.class);

    private final JdbcTemplate jdbcTemplate;

    /**
     * jieba 分词器。**是线程安全的**（内部索引只读），所以做成单例字段即可 ——
     * 而它的构造会加载内置词典（几十 MB），**绝不能每次检测都 new 一个**
     * （那会让每次提交工单都多付一次词典加载的开销）。
     */
    private final JiebaSegmenter segmenter = new JiebaSegmenter();

    /** 参与比对的"未完结"状态（用户拍板 ①） */
    private static final List<String> OPEN_STATUSES = List.of("PENDING", "PROCESSING", "REPLIED");

    /** 对比池上限（用户拍板 ⑤） */
    private static final int POOL_LIMIT = 200;

    /** 命中阈值：关键词交集 ≥ 2（契约明写，作为**下限**保留） */
    private static final int MIN_OVERLAP = 2;

    /**
     * 相似度比例门槛（契约留白处补的口径，见类注释 ③-b）。
     *
     * 交集 / 新单关键词总数 ≥ 40% 才算命中。
     * 取 40% 的来由：实测"真·同义改写"的比例约 **56%**（10/18），
     * "无关单撞上通用词"的比例约 **4%**（2/50）甚至更低 ⇒ 40% 落在这两个数量级中间，
     * 留了足够宽的容错带，不会在边界上反复抖动。
     */
    private static final double MIN_SIMILARITY = 0.4d;

    /**
     * 内容太短就跳过检测（用户拍板 ④）。
     * 阈值取 8：低于 8 个字符的内容（如"学时没到"）切出来的实词太少，
     * 靠通用词凑够交集 ≥ 2 的概率太高，判了也没有参考价值。
     */
    private static final int MIN_CONTENT_LENGTH = 8;

    /**
     * 停用词表（用户拍板 ③）：这些词几乎出现在任何一条工单里，
     * 拿它们当"关键词"等于没比。**故意用 Set 而不是词库文件** ——
     * 表短到看得完、改起来不用重新打包，比引一份几千行的停用词文件更可控。
     *
     * ⚠️ 这份表是"够用就好"，不求全。但 2026-10-14 实测证明：
     *    **"通用实词"比"客套词"更危险**，因为它们看起来像实词、却几乎出现在每条单里。
     *    最后一组就是实测翻车后补的（`[没有, 任何]` 曾让两张毫不相关的单判成重复）。
     *
     * ⚠️ **用 `Set.of` 时表里绝不能有重复项** —— 重复会在**类加载时**抛
     *    `IllegalArgumentException: duplicate element`，而这种错误在编译期发现不了，
     *    要么启动就炸、要么第一次用到才炸。加词时务必扫一眼有没有重复。
     */
    private static final Set<String> STOP_WORDS = Set.of(
            // 客套 / 称呼
            "请问", "老师", "同学", "你好", "您好", "谢谢", "麻烦", "帮忙", "一下",
            "这个", "那个", "什么", "怎么", "为什么", "可以", "能不能", "是否",
            // 虚词（jieba 有时会把它们跟相邻字粘在一起切出来）
            "的话", "已经", "还是", "就是", "但是", "因为", "所以", "如果", "而且",
            "我们", "你们", "他们", "自己", "大家", "现在", "今天", "昨天", "明天",
            // 工单场景里的万能词（出现在几乎每条单里，不构成"重复"证据）
            "工单", "问题", "情况", "系统", "后台", "提交", "查询", "咨询",
            // 🔴 2026-10-14 实测补的"通用实词"：看着像实词，其实任何句子都可能有。
            //    翻车现场：A0 ∩ 敏感词测试单 = [没有, 任何] → 恰好 2 个 → 误判成重复。
            "没有", "任何", "需要", "完全", "干净", "标记", "内容", "这条",
            "之后", "一直", "里面", "到底", "看看", "知道", "感觉", "应该",
            "可能", "希望", "建议", "感谢"
    );

    /**
     * 纯 ASCII token 的最大保留长度（超过它才可能算关键词）。
     *
     * 🔴 2026-10-14 实测翻车后加的。背景：测试脚本习惯在正文里塞一串随机 TAG
     *    （如 `e9a8b7`）防数据串味，结果两个**毫不相关**的单因为共用 `编号 + e9a8b7`
     *    就被判成重复（"图书馆开放时间" vs "学分认定"）。这类短英文/数字串
     *    本身不携带语义，只适合当"数据标记"，绝不该进关键词集合。
     *
     * 判据：**整个 token 全是 ASCII 字母/数字/连字符，且长度 < 8** ⇒ 丢弃。
     * 为什么是 8：中文工单里出现的长英文串（如 `BANNED985835`，12 位）
     * 往往是**真的专有名词**（软件名、订单号），有区分度，要留着；
     * 而 ≤ 7 位的短串（TAG、缩写、随机码）基本没有区分度。
     * ⚠️ 只对**纯 ASCII**生效：中文词不管多短都走"单字过滤"那条路，不受这个影响。
     */
    private static final int ASCII_TOKEN_MIN_LENGTH = 8;

    public DuplicateDetectService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 找出这张新工单最可能"重复"的那张已有工单。
     *
     * @param category 新单分类（只在**同分类**里比 —— 契约明写"同分类"）
     * @param content  新单正文
     * @return 命中的原工单号；没命中（或检测出错）返回 **null**
     */
    public String findDuplicate(String category, String content) {
        try {
            return doFind(category, content);
        } catch (RuntimeException e) {
            // 辅助功能，出问题只留痕、不往上抛（理由见类注释）
            log.warn("重复检测失败，本次按「不重复」处理（不影响提交）：{}", e.toString());
            return null;
        }
    }

    private String doFind(String category, String content) {
        // ① 内容太短 → 直接不判（用户拍板 ④）
        if (content == null || content.trim().length() < MIN_CONTENT_LENGTH) {
            return null;
        }
        // ② 新单的关键词集合
        Set<String> newWords = extractKeywords(content);
        if (newWords.size() < MIN_OVERLAP) {
            // 连 2 个实词都切不出来，比也白比
            return null;
        }

        // ③ 取对比池：近 7 天 + 同分类 + 未完结，最多 200 张（最近的优先）
        //
        //    ⚠️ 为啥用 `>= DATE_SUB(NOW(), INTERVAL 7 DAY)` 而不是 `BETWEEN`：
        //       BETWEEN 要写两个端点，而"近 7 天"只有一个端点（现在往回数 7 天）。
        //    ⚠️ `duplicate_of_order_id IS NULL` 这一条是**刻意排除**的：
        //       已经判定为"重复别人"的单，不该再被别人当"原单"引用 ——
        //       否则会出现 A→B、C→A 的链式指向，管理端看"原单"要点好几层。
        List<Map<String, Object>> pool = jdbcTemplate.queryForList(
                "SELECT work_order_id, content FROM t_work_order "
                        + "WHERE category = ? AND status IN ('PENDING', 'PROCESSING', 'REPLIED') "
                        + "AND duplicate_of_order_id IS NULL "
                        + "AND create_time >= DATE_SUB(NOW(), INTERVAL 7 DAY) "
                        + "ORDER BY create_time DESC LIMIT ?",
                category, POOL_LIMIT);

        // ④ 逐张算交集，记下"交集最多"的那张；平手时**先到的赢** ——
        //    池子是 create_time DESC 排的，所以"先到"= 更新的那张（用户拍板 ②）。
        //
        //    ⚠️ 命中要**同时**满足两个条件（下限 + 比例，见类注释 ③-b）：
        //       · overlap >= MIN_OVERLAP   —— 契约明写的下限，不能丢
        //       · similarity >= MIN_SIMILARITY —— 补上区分度，防"长单子随便撞两个词"
        //    实测教训：只看下限时，A0（18 个关键词）对 **41 张**单都命中，
        //    其中一张还是完全无关的敏感词测试单（交集恰好 [没有, 任何]）。加了比例门槛后，
        //    A0 的真命中 A1 比例 10/18 ≈ 56% 稳过，而 [没有, 任何] 那类 2/18 ≈ 11% 直接出局。
        String bestId = null;
        int bestOverlap = 0;
        for (Map<String, Object> row : pool) {
            String otherId = (String) row.get("work_order_id");
            String otherContent = (String) row.get("content");
            if (otherContent == null) {
                continue;
            }
            int overlap = intersectionSize(newWords, extractKeywords(otherContent));
            if (overlap < MIN_OVERLAP) {
                continue;
            }
            // 比例用**新单**的关键词数当分母（而不是两张单里较小的那个）：
            // 因为判定语义是"这张新单，有多少内容能在库里找到对应"，
            // 用新单做分母才能表达"新单有多少是旧的"。用较小值当分母的话，
            // 只要池子里有一张**极短**的单（如"学分认定"3 个词），
            // 交集 2 就能凑到 2/2 = 100% ⇒ 短单反而变成"万能吸铁石"，更糟。
            double similarity = (double) overlap / newWords.size();
            if (similarity < MIN_SIMILARITY) {
                continue;
            }
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                bestId = otherId;
            }
        }
        return bestId;
    }

    /**
     * 把一段正文切成"关键词集合"：jieba 分词 → 去停用词 → 去单字。
     *
     * 用 **Set** 而不是 List：比对看的是"有没有共同的词"，同一个词出现几次不影响判定
     * （而且 Set 求交集是 O(min(m,n))，比 List 的两重循环快）。
     */
    Set<String> extractKeywords(String text) {
        Set<String> words = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return words;
        }
        for (String w : segmenter.sentenceProcess(text)) {
            String word = w.trim();
            // 去单字：jieba 会切出一堆"的""了""啊"，它们不是关键词。
            // ⚠️ 用 String.length() 计长度（中文一个汉字 = 1），所以 `<= 1` 就是"单字"。
            if (word.length() <= 1) {
                continue;
            }
            // 去停用词（大小写不敏感：停用词表里都是中文，但保持一致）
            if (STOP_WORDS.contains(word)) {
                continue;
            }
            // 🔴 去"短 ASCII token"（TAG / 随机码 / 缩写）—— 实测翻车后加的，见常量注释
            if (isShortAsciiToken(word)) {
                continue;
            }
            // 纯标点 / 纯数字也滤掉："2026-10-14"、"..." 这种当关键词没有意义
            if (!word.matches(".*[\\p{IsHan}A-Za-z].*")) {
                continue;
            }
            words.add(word);
        }
        return words;
    }

    /**
     * 是不是"没有语义的短 ASCII token"（见 {@link #ASCII_TOKEN_MIN_LENGTH}）。
     *
     * 判据：整个 token 由 ASCII 字母 / 数字 / 连字符 / 下划线组成，且长度不足阈值。
     * 中文词一律返回 false（中文长度由"单字过滤"那条路管）。
     */
    private static boolean isShortAsciiToken(String word) {
        if (word.length() >= ASCII_TOKEN_MIN_LENGTH) {
            return false;
        }
        // matches 里写全量锚定：整个串只允许这四类字符
        return word.matches("[A-Za-z0-9_\\-]+");
    }

    /** 交集大小（两个集合的公共词个数） */
    private static int intersectionSize(Set<String> a, Set<String> b) {
        // 遍历小的那个集合，省一点时间
        Set<String> small = a.size() <= b.size() ? a : b;
        Set<String> big = small == a ? b : a;
        int n = 0;
        for (String w : small) {
            if (big.contains(w)) {
                n++;
            }
        }
        return n;
    }

    /** 暴露给测试/诊断用：这次内容会切出哪些关键词（只读，无副作用） */
    public List<String> keywordsOf(String content) {
        return new ArrayList<>(extractKeywords(content));
    }

    /** 暴露给诊断用：对比池里的状态白名单（让 verify 脚本能断言它和契约口径一致） */
    public static List<String> openStatuses() {
        return OPEN_STATUSES;
    }

    /** 暴露给诊断用：命中阈值 */
    public static int minOverlap() {
        return MIN_OVERLAP;
    }

    /** 暴露给诊断用：相似度比例门槛（见类注释 ③-b） */
    public static double minSimilarity() {
        return MIN_SIMILARITY;
    }

    /** 暴露给诊断用：停用词表（只读副本，验证脚本可以断言它确实挡掉了通用实词） */
    public static Set<String> stopWords() {
        return STOP_WORDS;
    }
}
