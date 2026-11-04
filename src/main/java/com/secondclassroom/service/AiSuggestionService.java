package com.secondclassroom.service;

import com.secondclassroom.common.Result;
import com.secondclassroom.dto.AiSuggestionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 干事端「AI 回复建议」。
 *
 * 设计口径（2026-11-04 定）：
 * 1. **只给草稿，不自动发送**。模型可能编，必须由干事核对后手动发出。
 * 2. **只依据检索到的 FAQ 作答**。prompt 里明确要求"资料没覆盖就说需要核实"，
 *    宁可答"我不确定"，也不要编学时规则。
 * 3. **失败不阻塞、不报错**。没开开关 / 没配密钥 / 模型超时 / 限流 ——
 *    一律降级成 SEARCH_ONLY（把相似 FAQ 的答案拼给干事看），HTTP 仍是 200 + code=0。
 *    工单主流程不能因为一个辅助功能挂掉（和 jieba 重复检测"吞异常返 null"同一个口径）。
 * 4. **默认关闭**（app.ai.enabled 默认 false）。默认值必须指向"不需要外部依赖"的实现 ——
 *    这条是 2026-11-04 线上事故（短信 provider 默认 webhook 导致全站起不来）的直接教训。
 * 5. **不写审计日志**。这是只读辅助操作，不改变任何业务数据；写进 t_operation_log
 *    只会把真正的写操作淹掉（用量走应用日志）。
 */
@Service
public class AiSuggestionService {

    private static final Logger log = LoggerFactory.getLogger(AiSuggestionService.class);

    /** 喂给模型的 FAQ 条数上限。多了没必要，还增加成本和串味风险 */
    private static final int MAX_FAQ = 5;
    /** 历史对话最多带几条 */
    private static final int MAX_HISTORY = 5;
    /** 送进 prompt 的工单正文上限（工单本身 ≤500 字，这里再兜一层） */
    private static final int MAX_CONTENT = 600;

    private static final String SYSTEM_PROMPT = String.join("\n",
            "你是高校「第二课堂学时答疑」服务台的辅助助手，正在帮干事起草一段回复学生的答复。",
            "",
            "必须遵守：",
            "1. 只能依据【参考资料】里的事实作答。资料没覆盖的内容，直接写「这个问题需要核实后回复」，不要猜。",
            "2. 【参考资料】只在**确实与这个问题相关**时才用。不相关的整条忽略 ——",
            "   不要在答复里提到无关的问题，也不要用「另外」「顺便提醒」引到别的话题上。",
            "   宁可只回答一句「需要核实」，也不要把无关资料硬塞进来充数。",
            "3. 不要编造学时规则、申报时间、部门名称、办理流程。不确定就不写。",
            "4. 称呼用「同学你好」，语气与【参考资料】里的答复保持一致，控制在 300 字以内。",
            "5. 只输出答复正文本身。不要加标题、不要加 Markdown 标记、不要写「以下是草稿」这类话。");

    private final JdbcTemplate jdbcTemplate;
    private final DuplicateDetectService duplicateDetectService;

    private final boolean enabled;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final RestClient restClient;

