package com.github.feng111627.healthcareconsentgatewayjava.model;

import com.github.feng111627.healthcareconsentgatewayjava.domain.AccessDecision;

import java.util.Map;

/**
 * 网关对外响应。拒绝时 data 为 null 且不携带任何患者原文；
 * maskingError 非空表示脱敏阶段失败，此时 data 同样不会带出原文。
 */
public record GatewayResponse(
        String requestId,
        AccessDecision decision,
        Map<String, Object> data,
        boolean masked,
        String maskingError
) {
}
