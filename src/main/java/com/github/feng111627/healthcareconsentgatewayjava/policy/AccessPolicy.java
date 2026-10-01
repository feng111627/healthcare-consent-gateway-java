package com.github.feng111627.healthcareconsentgatewayjava.policy;

import com.github.feng111627.healthcareconsentgatewayjava.model.MaskingRuleSet;

/**
 * 可版本化的访问策略：每个版本绑定一份脱敏规则。
 * 历史版本必须保留，以便复现历史判定与脱敏效果。
 */
public record AccessPolicy(String version, MaskingRuleSet maskingRules) {
}
