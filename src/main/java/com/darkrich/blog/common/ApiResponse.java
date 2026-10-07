package com.darkrich.blog.common;

/**
 * 统一响应体。
 *
 * <p>{@code code} 直接沿用 HTTP 状态码（成功为 200），而不是另起一套业务码。
 * 这样 HTTP 状态和 body 永远一致，不会出现“HTTP 200 但业务失败”的歧义，前端只需判断一处。
 *
 * @param code    与 HTTP 状态码一致
 * @param message 面向用户/调试的提示；成功时为 "ok"
 * @param data    业务数据，失败时为 null
 * @param <T>     数据类型
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "ok", data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(200, "ok", null);
    }

    public static ApiResponse<Void> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
