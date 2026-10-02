package com.github.feng111627.healthcareconsentgatewayjava.masking;

import java.util.List;

/**
 * 拼接字段定义：输出字段 outputName 由 componentFields 各成分按 delimiter 拼接而成。
 * 脱敏时各成分先按自己的策略处理，再拼接，成分原文不出现在输出中。
 */
public record CompositeField(String outputName, List<String> componentFields, String delimiter) {
}
