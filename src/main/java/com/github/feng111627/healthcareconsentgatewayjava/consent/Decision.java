package com.github.feng111627.healthcareconsentgatewayjava.consent;

/** 访问判定结果，拒绝原因明确区分。 */
public enum Decision {
    ALLOWED,
    /** 授权有效但只覆盖部分数据维度：授权部分照常返回，未授权部分被扣留。 */
    ALLOWED_PARTIAL,
    DENIED_NO_CONSENT,
    DENIED_REVOKED,
    DENIED_EXPIRED,
    DENIED_PURPOSE_MISMATCH,
    /** 授权有效但未授予任何数据维度，等同于整体拒绝。 */
    DENIED_NO_SCOPE,
    ERROR_MASKING_FAILED
}
