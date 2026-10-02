package com.github.feng111627.healthcareconsentgatewayjava.replay;

import com.github.feng111627.healthcareconsentgatewayjava.audit.AuditLog;
import com.github.feng111627.healthcareconsentgatewayjava.audit.AuditRecord;
import com.github.feng111627.healthcareconsentgatewayjava.consent.Consent;
import com.github.feng111627.healthcareconsentgatewayjava.consent.ConsentRule;
import com.github.feng111627.healthcareconsentgatewayjava.consent.ConsentRuleRegistry;
import com.github.feng111627.healthcareconsentgatewayjava.consent.Decision;
import com.github.feng111627.healthcareconsentgatewayjava.consent.DecisionResult;
import com.github.feng111627.healthcareconsentgatewayjava.consent.Purpose;
import com.github.feng111627.healthcareconsentgatewayjava.masking.MaskingService;
import org.springframework.stereotype.Service;

/**
 * 历史复现：对任意一条审计记录，按记录中保存的规则版本与策略版本，
 * 重放出与当时完全一致的判定结论与脱敏结果。
 * - 判定结论：用记录中的授权快照 + 记录时间戳，交给记录对应版本的规则引擎重算，
 *   与当前生效版本、当前授权库状态均无关；
 * - 脱敏结果：当时返回的内容（已脱敏）原样保存在记录中、受哈希链保护，重放时取出，
 *   取出前先重算记录哈希确认未被篡改；
 * - 新版本规则/策略发布后只增不改，旧记录重放不会被新规则带跑。
 */
@Service
public class ReplayService {

    private final AuditLog auditLog;
    private final ConsentRuleRegistry ruleRegistry;
    private final MaskingService maskingService;

    public ReplayService(AuditLog auditLog, ConsentRuleRegistry ruleRegistry,
                         MaskingService maskingService) {
        this.auditLog = auditLog;
        this.ruleRegistry = ruleRegistry;
        this.maskingService = maskingService;
    }

    /**
     * 重放指定序号的审计记录。
     * @throws IllegalArgumentException 记录不存在，或记录引用的规则/策略版本已不可用
     */
    public ReplayResult replay(long sequence) {
        AuditRecord record = auditLog.recordAt(sequence);
        if (record == null) {
            throw new IllegalArgumentException("no audit record at sequence " + sequence);
        }
        // 1. 先确认这条记录本身未被篡改（重算哈希）
        boolean hashIntact = auditLog.verifyRecord(sequence);

        // 2. 按记录中的规则版本取回当时的规则引擎
        ConsentRule rule = ruleRegistry.byVersion(record.ruleVersion());
        if (rule == null) {
            throw new IllegalArgumentException(
                    "rule version unavailable for replay: " + record.ruleVersion());
        }
        // 策略版本必须仍然可取（脱敏结果虽已随记录保存，但版本缺失说明注册表被破坏）
        if (maskingService.policyByVersion(record.maskingPolicyVersion()) == null) {
            throw new IllegalArgumentException(
                    "masking policy version unavailable for replay: " + record.maskingPolicyVersion());
        }

        // 3. 用记录中的授权快照与当时时间戳重算判定
        Consent consent = Consent.fromSnapshot(record.consentSnapshot());
        Purpose purpose = Purpose.valueOf(record.purpose());
        DecisionResult recomputed = rule.evaluate(consent, purpose, record.timestamp());

        // 4. 比对：重算结论须与记录一致
        boolean decisionMatches = recomputed.decision().name().equals(record.decision())
                && recomputed.reason().equals(record.reason());
        if (record.decision().equals(Decision.ERROR_MASKING_FAILED.name())) {
            // 脱敏失败是运行时事件：记录为 ERROR 时，重算的授权判定应当是「允许」，
            // 即当时确实走到了脱敏这一步
            decisionMatches = recomputed.allowed();
        }
        boolean consistent = hashIntact && decisionMatches;

        return new ReplayResult(record, recomputed.decision().name(), recomputed.reason(),
                record.maskedPayload(), consistent);
    }
}
