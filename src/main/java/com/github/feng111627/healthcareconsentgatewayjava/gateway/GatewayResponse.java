package com.github.feng111627.healthcareconsentgatewayjava.gateway;

import com.github.feng111627.healthcareconsentgatewayjava.consent.Decision;

import java.util.Map;

public record GatewayResponse(Decision decision, String reason, Map<String, Object> data) {
}
