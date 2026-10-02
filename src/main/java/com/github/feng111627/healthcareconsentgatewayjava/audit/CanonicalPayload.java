package com.github.feng111627.healthcareconsentgatewayjava.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * 规范化序列化：Map 键排序后输出 JSON，同一内容必然得到同一字符串，
 * 用于把「当时返回的脱敏结果」确定性地写入审计记录。
 */
public final class CanonicalPayload {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private CanonicalPayload() {
    }

    public static String of(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("canonical serialization failed", e);
        }
    }
}
