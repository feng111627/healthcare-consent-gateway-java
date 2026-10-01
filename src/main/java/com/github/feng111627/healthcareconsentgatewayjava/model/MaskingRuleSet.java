package com.github.feng111627.healthcareconsentgatewayjava.model;

import java.util.List;
import java.util.Map;

/**
 * 一份可配置、带版本的脱敏规则。
 * fieldRules 的 key 支持叶子字段名或点号路径（如 contact.phone）。
 */
public record MaskingRuleSet(
        String version,
        Map<String, MaskStrategy> fieldRules,
        List<CompositeFieldRule> compositeRules
) {
}
