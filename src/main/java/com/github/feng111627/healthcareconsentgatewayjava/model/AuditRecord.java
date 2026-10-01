package com.github.feng111627.healthcareconsentgatewayjava.model;

import com.github.feng111627.healthcareconsentgatewayjava.domain.AccessRequest;
import com.github.feng111627.healthcareconsentgatewayjava.domain.DecisionBasis;
import com.github.feng111627.healthcareconsentgatewayjava.domain.DecisionReason;

import java.time.Instant;

/**
 * 一条访问留痕（不可变 record）。recordHash = H(prevHash, 业务字段)，
 * 任意一条被改动或顺序被打乱都会在校验链时暴露。
 * 全参构造公开，支持测试构造“被篡改的副本”。
 */
public record AuditRecord(
        long seq,
        String requestId,
        Instant timestamp,
        AccessRequest request,
        DecisionReason decision,
        DecisionBasis basis,
        String policyVersion,
        boolean masked,
        String maskingErrorCode,
        String prevHash,
        String recordHash
) {
}
