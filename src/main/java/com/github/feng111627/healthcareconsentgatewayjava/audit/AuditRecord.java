package com.github.feng111627.healthcareconsentgatewayjava.audit;

import java.time.Instant;

/**
 * 一条审计记录，prevHash/hash 构成哈希链。
 *
 * @param consentSnapshot 判定所依据的授权快照（"NONE" 表示无授权），重放时据此还原判定输入
 * @param maskedPayload   当时返回给请求方的脱敏结果（规范化字符串，拒绝时为 null），
 *                        本身已脱敏可安全留痕，受哈希链保护，重放时原样取出
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
        String consentSnapshot,
        String maskedPayload,
        String prevHash,
        String hash) {

    /** 兼容旧用法：无快照与返回内容。 */
    public AuditRecord(long sequence, Instant timestamp, String requesterId, String patientId,
                       String purpose, String decision, String reason,
                       String ruleVersion, String maskingPolicyVersion,
                       String prevHash, String hash) {
        this(sequence, timestamp, requesterId, patientId, purpose, decision, reason,
                ruleVersion, maskingPolicyVersion, "NONE", null, prevHash, hash);
    }
}
