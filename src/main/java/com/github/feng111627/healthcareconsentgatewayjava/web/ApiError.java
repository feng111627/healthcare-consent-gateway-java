package com.github.feng111627.healthcareconsentgatewayjava.web;

/** 统一错误体：只携带可区分的错误码与可读信息，不含患者原文。 */
public record ApiError(String code, String message) {
}
