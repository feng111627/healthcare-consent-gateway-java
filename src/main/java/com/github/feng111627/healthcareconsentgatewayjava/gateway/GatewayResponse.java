package com.github.feng111627.healthcareconsentgatewayjava.gateway;

import com.github.feng111627.healthcareconsentgatewayjava.consent.Decision;

import java.util.List;
import java.util.Map;

/**
 * 网关响应。
 * PARTIAL_ALLOWED 时 data 只含授权区段，deniedSections 列出被拒绝的区段；
 * 整体拒绝与脱敏失败时 data 为 null。
 */
public record GatewayResponse(Decision decision, String reason, Map<String, Object> data,
                              List<String> deniedSections) {

    public GatewayResponse(Decision decision, String reason, Map<String, Object> data) {
        this(decision, reason, data, List.of());
    }
}
