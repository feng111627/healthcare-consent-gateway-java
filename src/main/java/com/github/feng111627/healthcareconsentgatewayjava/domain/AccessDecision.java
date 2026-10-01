package com.github.feng111627.healthcareconsentgatewayjava.domain;

/** 一次访问请求的授权判定结果（尚未包含数据本身）。 */
public record AccessDecision(
        boolean allowed,
        DecisionReason reason,
        String message,
        DecisionBasis basis
) {
}