    public AiSuggestionService(JdbcTemplate jdbcTemplate,
                               DuplicateDetectService duplicateDetectService,
                               @Value("${app.ai.enabled:false}") boolean enabled,
                               @Value("${app.ai.base-url:https://api.deepseek.com}") String baseUrl,
                               @Value("${app.ai.api-key:}") String apiKey,
                               @Value("${app.ai.model:deepseek-flash}") String model,
                               @Value("${app.ai.timeout-seconds:20}") int timeoutSeconds) {
        this.jdbcTemplate = jdbcTemplate;
        this.duplicateDetectService = duplicateDetectService;
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.model = model;

        // 连接超时短、读超时长：连接不上要快速失败，模型思考要给足时间。
        // 注意 RestClient.create() 默认**没有任何超时** —— 模型不返回时线程会一直挂在那儿，
        // 干事看到的是按钮转圈转到天荒地老。必须显式设。
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 为某张工单生成回复建议。仅干事 / 管理员可用。
     *
     * 不校验"是不是我接的单"：这个接口**不写库、不改状态**，而干事在列表里本来就能看到
     * 全部待接单和处理中工单，限制归属只会让"帮同事看一眼"变成一次 404，没有安全收益。
     */
    public Result<AiSuggestionResponse> suggest(String userId, String userType, String workOrderId) {
        if (!"STAFF".equals(userType) && !"ADMIN".equals(userType)) {
            return Result.fail(403, "仅干事或管理员可生成回复建议");
        }

        Map<String, Object> order;
        try {
            order = jdbcTemplate.queryForMap(
                    "SELECT work_order_id, category, content, status "
                            + "FROM t_work_order WHERE work_order_id = ?", workOrderId);
        } catch (EmptyResultDataAccessException e) {
            return Result.fail(404, "工单不存在");
        }

        String status = String.valueOf(order.get("status"));
        if ("CLOSED".equals(status) || "MERGED".equals(status)) {
            return Result.fail(409, "该工单已结束，回复建议没有意义");
        }

        String category = String.valueOf(order.get("category"));
        String content = order.get("content") == null ? "" : String.valueOf(order.get("content"));

        List<String> history = recentMessages(workOrderId);
        List<AiSuggestionResponse.Source> sources = searchFaq(content);

        AiSuggestionResponse resp = new AiSuggestionResponse();
        resp.setWorkOrderId(workOrderId);
        resp.setSources(sources);
        resp.setModel(null);

        long start = System.currentTimeMillis();
        try {
            if (!enabled) {
                resp.setMode("SEARCH_ONLY");
                resp.setReason("AI 建议未开启（AI_ENABLED=false），下面是检索到的相关 FAQ");
                resp.setSuggestion(joinSources(sources));
                return Result.success("已找到相关 FAQ（AI 未开启）", resp);
            }
            if (apiKey == null || apiKey.isBlank()) {
                resp.setMode("SEARCH_ONLY");
                resp.setReason("未配置 AI_API_KEY，下面是检索到的相关 FAQ");
                resp.setSuggestion(joinSources(sources));
                return Result.success("已找到相关 FAQ（未配置密钥）", resp);
            }

            String draft = callModel(buildPrompt(category, content, history, sources));
            resp.setMode("AI");
            resp.setSuggestion(draft);
            resp.setModel(model);
            return Result.success("AI 建议已生成，请核对后再发送", resp);
        } catch (Exception e) {
            // 不往上抛：辅助功能失败不该变成 500，更不该阻塞干事回复
            log.warn("AI 建议生成失败 workOrderId={} : {}", workOrderId, e.toString());
            resp.setMode("SEARCH_ONLY");
            resp.setReason("AI 服务暂时不可用，已退回相关 FAQ");
            resp.setSuggestion(joinSources(sources));
            resp.setModel(null);
            return Result.success("AI 暂不可用，已为你找到相关 FAQ", resp);
        } finally {
            resp.setElapsedMs(System.currentTimeMillis() - start);
        }
    }

    // ============ 检索 ============

    /**
     * 拿工单正文去 FAQ 里找最相关的几条。
     * 先走全文检索（t_faq 上有 FULLTEXT(question, keywords) + ngram），
     * 搜不到再用 jieba 关键词做 LIKE 兜底 —— 全文检索对"短问题 + 生僻词"经常给空结果，
     * 一空就直接不参考任何资料，模型就只能瞎猜了。
     */
    private List<AiSuggestionResponse.Source> searchFaq(String text) {
        String q = text == null ? "" : text.trim();
        if (q.isEmpty()) {
            return new ArrayList<>();
        }

        try {
            // 🔴 必须按相关度排序。不排的话 MySQL 按主键顺序返回，
            //    结果是"随便 5 条碰巧命中的 FAQ"，而不是"最相关的 5 条" ——
            //    2026-11-05 本地实测：不排序时 5 条全是同一个问题，模型拿到的资料等于废的。
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT faq_id, question, answer FROM t_faq "
                            + "WHERE status = 'PUBLISHED' "
                            + "AND MATCH(question, keywords) AGAINST(? IN NATURAL LANGUAGE MODE) "
                            + "ORDER BY MATCH(question, keywords) AGAINST(? IN NATURAL LANGUAGE MODE) DESC "
                            + "LIMIT ?", q, q, MAX_FAQ * 4);
            if (!rows.isEmpty()) {
                return toSources(rows);
            }
        } catch (DataAccessException e) {
            log.warn("FAQ 全文检索失败，改用关键词兜底：{}", e.toString());
        }

        List<String> keywords;
        try {
            keywords = duplicateDetectService.keywordsOf(q);
        } catch (RuntimeException e) {
            log.warn("关键词提取失败：{}", e.toString());
            return new ArrayList<>();
        }
        if (keywords == null || keywords.isEmpty()) {
            return new ArrayList<>();
        }

        StringBuilder sql = new StringBuilder(
                "SELECT faq_id, question, answer FROM t_faq WHERE status = 'PUBLISHED' AND (");
        List<Object> args = new ArrayList<>();
        int used = 0;
        for (String kw : keywords) {
            if (used >= 3) {
                break;
            }
            if (used > 0) {
                sql.append(" OR ");
            }
            sql.append("question LIKE ? OR keywords LIKE ?");
            args.add("%" + kw + "%");
            args.add("%" + kw + "%");
            used++;
        }
        sql.append(") LIMIT ?");
        args.add(MAX_FAQ);

        try {
            return toSources(jdbcTemplate.queryForList(sql.toString(), args.toArray()));
        } catch (DataAccessException e) {
            log.warn("FAQ 关键词检索失败：{}", e.toString());
            return new ArrayList<>();
        }
    }

