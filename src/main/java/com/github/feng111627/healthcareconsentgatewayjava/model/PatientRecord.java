package com.github.feng111627.healthcareconsentgatewayjava.model;

import java.util.Map;

/**
 * 患者数据，用通用 Map 表示以支持任意嵌套结构。
 * 必须包含 "patientId" 字段。
 */
public record PatientRecord(
        String patientId,
        Map<String, Object> data
) {
}
