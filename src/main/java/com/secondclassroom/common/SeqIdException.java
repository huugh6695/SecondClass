package com.secondclassroom.common;

/**
 * 「单号生成失败」专用异常。
 *
 * <h3>为什么要单独定义一个类型，而不是直接 throw new RuntimeException(...)？</h3>
 * 因为 Spring Data Redis 连不上时抛的 {@code RedisConnectionFailureException}
 * **本身就是 {@code DataAccessException} 的子类**，而全局异常处理器里有一条
 * {@code @ExceptionHandler(DataAccessException.class)} 会先把它接走，
 * 并把消息写死成「数据库操作失败，请稍后重试」。
 * 结果就是：Redis 没启动，报出来的却是"数据库出错" —— 排查方向直接跑偏。
 *
 * <p>所以这里用一个**自定义类型**把"单号生成失败"这件事单独标出来，
 * 在 GlobalExceptionHandler 里给它一条专属分支，把真正的原因透给调用方。
 * 注意它**故意不继承** DataAccessException —— 正是为了不被那条分支抢走。
 *
 * <p>它继承的是 RuntimeException（非受检），所以业务代码里不用层层声明 throws。
 */
public class SeqIdException extends RuntimeException {

    public SeqIdException(String message) {
        super(message);
    }

    public SeqIdException(String message, Throwable cause) {
        super(message, cause);
    }
}