    private List<AiSuggestionResponse.Source> toSources(List<Map<String, Object>> rows) {
        List<AiSuggestionResponse.Source> list = new ArrayList<>();
        // 按问题文本去重：FAQ 库里同一个问题经常被重复录入（本地测试库 176 条里大半是重复），
        // 不去重就会出现"5 条参考资料全是同一句话"，既浪费 prompt 又误导模型。
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Map<String, Object> row : rows) {
            if (list.size() >= MAX_FAQ) {
                break;
            }
            String question = String.valueOf(row.get("question"));
            if (!seen.add(question)) {
                continue;
            }
            list.add(new AiSuggestionResponse.Source(
                    String.valueOf(row.get("faq_id")),
                    question,
                    String.valueOf(row.get("answer"))));
        }
        return list;
    }

    /** 该工单最近几条消息正文。只取 content —— 姓名、ID 都不发给外部模型 */
    private List<String> recentMessages(String workOrderId) {
        try {
            List<String> list = jdbcTemplate.queryForList(
                    "SELECT content FROM t_work_order_message WHERE work_order_id = ? "
                            + "ORDER BY create_time DESC LIMIT ?", String.class, workOrderId, MAX_HISTORY);
            Collections.reverse(list);
            return list;
        } catch (DataAccessException e) {
            log.warn("读工单消息失败 workOrderId={} : {}", workOrderId, e.toString());
            return new ArrayList<>();
        }
    }

    // ============ 提示词与调用 ============

    private String buildPrompt(String category, String content, List<String> history,
                               List<AiSuggestionResponse.Source> sources) {
        StringBuilder sb = new StringBuilder();
        sb.append("【学生问题】\n");
        sb.append("分类：").append(category).append('\n');
        sb.append("正文：").append(truncate(content, MAX_CONTENT)).append("\n\n");

        if (!history.isEmpty()) {
            sb.append("【历史对话】（按时间先后，可能包含学生追问）\n");
            for (int i = 0; i < history.size(); i++) {
                sb.append(i + 1).append(". ").append(truncate(history.get(i), 200)).append('\n');
            }
            sb.append('\n');
        }

        sb.append("【参考资料】\n");
        if (sources.isEmpty()) {
            sb.append("（没有检索到相关的常见问题）\n");
        } else {
            for (int i = 0; i < sources.size(); i++) {
                AiSuggestionResponse.Source s = sources.get(i);
                sb.append(i + 1).append(". 问：").append(s.getQuestion()).append('\n');
                sb.append("   答：").append(s.getAnswer()).append('\n');
            }
        }
        sb.append("\n请起草一段回复。");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String callModel(String prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0.3);
        body.put("max_tokens", 600);
        body.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", prompt)));

        Map<String, Object> resp = restClient.post()
                .uri(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        if (resp == null) {
            throw new IllegalStateException("模型返回空响应");
        }
        Object choicesObj = resp.get("choices");
        if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) {
            throw new IllegalStateException("模型未返回 choices");
        }
        Object messageObj = ((Map<String, Object>) choices.get(0)).get("message");
        if (!(messageObj instanceof Map<?, ?> message)) {
            throw new IllegalStateException("模型未返回 message");
        }
        Object contentObj = message.get("content");
        String text = contentObj == null ? "" : contentObj.toString().trim();
        if (text.isEmpty()) {
            throw new IllegalStateException("模型返回了空内容");
        }
        return truncate(text, 1000);
    }

    /** 没调模型时给干事看的"参考资料版草稿" */
    private String joinSources(List<AiSuggestionResponse.Source> sources) {
        if (sources.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sources.size(); i++) {
            if (i > 0) {
                sb.append("\n\n");
            }
            sb.append("【").append(sources.get(i).getFaqId()).append("】")
              .append(sources.get(i).getQuestion()).append('\n')
              .append(sources.get(i).getAnswer());
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
