package com.secondclassroom.interceptor;

import com.secondclassroom.common.TokenBlacklist;
import com.secondclassroom.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT 拦截器（守门员）：在每个需要登录的请求到达 Controller 之前先检查 Token。
 * - 没有 / 格式不对的 Token → 401
 * - 在黑名单里（已登出）→ 401。**唯一例外是 `/api/auth/logout` 自己** ——
 *   "拿已作废的 Token 再登出一次"是幂等操作，直接回成功（见下面第 2 步的说明）
 * - 签名不对 / 过期 → 401
 * - **账号已被禁用（t_user.status = DISABLED）→ 403**（文档 §1.10 要求"每次请求校验"）
 * - 通过 → 把 userId、userType 放进 request，Controller 直接用
 *
 * 「可选 Token」的接口（FAQ 的列表+详情、公告列表）：
 *   文档把这些接口标成「可选 Token」—— 没登录也要能看（游客查常见问题、看首页公告栏），
 *   但登录了并且是管理员时，要多看到未发布（UNPUBLISHED）的内容 / 已归档的公告。
 *   所以对它们：
 *     · 没带 Token      → 放行，但不设属性（Controller 读到 null，当游客处理）；
 *     · Token 过期/失效 → **也放行**，同样当游客 —— 因为公开页面不该因为 Token 过期就整个报错
 *                        （否则管理员挂着过期 Token 打开 FAQ 会看到 401，而学生却正常，很怪）。
 *   注意这**不是**"不安全"：能多看的内容只有管理员该看的那些，而判断依据是"Token 里的角色"，
 *   游客身份下永远只能看到 PUBLISHED。
 *
 * 哪些路径算"可选 Token"，见下面的 OPTIONAL_AUTH_PREFIXES —— 只匹配前缀本身及其子路径，
 * 不会误伤 /api/admin/faqs、/api/admin/announcements（那些仍然强制要管理员 Token）。
 *
 * 补充一个容易搞混的对照：`GET /api/templates`（快捷回复模板）文档标的是「**需** Token」，
 * 不是"可选" —— 模板是给干事回复时套用的，游客没有使用场景，所以让它走默认的强制校验，
 * **不要**往 OPTIONAL_AUTH_PREFIXES 里加。
 */
@Component
public class JwtInterceptor implements HandlerInterceptor {

    /**
     * 「可选 Token」的路径前缀：匿名放行，带了有效 Token 就按登录处理。
     * 目前两个：FAQ（学生自助查询）+ 公告（首页公告栏，打开网站第一眼就该看到）。
     */
    private static final String[] OPTIONAL_AUTH_PREFIXES = {"/api/faqs", "/api/announcements"};

    /**
     * 登出接口自己。它是黑名单校验唯一的例外 —— 见下面第 2 步里的说明。
     */
    private static final String LOGOUT_URI = "/api/auth/logout";

    private final JwtUtil jwtUtil;
    private final TokenBlacklist blacklist;
    private final JdbcTemplate jdbcTemplate;

    public JwtInterceptor(JwtUtil jwtUtil, TokenBlacklist blacklist, JdbcTemplate jdbcTemplate) {
        this.jwtUtil = jwtUtil;
        this.blacklist = blacklist;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        boolean optional = isOptionalAuth(request.getRequestURI());

        // 1. 取请求头里的 Authorization
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            if (optional) {
                return true;   // 可选 Token 的接口：游客直接放行（不设 userId/userType）
            }
            writeJson(response, 401, "未登录或 Token 格式错误");
            return false;
        }
        String token = authHeader.substring(7); // 去掉开头的 "Bearer "

        // 2. 黑名单校验（登出后的 Token 直接拒绝）
        if (blacklist.contains(token)) {
            if (optional) {
                return true;   // 可选 Token：Token 失效不影响匿名访问
            }
            // 例外：**登出接口自己**。
            //
            // 它的语义是"把这个 Token 作废"。而"拿一个已经被作废的 Token 再作废一次"
            // 结果和第一次完全相同 ⇒ 这天然是个幂等操作，就该成功。
            //
            // 如果在这里拦成 401，会发生什么：前端点"退出登录"拿到 401，以为没退成功，
            // 于是弹错误提示 / 反复重试；而真实情况是它早就退干净了（Token 确实已作废）。
            // 这属于**用错误码把"已经达成目标"报成"失败"**，纯粹制造困惑。
            //
            // 所以直接回一个正常成功响应就收工：不往下走（不查库、不写第二遍审计 ——
            // 第一次登出已经记过了，重复登出不该在审计表里刷出一串 LOGOUT）。
            if (LOGOUT_URI.equals(request.getRequestURI())) {
                writeSuccess(response, "退出成功");
                return false;
            }
            writeJson(response, 401, "Token 已失效，请重新登录");
            return false;
        }

