package com.github.feng111627.healthcareconsentgatewayjava.consent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 范围过滤器：按授权的数据维度（scope）裁剪已脱敏的数据。
 * 未授权字段以 [UNAUTHORIZED] 标记显式替换（不静默省略、绝不带出原文），
 * 未纳入维度映射的字段视为非敏感元数据，始终可见。
 */
public final class ScopeFilter {

    /** 未授权字段的占位标记。 */
    public static final String UNAUTHORIZED = "[UNAUTHORIZED]";

    private ScopeFilter() {
    }

    /**
     * @param maskedData    已脱敏的数据（本方法不修改入参，返回新对象）
     * @param fieldScopes   字段 -> 数据维度映射（取自判定所用规则版本）
     * @param grantedScopes 本次被授予的维度；null 表示不做过滤（返回全部）
     */
    public static Map<String, Object> apply(Map<String, Object> maskedData,
                                            Map<String, String> fieldScopes,
                                            Set<String> grantedScopes) {
        if (grantedScopes == null) {
            return maskedData;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : maskedData.entrySet()) {
            String scope = fieldScopes.get(e.getKey());
            if (scope != null && !grantedScopes.contains(scope)) {
                // 未授权维度：整个字段（含其子树）替换为明确标记
                out.put(e.getKey(), UNAUTHORIZED);
            } else {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }
}
