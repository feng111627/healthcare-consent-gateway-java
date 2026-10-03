package com.github.feng111627.healthcareconsentgatewayjava.replay;

import com.github.feng111627.healthcareconsentgatewayjava.audit.AuditRecord;

/**
 * 一次历史重放的结果。重放不是把记录里留存的内容原样取出，
 * 而是按记录中的规则版本与策略版本，用记录保存的判定输入（授权快照 + 当时时间戳）
 * 和当前患者数据重新计算判定结论与脱敏结果，再与记录留存的内容核对。
 *
 * @param record                被重放的审计记录
 * @param replayedDecision      按记录中规则版本重算出的判定
 * @param replayedReason        重算出的判定原因
 * @param replayedMaskedPayload 按记录中策略版本重算出的脱敏结果（拒绝/失败时为 null）
 * @param recordedMaskedPayload 记录中留存的当时返回内容（哈希链保护），用于核对
 * @param consistent            重算结论、重算脱敏结果与记录留存内容是否完全一致
 */
public record ReplayResult(AuditRecord record, String replayedDecision, String replayedReason,
                           String replayedMaskedPayload, String recordedMaskedPayload,
                           boolean consistent) {
}
