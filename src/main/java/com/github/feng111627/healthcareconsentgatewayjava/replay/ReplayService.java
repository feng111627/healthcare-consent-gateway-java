package com.github.feng111627.healthcareconsentgatewayjava.replay;

import com.github.feng111627.healthcareconsentgatewayjava.audit.AuditLog;
import com.github.feng111627.healthcareconsentgatewayjava.audit.AuditRecord;
import com.github.feng111627.healthcareconsentgatewayjava.consent.ConsentRuleRegistry;
import com.github.feng111627.healthcareconsentgatewayjava.consent.ConsentRuleSet;
import com.github.feng111627.healthcareconsentgatewayjava.consent.Decision;
import com.github.feng111627.healthcareconsentgatewayjava.consent.DecisionResult;
import com.github.feng111627.healthcareconsentgatewayjava.consent.Purpose;
import com.github.feng111627.healthcareconsentgatewayjava.gateway.DataProjector;
import com.github.feng111627.healthcareconsentgatewayjava.gateway.ResultHasher;
import com.github.feng111627.healthcareconsentgatewayjava.history.AccessSnapshot;
import com.github.feng111627.healthcareconsentgatewayjava.history.HistoryStore;
import com.github.feng111627.healthcareconsentgatewayjava.masking.MaskingPolicy;
import com.github.feng111627.healthcareconsentgatewayjava.masking.MaskingService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 历史复现服务：对任意一条历史审计记录，按它当时生效的规则版本与脱敏策略版本，
 * 用访问时刻保存的授权/数据快照重新得到当时的判定结论与脱敏结果，并与留痕比对。
 * 新版本发布只影响之后的访问；重放全程只读，不新增审计记录，可与并发访问共存。
 */
@org.springframework.stereotype.Service
public class ReplayService {

    private final HistoryStore historyStore;
    private final AuditLog auditLog;
    private final ConsentRuleRegistry ruleRegistry;
    private final MaskingService maskingService;

    public ReplayService(HistoryStore historyStore, AuditLog auditLog,
                         ConsentRuleRegistry ruleRegistry, MaskingService maskingService) {
        this.historyStore = historyStore;
        this.auditLog = auditLog;
        this.ruleRegistry = ruleRegistry;
        this.maskingService = maskingService;
    }

    public ReplayResult replay(long sequence) {
        AuditRecord record = auditLog.recordAt(sequence);
        if (record == null) {
            throw new IllegalArgumentException("audit record not found: " + sequence);
        }
        ConsentRuleSet rule = ruleRegistry.get(record.ruleVersion());
        if (rule == null) {
            throw new IllegalStateException("rule version unavailable: " + record.ruleVersion());
        }
        MaskingPolicy policy = maskingService.policyByVersion(record.maskingPolicyVersion());
        if (policy == null) {
            throw new IllegalStateException(
                    "masking policy version unavailable: " + record.maskingPolicyVersion());
        }

        Optional<AccessSnapshot> snapshotOpt = historyStore.findBySequence(sequence);
        // 无快照（如整体拒绝）：直接以留痕中的拒绝结论为复现结果
        if (snapshotOpt.isEmpty()) {
            return compare(record, record.decision(), record.reason(),
                    record.deniedSections(), null);
        }

        AccessSnapshot snapshot = snapshotOpt.get();
        DecisionResult decisionResult;
        if (snapshot.consent() == null) {
            // 当时无授权：结论只与「无授权」事实有关
            decisionResult = new DecisionResult(Decision.valueOf(record.decision()),
                    record.reason(), record.ruleVersion(), null);
        } else {
            // 关键：以访问时刻为判定基准（过期判断不随今天时间漂移），并指定当时规则版本
            decisionResult = rule.evaluate(snapshot.consent(),
                    Purpose.valueOf(record.purpose()), snapshot.accessedAt());
        }

        Map<String, Object> data = null;
        List<String> denied = record.deniedSections();
        if (decisionResult.allowed() && snapshot.rawRecord() != null) {
            // 范围结论同样按当时规则重算（规则版本内含字段->区段映射）
            List<String> recomputedDenied =
                    rule.deniedSections(snapshot.consent(), snapshot.rawRecord());
            List<String> present = rule.presentSections(snapshot.rawRecord());
            // 还原当时的「范围全拒」：授权区段与记录无交集时整体拒绝，不放任何数据
            if (!snapshot.consent().isFullScope() && !present.isEmpty()
                    && recomputedDenied.containsAll(present)) {
                return compare(record, Decision.DENIED_OUT_OF_SCOPE.name(),
                        record.reason(), present, null);
            }
            denied = recomputedDenied;
            data = DataProjector.project(snapshot.rawRecord(), recomputedDenied,
                    rule.fieldSections(), maskingService, policy);
            // 还原当时的 PARTIAL_ALLOWED 决策与统一原因口径
            if (!recomputedDenied.isEmpty()) {
                String reason = rule.partialReason(snapshot.consent(),
                        Purpose.valueOf(record.purpose()), recomputedDenied);
                return compare(record, Decision.PARTIAL_ALLOWED.name(), reason,
                        recomputedDenied, data);
            }
        }
        return compare(record, decisionResult.decision().name(), decisionResult.reason(),
                denied, data);
    }

    private ReplayResult compare(AuditRecord record, String decision, String reason,
                                 List<String> deniedSections, Map<String, Object> data) {
        String resultHash = ResultHasher.hashResult(decision, data);
        boolean decisionMatches = record.decision().equals(decision)
                && record.reason().equals(reason)
                && new ArrayList<>(record.deniedSections()).equals(new ArrayList<>(deniedSections));
        boolean resultMatches = resultHash.equals(record.resultHash());
        String detail = null;
        if (!decisionMatches || !resultMatches) {
            List<String> mismatches = new ArrayList<>();
            if (!record.decision().equals(decision)) {
                mismatches.add("decision: recorded=" + record.decision() + ", replayed=" + decision);
            }
            if (!record.reason().equals(reason)) {
                mismatches.add("reason: recorded=" + record.reason() + ", replayed=" + reason);
            }
            if (!new ArrayList<>(record.deniedSections()).equals(new ArrayList<>(deniedSections))) {
                mismatches.add("deniedSections: recorded=" + record.deniedSections()
                        + ", replayed=" + deniedSections);
            }
            if (!resultHash.equals(record.resultHash())) {
                mismatches.add("resultHash: recorded=" + record.resultHash()
                        + ", replayed=" + resultHash);
            }
            detail = String.join("; ", mismatches);
        }
        return new ReplayResult(record.sequence(), decision, reason,
                record.ruleVersion(), record.maskingPolicyVersion(),
                List.copyOf(deniedSections), data, resultHash,
                decisionMatches, resultMatches, detail);
    }
}
