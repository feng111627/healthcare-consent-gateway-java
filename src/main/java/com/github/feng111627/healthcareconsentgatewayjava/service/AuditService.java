package com.github.feng111627.healthcareconsentgatewayjava.service;

import com.github.feng111627.healthcareconsentgatewayjava.domain.AccessDecision;
import com.github.feng111627.healthcareconsentgatewayjava.domain.AccessRequest;
import com.github.feng111627.healthcareconsentgatewayjava.model.AuditRecord;
import com.github.feng111627.healthcareconsentgatewayjava.model.IntegrityReport;

import java.util.List;

/**
 * 访问留痕：每次判定都追加一条哈希链记录；支持完整性校验与历史复现。
 */
public interface AuditService {
    AuditRecord record(AccessRequest request, AccessDecision decision,
                       boolean masked, String maskingErrorCode);

    List<AuditRecord> all();

    /** 校验内存中的整条哈希链。 */
    IntegrityReport verifyChain();

    /** 校验给定记录集合（支持对“被篡改副本”的检测）。 */
    IntegrityReport verifyChain(List<AuditRecord> records);

    /** 按 requestId 复现历史判定依据。 */
    ReplayResult replay(String requestId);

    record ReplayResult(AuditRecord record, AccessDecision reproducedDecision) {
    }
}
