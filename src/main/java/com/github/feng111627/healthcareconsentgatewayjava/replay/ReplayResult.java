package com.github.feng111627.healthcareconsentgatewayjava.replay;

import com.github.feng111627.healthcareconsentgatewayjava.audit.AuditRecord;

/**
 * 一次历史重放的结果。
 *
 * @param record            被重放的审计记录
 * @param replayedDecision  按记录中规则版本重算出的判定
 * @param replayedReason    重算出的判定原因
 * @param maskedPayload     当时返回的脱敏结果（取自审计记录，哈希链保护）
 * @param consistent        重算结论与记录是否完全一致
 */
public record ReplayResult(AuditRecord record, String replayedDecision, String replayedReason,
                           String maskedPayload, boolean consistent) {
}
