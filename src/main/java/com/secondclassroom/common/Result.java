package com.secondclassroom.common;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 统一接口返回结构：所有 Controller 都返回这个类。
 * code=0 表示成功；非 0 表示失败（如 401 未认证、403 无权限）。
 */
public class Result<T> {
    private int code;
    private String message;
    private T data;

    public Result() {}

    public Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /** 成功（用默认 message="success"） */
    public static <T> Result<T> success(T data) {
        return new Result<>(0, "success", data);
    }

    /** 成功（自定义提示语，如"登录成功"） */
    public static <T> Result<T> success(String message, T data) {
        return new Result<>(0, message, data);
    }

    /** 失败（带错误码和提示语） */
    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }

    @JsonIgnore
    public boolean isSuccess() { return code == 0; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public T getData() { return data; }
    public void setData(T data) { this.data = data; }
}
