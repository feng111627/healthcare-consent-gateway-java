package com.github.feng111627.healthcareconsentgatewayjava.domain;

/**
 * 访问判定结论。拒绝原因互相可区分：
 * 授权撤回 / 授权过期 / 用途越权 / 无授权 各自独立。
 */
public enum DecisionReason {
    GRANTED,
    CONSENT_NOT_FOUND,
    CONSENT_REVOKED,
    CONSENT_EXPIRED,
    OUT_OF_SCOPE
}
