package com.github.feng111627.healthcareconsentgatewayjava.service;

import com.github.feng111627.healthcareconsentgatewayjava.model.MaskingRuleSet;

import java.util.Map;

/**
 * 返回前脱敏：对姓名、证件号、联系方式等字段按策略处理，
 * 递归覆盖嵌套结构，并处理拼接字段。
 */
public interface MaskingService {
    Map<String, Object> mask(Map<String, Object> raw, MaskingRuleSet ruleSet);
}
