package com.github.feng111627.healthcareconsentgatewayjava.gateway;

import com.github.feng111627.healthcareconsentgatewayjava.audit.AuditLog;

import java.util.Map;

/**
 * 返回结果的确定性哈希：访问时与重放时使用完全相同的序列化口径，
 * 因此只要当时的脱敏结果可复现，重算哈希就与审计记录中的 resultHash 一致。
 */
public final class ResultHasher {

    private ResultHasher() {
    }

    public static String hashResult(String decision, Map<String, Object> data) {
        return AuditLog.sha256Hex(decision + "|" + canonical(data));
    }

    /** 确定性规范化序列化：Map 按 key 排序，null 字面量化。 */
    @SuppressWarnings("unchecked")
    public static String canonical(Object node) {
        if (node == null) {
            return "null";
        }
        if (node instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            map.keySet().stream().map(String::valueOf).sorted().forEach(k -> {
                if (sb.length() > 1) {
                    sb.append(',');
                }
                sb.append(k).append('=').append(canonical(((Map<String, Object>) map).get(k)));
            });
            return sb.append('}').toString();
        }
        if (node instanceof Iterable<?> iterable) {
            StringBuilder sb = new StringBuilder("[");
            for (Object item : iterable) {
                if (sb.length() > 1) {
                    sb.append(',');
                }
                sb.append(canonical(item));
            }
            return sb.append(']').toString();
        }
        return String.valueOf(node);
    }
}
