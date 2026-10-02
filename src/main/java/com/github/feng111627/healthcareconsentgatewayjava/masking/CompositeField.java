package com.github.feng111627.healthcareconsentgatewayjava.masking;

import java.util.List;

/**
 * 拼接字段定义：输出字段由若干成分字段按顺序拼接而成。
 * 成分各自按策略脱敏后再组合，组合结果不得包含任何成分原文。
 */
public record CompositeField(String outputField, List<String> components, String separator) {
}
