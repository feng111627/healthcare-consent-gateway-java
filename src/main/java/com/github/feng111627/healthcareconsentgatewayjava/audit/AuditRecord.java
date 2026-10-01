package com.github.feng111627.healthcareconsentgatewayjava.audit;

import java.time.Instant;

/** 一条审计记录，prevHash/hash 构成哈希链。 */
public record AuditRecord(
        long sequence,
        Instant timestamp,
        String requesterId,
        String patientId,
        String purpose,
        String decision,
        String reason,
        String ruleVersion,
        String maskingPolicyVersion,
        String prevHash,
        String hash) {
}
