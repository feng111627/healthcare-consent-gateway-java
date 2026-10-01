package com.github.feng111627.healthcareconsentgatewayjava.model;

/**
 * 拼接字段规则：由多个字段拼出来的响应片段也不能漏原文。
 * 例如 "fullName" = firstName + " " + lastName，
 * 则对 "fullName" 的输出按给定模板用各成分脱敏后的值重组。
 */
public record CompositeFieldRule(
        String outputField,
        String template,
        java.util.List<String> componentFields,
        MaskStrategy strategy
) {
}
