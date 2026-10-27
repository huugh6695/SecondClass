package com.secondclassroom.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器：把没被业务代码接住的异常，统一翻译成 Result 格式。
 *
 * 为什么需要它？之前接口出错时，浏览器/IDEA 会看到 Spring 的默认错误页
 * （{"timestamp":...,"status":500,"error":"Internal Server Error","path":...}），
 * 前端拿到这种结构完全没法处理，因为它和后端约定的 { code, message, data } 长得不一样。
 * 有了这个类以后，**不管是哪种异常，前端永远只会收到同一个结构**。
 *
 * 项目约定：HTTP 状态码固定 200，真正的结果码放在 body 的 code 字段里。
 * 所以这里所有方法都直接 return Result，不加 @ResponseStatus。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 我们自己主动抛的并发冲突（reply / followUp 里 affected == 0 时 throw 的那个）。
     *
     * 为什么不归到 500？因为这类异常不是"程序崩了"，而是**业务上的乐观锁冲突**：
     * 说明工单刚好被别人改了。语义上就是"请刷新后重试"，所以给 409 比 500 有用得多。
     */
    @ExceptionHandler(IllegalStateException.class)
    public Result<Void> handleIllegalState(IllegalStateException e) {
        log.warn("业务状态冲突: {}", e.getMessage());
        return Result.fail(409, "工单状态已被他人变更，请刷新后重试");
    }

    /**
     * 请求体 JSON 解析失败 —— 这是最容易踩的坑，单独给它一条人话提示。
     * 常见原因：用了中文引号 “ ” 而不是英文引号 " "、少了逗号/括号、字段名拼错。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.fail(400, "请求体格式错误：JSON 解析失败，请检查是否用了中文引号、括号是否配对");
    }

    /** 必填的查询参数没传（比如 ?page= 漏了） */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return Result.fail(400, "缺少请求参数：" + e.getParameterName());
    }

    /** 参数类型不对（比如 page 传了 abc，没法转成整数） */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return Result.fail(400, "参数类型错误：" + e.getName());
    }

    /** 请求方法不对（比如该 POST 的接口用 GET 访问了） */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return Result.fail(405, "请求方法不支持：" + e.getMethod());
    }

    /**
     * 上传的文件超过 multipart 硬限制（application.yml 的 spring.servlet.multipart.max-file-size）。
     * Spring 默认只有 1MB，不处理的话前端会拿到一个 500，看不出是"文件太大"。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件超过大小限制: {}", e.getMessage());
        // 不写死具体 MB 数：限制值配在 application.yml（spring.servlet.multipart.max-file-size），
        // 写死在这里改配置时就会对不上。精确数字由业务层的第二道校验给出。
        return Result.fail(400, "文件超过大小限制，请压缩后再上传");
    }

    /** 上传时表单里没有 file 字段 */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public Result<Void> handleMissingPart(MissingServletRequestPartException e) {
        return Result.fail(400, "缺少上传文件：表单字段名应为 " + e.getRequestPartName());
    }

    /**
     * 其他 multipart 解析问题（最典型：用 JSON 的 Content-Type 调上传接口）。
     * 注意 MaxUploadSizeExceededException 也是 MultipartException 的子类，
     * Spring 会优先匹配上面那个更具体的处理器，不会落到这里。
     */
    @ExceptionHandler(MultipartException.class)
    public Result<Void> handleMultipart(MultipartException e) {
        log.warn("非法的 multipart 请求: {}", e.getMessage());
        return Result.fail(400, "上传请求格式错误：请使用 multipart/form-data 并带上 file 字段");
    }

    /** 访问了不存在的接口地址 */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public Result<Void> handleNotFound(Exception e) {
        return Result.fail(404, "接口不存在");
    }

    /**
     * Redis 依赖不可用导致的操作失败：单号生成失败（SeqIdException）/
     * 幂等校验失败（IdempotencyException）。
     *
     * 为什么必须单开一条分支？因为 Spring Data Redis 的连接异常
     * （RedisConnectionFailureException）**本身就是 DataAccessException 的子类**，
     * 会被下面的 handleDataAccess 抢走、改写成「数据库操作失败，请稍后重试」——
     * 于是"Redis 没起"被伪装成"数据库出错"，排查方向直接跑偏。
     * 用自定义异常单独接住，才能把真正的原因透出来。
     *
     * 注意：这里直接回 e.getMessage()，因为消息是 DailySeqId / IdempotencyStore
     * 自己拼的，里面已经写清「什么失败了 + 哪个 key/requestId + 让用户去启 Redis + 底层原因」。
     */
    @ExceptionHandler({SeqIdException.class, IdempotencyException.class})
    public Result<Void> handleRedisUnavailable(RuntimeException e) {
        log.error("Redis 依赖不可用", e);
        return Result.fail(500, e.getMessage());
    }

    /**
     * 数据库异常（SQL 写错、连接断了、字段超长、外键不存在……）。
     * 这类错误要打完整堆栈，不然排查起来毫无线索。
     */
    @ExceptionHandler(DataAccessException.class)
    public Result<Void> handleDataAccess(DataAccessException e) {
        log.error("数据库操作失败", e);
        return Result.fail(500, "数据库操作失败，请稍后重试");
    }

    /**
     * 兜底：前面都没接住的异常全走这里，保证前端拿到的永远是 Result 结构。
     * 注意它必须放在最后 —— Spring 会优先匹配"最具体"的那个 handler。
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("未捕获的系统异常", e);
        return Result.fail(500, "服务器内部错误，请稍后重试");
    }
}
