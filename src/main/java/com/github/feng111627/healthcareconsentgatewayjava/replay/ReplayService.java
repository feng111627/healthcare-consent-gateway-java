package com.github.feng111627.healthcareconsentgatewayjava.replay;

import com.github.feng111627.healthcareconsentgatewayjava.audit.AuditLog;
import com.github.feng111627.healthcareconsentgatewayjava.audit.AuditRecord;
import com.github.feng111627.healthcareconsentgatewayjava.audit.CanonicalPayload;
import com.github.feng111627.healthcareconsentgatewayjava.consent.Consent;
import com.github.feng111627.healthcareconsentgatewayjava.consent.ConsentRule;
import com.github.feng111627.healthcareconsentgatewayjava.consent.ConsentRuleRegistry;
import com.github.feng111627.healthcareconsentgatewayjava.consent.Decision;
import com.github.feng111627.healthcareconsentgatewayjava.consent.DecisionResult;
import com.github.feng111627.healthcareconsentgatewayjava.consent.Purpose;
import com.github.feng111627.healthcareconsentgatewayjava.consent.ScopeFilter;
import com.github.feng111627.healthcareconsentgatewayjava.gateway.PatientRecordStore;
import com.github.feng111627.healthcareconsentgatewayjava.masking.MaskingPolicy;
import com.github.feng111627.healthcareconsentgatewayjava.masking.MaskingService;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * 历史复现：对任意一条审计记录，按记录中保存的规则版本与策略版本，
 * 重放出与当时完全一致的判定结论与脱敏结果。
 * - 判定结论：用记录中的授权快照 + 记录时间戳，交给记录对应版本的规则引擎重算，
 *   与当前生效版本、当前授权库状态均无关；
 * - 脱敏结果：不是把记录里留存的内容原样拿出来，而是取记录对应版本的脱敏策略，
 *   对患者数据重新脱敏 + 范围裁剪后重新得出，再与记录中留存的 maskedPayload 核对
 *   （payloadMatches），对不上会被明确标出；
 * - 新版本规则/策略发布后只增不改，旧记录重放不会被新规则带跑。
 */
@Service
public class ReplayService {

    private final AuditLog auditLog;
    private final ConsentRuleRegistry ruleRegistry;
    private final MaskingService maskingService;
    private final PatientRecordStore recordStore;

    public ReplayService(AuditLog auditLog, ConsentRuleRegistry ruleRegistry,
                         MaskingService maskingService, PatientRecordStore recordStore) {
        this.auditLog = auditLog;
        this.ruleRegistry = ruleRegistry;
        this.maskingService = maskingService;
        this.recordStore = recordStore;
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

        // 2. 按记录中的规则版本取回当时的规则引擎与脱敏策略
        ConsentRule rule = ruleRegistry.byVersion(record.ruleVersion());
        if (rule == null) {
            throw new IllegalArgumentException(
                    "rule version unavailable for replay: " + record.ruleVersion());
        }
        MaskingPolicy policy = maskingService.policyByVersion(record.maskingPolicyVersion());
        if (policy == null) {
            throw new IllegalArgumentException(
                    "masking policy version unavailable for replay: " + record.maskingPolicyVersion());
        }

        // 3. 用记录中的授权快照与当时时间戳重算判定
        Consent consent = Consent.fromSnapshot(record.consentSnapshot());
        Purpose purpose = Purpose.valueOf(record.purpose());
        DecisionResult recomputed = rule.evaluate(consent, purpose, record.timestamp());

        // 4. 比对判定结论：重算结论须与记录一致
        boolean decisionMatches = recomputed.decision().name().equals(record.decision())
                && recomputed.reason().equals(record.reason());
        if (record.decision().equals(Decision.ERROR_MASKING_FAILED.name())) {
            // 脱敏失败是运行时事件：记录为 ERROR 时，重算的授权判定应当是「允许」，
            // 即当时确实走到了脱敏这一步
            decisionMatches = recomputed.allowed();
        }

        // 5. 按当时版本的策略重新得出脱敏结果，并与记录留存内容核对
        String replayedPayload = recomputePayload(record, rule, recomputed, policy);
        boolean payloadMatches = record.maskedPayload() == null
                ? replayedPayload == null
                : record.maskedPayload().equals(replayedPayload);

        boolean consistent = hashIntact && decisionMatches && payloadMatches;
        return new ReplayResult(record, recomputed.decision().name(), recomputed.reason(),
                replayedPayload, payloadMatches, consistent);
    }

    /**
     * 用记录对应版本的规则映射与脱敏策略，重新算出当时应返回的脱敏内容。
     * 拒绝/脱敏失败的记录没有返回内容，返回 null；
     * 数据已不存在（无法复现）时也返回 null，由 payloadMatches 体现对不上。
     */
    private String recomputePayload(AuditRecord record, ConsentRule rule,
                                    DecisionResult recomputed, MaskingPolicy policy) {
        if (!recomputed.allowed()
                || record.decision().equals(Decision.ERROR_MASKING_FAILED.name())) {
            return null;
        }
        Optional<Map<String, Object>> data = recordStore.findById(record.patientId());
        if (data.isEmpty()) {
            return null;
        }
        Map<String, Object> masked = maskingService.mask(data.get(), policy);
        Map<String, Object> visible = ScopeFilter.apply(
                masked, rule.fieldScopes(), recomputed.grantedScopes());
        return CanonicalPayload.of(visible);
    }
}
