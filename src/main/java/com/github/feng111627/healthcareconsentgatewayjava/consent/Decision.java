package com.github.feng111627.healthcareconsentgatewayjava.consent;

/** 访问判定结果，拒绝原因明确区分。 */
public enum Decision {
    ALLOWED,
    DENIED_NO_CONSENT,
    DENIED_REVOKED,
    DENIED_EXPIRED,
    DENIED_PURPOSE_MISMATCH,
    ERROR_MASKING_FAILED
}
