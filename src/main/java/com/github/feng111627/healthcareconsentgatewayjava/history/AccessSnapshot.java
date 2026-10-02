package com.github.feng111627.healthcareconsentgatewayjava.history;

import com.github.feng111627.healthcareconsentgatewayjava.consent.ConsentSnapshot;
import com.github.feng111627.healthcareconsentgatewayjava.consent.Purpose;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 一次访问在判定时刻的完整快照（请求、授权、原始记录、版本与范围结论），供重放使用。 */
public record AccessSnapshot(
        long sequence,
        String requesterId,
        String patientId,
        Purpose purpose,
        Instant accessedAt,
        ConsentSnapshot consent,
        Map<String, Object> rawRecord,
        String ruleVersion,
        String maskingPolicyVersion,
        List<String> deniedSections,
        String decision) {
}
