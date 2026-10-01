package com.github.feng111627.healthcareconsentgatewayjava.model;

/** 脱敏策略。 */
public enum MaskStrategy {
    /** 整段替换为固定占位符，例如 [REDACTED] */
    REDACT,
    /** 保留前 1 位与后 1 位，中间打码 */
    PARTIAL,
    /** 保留域名/结构，本地部分打码，例如 a***@x.com */
    EMAIL,
    /** 仅保留区号与后 4 位 */
    PHONE
}
