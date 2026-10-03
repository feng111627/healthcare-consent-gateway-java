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
import com.github.feng111627.healthcareconsentgatewayjava.gateway.PatientDataGateway;
import com.github.feng111627.healthcareconsentgatewayjava.gateway.PatientRecordStore;
import com.github.feng111627.healthcareconsentgatewayjava.masking.MaskingException;
import com.github.feng111627.healthcareconsentgatewayjava.masking.MaskingPolicy;
import com.github.feng111627.healthcareconsentgatewayjava.masking.MaskingService;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 历史复现：对任意一条审计记录，按记录中保存的规则版本与策略版本，
 * 重新计算判定结论与脱敏结果，并与记录中留存的内容核对一致性。
 * - 判定结论：用记录中的授权快照 + 记录时间戳，交给记录对应版本的规则引擎重算，
 *   与当前生效版本、当前授权库状态均无关；
 * - 脱敏结果：用记录对应版本的策略（含当时的拼接字段定义）对患者数据重新脱敏、
 *   按重算出的授权维度重新裁剪，再与记录中留存的当时返回内容（哈希链保护）比对——
 *   不是把留存内容原样取出当结果；
 * - 当时脱敏失败的记录，重放时会真实重现失败过程（同样的策略、同样的数据）；
 * - 新版本规则/策略发布后只增不改，旧记录重放不会被新版本带跑，新老记录共存互不影响。
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

        // 2. 按记录中的版本取回当时的规则引擎与脱敏策略（只增不改，历史版本永久可取）
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

        // 3. 用记录中的授权快照与当时时间戳，按当时版本的规则重算判定
        Consent consent = Consent.fromSnapshot(record.consentSnapshot());
        Purpose purpose = Purpose.valueOf(record.purpose());
        DecisionResult recomputed = rule.evaluate(consent, purpose, record.timestamp());

        // 4. 按当时版本的策略重算脱敏结果（含范围裁剪）；当时脱敏失败的场景会真实重现
        String replayedDecision = recomputed.decision().name();
        String replayedReason = recomputed.reason();
        String replayedPayload = null;
        if (recomputed.allowed()) {
            Optional<Map<String, Object>> data = recordStore.findById(record.patientId());
            if (data.isPresent()) {
                try {
                    Map<String, Object> masked = maskingService.mask(data.get(), policy);
                    Map<String, Object> visible = ScopeFilter.apply(
                            masked, rule.fieldScopes(), recomputed.grantedScopes());
                    replayedPayload = CanonicalPayload.of(visible);
                } catch (MaskingException e) {
                    // 重现当时的脱敏失败：结论与原因都和当时写记录时保持一致
                    replayedDecision = Decision.ERROR_MASKING_FAILED.name();
                    replayedReason = PatientDataGateway.MASKING_FAILURE_REASON;
                    replayedPayload = null;
                }
            }
            // 患者记录已不存在：无法重现脱敏结果，replayedPayload 保持 null，
            // 与记录留存内容比对时会体现为不一致
        }

        // 5. 核对：记录未被篡改 + 重算结论与记录一致 + 重算脱敏结果与当时留存内容一致
        boolean consistent = hashIntact
                && replayedDecision.equals(record.decision())
                && replayedReason.equals(record.reason())
                && Objects.equals(replayedPayload, record.maskedPayload());

        return new ReplayResult(record, replayedDecision, replayedReason,
                replayedPayload, record.maskedPayload(), consistent);
    }
}
