package com.github.feng111627.healthcareconsentgatewayjava.service;

/** 脱敏处理失败。抛出后由上层兜底响应，禁止把原文带出。 */
public class MaskingException extends RuntimeException {
    public MaskingException(String message) {
        super(message);
    }

    public MaskingException(String message, Throwable cause) {
        super(message, cause);
    }
}
