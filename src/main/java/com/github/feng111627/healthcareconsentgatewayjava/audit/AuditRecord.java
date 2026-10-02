package com.github.feng111627.healthcareconsentgatewayjava.audit;

import java.time.Instant;
import java.util.List;

/**
 * 一条审计记录，prevHash/hash 构成哈希链。
 * deniedSections 记录部分授权下被拒绝的数据区段（全集授权为空）；
 * resultHash 为当时返回结果（脱敏数据或拒绝结论）的哈希，供复现比对。
 */
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
        List<String> deniedSections,
        String resultHash,
        String prevHash,
        String hash) {
}
