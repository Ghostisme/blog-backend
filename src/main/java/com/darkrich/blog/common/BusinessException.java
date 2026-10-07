package com.darkrich.blog.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 可预期的业务异常，由 {@link GlobalExceptionHandler} 统一转成 JSON 响应。
 *
 * <p>只用于“调用方可以据此修正请求”的场景（404、参数不合法、冲突等）；
 * 真正的程序错误应直接抛出，让全局处理器记日志并返回 500。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final HttpStatus status;

    public BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, message);
    }

    public static BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, message);
    }

    public static BusinessException conflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, message);
    }

    public static BusinessException tooManyRequests(String message) {
        return new BusinessException(HttpStatus.TOO_MANY_REQUESTS, message);
    }
}
