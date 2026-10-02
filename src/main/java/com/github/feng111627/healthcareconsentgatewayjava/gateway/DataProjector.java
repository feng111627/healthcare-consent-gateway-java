package com.github.feng111627.healthcareconsentgatewayjava.gateway;

import com.github.feng111627.healthcareconsentgatewayjava.masking.MaskingPolicy;
import com.github.feng111627.healthcareconsentgatewayjava.masking.MaskingService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据投影：先按范围结论剔除未授权的顶层数据区段，再交给脱敏服务递归脱敏
 * （含拼接字段处理），返回可对外输出的对象。未授权字段在脱敏前就被剔除，
 * 既不返回原文也不返回打码占位，明确表现为「不返回」。
 */
public final class DataProjector {

    private DataProjector() {
    }

    public static Map<String, Object> project(Map<String, Object> rawRecord,
                                              List<String> deniedSections,
                                              Map<String, String> fieldSections,
                                              MaskingService maskingService,
                                              MaskingPolicy policy) {
        Map<String, Object> scoped = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : rawRecord.entrySet()) {
            String section = fieldSections.get(e.getKey());
            if (section != null && deniedSections.contains(section)) {
                continue;
            }
            scoped.put(e.getKey(), e.getValue());
        }
        return maskingService.mask(scoped, policy);
    }
}
