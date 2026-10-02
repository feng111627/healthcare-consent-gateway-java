package com.github.feng111627.healthcareconsentgatewayjava.consent;

/**
 * 访问判定结果。
 * 全部拒绝类原因互相区分；PARTIAL_ALLOWED 表示「部分数据可见」，
 * 与整体拒绝、整体允许都不同。
 */
public enum Decision {
    ALLOWED,
    PARTIAL_ALLOWED,
    DENIED_NO_CONSENT,
    DENIED_REVOKED,
    DENIED_EXPIRED,
    DENIED_PURPOSE_MISMATCH,
    /** 授权有效，但授权的数据区段与本次记录完全无交集：整体拒绝。 */
    DENIED_OUT_OF_SCOPE,
    ERROR_MASKING_FAILED
}
