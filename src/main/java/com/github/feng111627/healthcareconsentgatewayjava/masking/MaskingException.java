package com.github.feng111627.healthcareconsentgatewayjava.masking;

/** 脱敏处理失败时抛出，调用方必须保证不把原始数据带出去。 */
public class MaskingException extends RuntimeException {
    public MaskingException(String message, Throwable cause) {
        super(message, cause);
    }
}
