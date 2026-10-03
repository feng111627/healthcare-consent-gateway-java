package com.github.feng111627.healthcareconsentgatewayjava.replay;

import com.github.feng111627.healthcareconsentgatewayjava.audit.AuditRecord;

/**
 * 一次历史重放的结果。
 *
 * @param record            被重放的审计记录
 * @param replayedDecision  按记录中规则版本重算出的判定
 * @param replayedReason    重算出的判定原因
 * @param maskedPayload     按记录中策略版本对数据重新脱敏得出的结果（不是直接取记录留存值；
 *                          拒绝/失败记录为 null）
 * @param payloadMatches    重算出的脱敏结果与记录中留存的 maskedPayload 是否一致
 * @param consistent        记录哈希完好 且 重算结论与记录一致 且 脱敏结果对得上
 */
public record ReplayResult(AuditRecord record, String replayedDecision, String replayedReason,
                           String maskedPayload, boolean payloadMatches, boolean consistent) {
}
