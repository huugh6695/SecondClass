package com.secondclassroom.common;

/**
 * 「幂等校验失败」专用异常（Redis 不可用等）。
 *
 * <p>为什么不用普通 RuntimeException？和 {@link SeqIdException} 是同一个原因：
 * Spring Data Redis 的连接异常（RedisConnectionFailureException）**继承自
 * DataAccessException**，而 GlobalExceptionHandler 里有一条
 * {@code @ExceptionHandler(DataAccessException.class)} 会把它接走、
 * 改写成「数据库操作失败，请稍后重试」—— 于是"Redis 没启动"被伪装成"数据库出错"。
 *
 * <p>所以这里用自定义类型单独标出来，在全局处理器里给它一条专属分支，把真相透出去。
 * 它**故意不继承** DataAccessException，正是为了不被那条分支抢走。
 */
public class IdempotencyException extends RuntimeException {

    public IdempotencyException(String message) {
        super(message);
    }

    public IdempotencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