        // 3. 校验签名与有效期
        if (!jwtUtil.validateToken(token)) {
            if (optional) {
                return true;   // 同上：按游客处理，不因为 Token 过期把公开页面也打挂
            }
            writeJson(response, 401, "Token 无效或已过期");
            return false;
        }

        // 4. **账号状态校验**（文档 §1.10 安全规范："用户禁用 → 每次请求校验 t_user.status = ACTIVE，
        //    DISABLED → 403"）。
        //
        //    为什么非要有这一步：JWT 是**无状态**的 —— 签出去之后服务端就不再管它了。
        //    光靠登录时校验一次的话，管理员禁用某个账号后，那个人手上的 Token 在过期前**照样能用**，
        //    "禁用"就成了一句空话。所以每个请求都回头查一眼数据库里的状态。
        //
        //    代价：每个请求多一次主键查询（user_id 是主键，走聚簇索引，开销可忽略）。
        //    文档的完整设计里这一层可以用 Redis 缓存账号状态，等第⑧阶段接入 Redis 再优化；
        //    现在**宁可多查一次库也要保证"禁用立即生效"**。
        //
        //    注意这里**不看 optional**：可选 Token 的接口（FAQ/公告）虽然匿名能访问，
        //    但一个"已禁用账号的有效 Token"应该明确报错，而不是悄悄降级成游客 ——
        //    静默降级会让人以为"登录了却看不到更多内容"，很难排查。
        String userId = jwtUtil.getUserId(token);
        String userType = jwtUtil.getUserType(token);

        UserSecurityState securityState = queryUserSecurityState(userId);
        if (securityState == null) {
            // Token 签名有效、但库里已经没有这个人了（比如测试数据被清理过）→ 当作未认证
            writeJson(response, 401, "账号不存在，请重新登录");
            return false;
        }
        if ("DISABLED".equals(securityState.status())) {
            writeJson(response, 403, "账号已禁用");
            return false;
        }
        if (securityState.tokenVersion() != jwtUtil.getTokenVersion(token)) {
            writeJson(response, 401, "账号认证状态已更新，请重新登录");
            return false;
        }

        // 5. 解析出当前登录用户，放进 request，供后续 Controller 读取
        request.setAttribute("userId", userId);
        request.setAttribute("userType", userType);
        request.setAttribute("token", token); // 供 /logout 加入黑名单时使用

        return true; // 放行，请求继续走到 Controller
    }

    /** 查用户当前状态；用户不存在返回 null（交给调用方决定报 401 还是别的） */
    private UserSecurityState queryUserSecurityState(String userId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT status, token_version FROM t_user WHERE user_id = ?",
                    (rs, rowNum) -> new UserSecurityState(
                            rs.getString("status"), rs.getInt("token_version")),
                    userId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private record UserSecurityState(String status, int tokenVersion) {}

    /**
     * 这个请求的路径是否属于「可选 Token」。
     *
     * 判断用 equals 或 "前缀 + /"，而不是简单的 startsWith 字符串 ——
     * 否则将来有个 /api/faqs-archive 之类的路径会被顺带放行。
     * ⚠️ /api/admin/faqs、/api/admin/announcements **不**以 /api/faqs、/api/announcements 开头
     *    （中间隔着 /admin），所以不受影响，仍然强制要管理员 Token。
     */
    private boolean isOptionalAuth(String uri) {
        for (String prefix : OPTIONAL_AUTH_PREFIXES) {
            if (uri.equals(prefix) || uri.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    /** 拦截器里没法用 Result 自动序列化，所以手动拼一个 JSON 写回 */
    private void writeJson(HttpServletResponse response, int code, String message) throws Exception {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        String body = "{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}";
        response.getWriter().write(body);
    }

    /**
     * 拼一个"业务成功"的响应：**HTTP 200 + `code=0`**。
     *
     * ⚠️ 不能拿 writeJson 顶替 —— 它把 HTTP 状态码和 `code` 字段写成同一个值
     *    （那是给 401/403 用的，两者恰好相等）。而项目约定是"**业务成功一律 HTTP 200 + code=0**"，
     *    传 200 进去会拼出 `{"code":200}`，前端按 code===0 判断就会认成失败。
     */
    private void writeSuccess(HttpServletResponse response, String message) throws Exception {
        response.setStatus(200);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":0,\"message\":\"" + message + "\",\"data\":null}");
    }
}
