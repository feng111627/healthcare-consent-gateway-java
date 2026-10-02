package com.github.feng111627.healthcareconsentgatewayjava.gateway;

import com.github.feng111627.healthcareconsentgatewayjava.audit.AuditLog;
import com.github.feng111627.healthcareconsentgatewayjava.audit.AuditRecord;
import com.github.feng111627.healthcareconsentgatewayjava.consent.ConsentEvaluator;
import com.github.feng111627.healthcareconsentgatewayjava.consent.ConsentRuleSet;
import com.github.feng111627.healthcareconsentgatewayjava.consent.ConsentRuleRegistry;
import com.github.feng111627.healthcareconsentgatewayjava.consent.Decision;
import com.github.feng111627.healthcareconsentgatewayjava.consent.DecisionResult;
import com.github.feng111627.healthcareconsentgatewayjava.history.AccessSnapshot;
import com.github.feng111627.healthcareconsentgatewayjava.history.HistoryStore;
import com.github.feng111627.healthcareconsentgatewayjava.masking.MaskingException;
import com.github.feng111627.healthcareconsentgatewayjava.masking.MaskingPolicy;
import com.github.feng111627.healthcareconsentgatewayjava.masking.MaskingService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 合规网关编排：每次访问都经过「判定 -> 范围裁剪 -> 留痕/快照 -> 脱敏返回」。
 * 无论允许、部分可见还是拒绝都会写审计；访问时刻的授权与原始数据快照写入历史库，
 * 供 {@code ReplayService} 按当时版本复现；脱敏失败时返回错误，绝不带出原始数据。
 */
@Service
public class PatientDataGateway {

    private final ConsentEvaluator consentEvaluator;
    private final MaskingService maskingService;
    private final AuditLog auditLog;
    private final PatientRecordStore recordStore;
    private final ConsentRuleRegistry ruleRegistry;
    private final HistoryStore historyStore;

    public PatientDataGateway(ConsentEvaluator consentEvaluator,
                              MaskingService maskingService,
                              AuditLog auditLog,
                              PatientRecordStore recordStore) {
        this(consentEvaluator, maskingService, auditLog, recordStore,
                new ConsentRuleRegistry(), new HistoryStore());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PatientDataGateway(ConsentEvaluator consentEvaluator,
                              MaskingService maskingService,
                              AuditLog auditLog,
                              PatientRecordStore recordStore,
                              ConsentRuleRegistry ruleRegistry,
                              HistoryStore historyStore) {
        this.consentEvaluator = consentEvaluator;
        this.maskingService = maskingService;
        this.auditLog = auditLog;
        this.recordStore = recordStore;
        this.ruleRegistry = ruleRegistry;
        this.historyStore = historyStore;
    }

    public GatewayResponse access(AccessRequest request) {
        Instant now = Instant.now();
        MaskingPolicy policy = maskingService.currentPolicy();
        DecisionResult result = consentEvaluator.evaluate(
                request.patientId(), request.requesterId(), request.purpose(), now);
        ConsentRuleSet rule = ruleRegistry.get(result.ruleVersion());
        if (rule == null) {
            // 判定来自固定版本测试构造器等场景，按当前规则补齐
            rule = ruleRegistry.active();
        }

        Optional<Map<String, Object>> recordOpt = recordStore.findById(request.patientId());

        if (!result.allowed()) {
            // 整体拒绝：无数据返回；仅拒绝结论入结果哈希，无需原始数据快照
            String hash = ResultHasher.hashResult(result.decision().name(), null);
            AuditRecord audit = auditAndSnapshot(request, result, policy, List.of(), hash,
                    null, recordOpt.orElse(null), now);
            return new GatewayResponse(result.decision(), result.reason(), null, List.of());
        }

        if (recordOpt.isEmpty()) {
            String hash = ResultHasher.hashResult(Decision.ALLOWED.name(), Map.of());
            auditAndSnapshot(request, result, policy, List.of(), hash, null, null, now);
            return new GatewayResponse(Decision.ALLOWED, result.reason(), Map.of(), List.of());
        }

        Map<String, Object> rawRecord = recordOpt.get();
        List<String> deniedSections = result.consentSnapshot() == null
                ? List.of()
                : rule.deniedSections(result.consentSnapshot(), rawRecord);
        List<String> presentSections = rule.presentSections(rawRecord);

        // 范围授权：授权区段与记录完全无交集 -> 整体拒绝，明确区别于部分可见
        if (!result.consentSnapshot().isFullScope()
                && !presentSections.isEmpty()
                && deniedSections.containsAll(presentSections)) {
            DecisionResult outOfScope = new DecisionResult(Decision.DENIED_OUT_OF_SCOPE,
                    "consent scopes " + result.consentSnapshot().scopes()
                            + " do not cover any section of patient " + request.patientId()
                            + " (record sections: " + presentSections + ")",
                    result.ruleVersion(), result.consentSnapshot());
            String hash = ResultHasher.hashResult(outOfScope.decision().name(), null);
            auditAndSnapshot(request, outOfScope, policy, presentSections,
                    hash, result.consentSnapshot(), rawRecord, now);
            return new GatewayResponse(outOfScope.decision(), outOfScope.reason(), null,
                    presentSections);
        }

        try {
            Map<String, Object> data = DataProjector.project(rawRecord, deniedSections,
                    rule.fieldSections(), maskingService, policy);
            // 部分可见：在原因里明确标注，审计 decision 为 PARTIAL_ALLOWED
            Decision effectiveDecision = deniedSections.isEmpty()
                    ? Decision.ALLOWED : Decision.PARTIAL_ALLOWED;
            String reason = deniedSections.isEmpty() ? result.reason()
                    : rule.partialReason(result.consentSnapshot(), request.purpose(),
                            deniedSections);
            String hash = ResultHasher.hashResult(effectiveDecision.name(), data);
            auditAndSnapshot(request,
                    new DecisionResult(effectiveDecision, reason, result.ruleVersion(),
                            result.consentSnapshot()),
                    policy, deniedSections, hash, result.consentSnapshot(), rawRecord, now);
            return new GatewayResponse(effectiveDecision, reason, data, deniedSections);
        } catch (MaskingException e) {
            // 脱敏出错：返回错误，不带任何原始数据
            DecisionResult failed = new DecisionResult(Decision.ERROR_MASKING_FAILED,
                    "masking failed, data withheld", result.ruleVersion(),
                    result.consentSnapshot());
            String hash = ResultHasher.hashResult(failed.decision().name(), null);
            auditAndSnapshot(request, failed, policy, deniedSections, hash,
                    result.consentSnapshot(), rawRecord, now);
            return new GatewayResponse(Decision.ERROR_MASKING_FAILED, failed.reason(), null,
                    deniedSections);
        }
    }

    /** 本次记录实际涉及的受限区段由规则版本的 presentSections 提供。 */

    private AuditRecord auditAndSnapshot(AccessRequest request, DecisionResult result,
                                         MaskingPolicy policy, List<String> deniedSections,
                                         String resultHash,
                                         com.github.feng111627.healthcareconsentgatewayjava.consent.ConsentSnapshot consent,
                                         Map<String, Object> rawRecord, Instant now) {
        AuditRecord record = auditLog.append(request.requesterId(), request.patientId(),
                String.valueOf(request.purpose()),
                result.decision().name(), result.reason(),
                result.ruleVersion(), policy.getVersion(), deniedSections, resultHash);
        // 允许/部分可见/范围拒绝都保留访问时刻快照（整体授权类拒绝不需要原始数据）
        if (consent != null || rawRecord != null) {
            historyStore.save(new AccessSnapshot(record.sequence(), request.requesterId(),
                    request.patientId(), request.purpose(), now, consent,
                    rawRecord == null ? null : deepCopy(rawRecord),
                    result.ruleVersion(), policy.getVersion(), deniedSections,
                    result.decision().name()));
        }
        return record;
    }

    /** 原始记录深拷贝，避免之后数据存储变更污染历史快照。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> source) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : source.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map<?, ?> m) {
                copy.put(e.getKey(), deepCopy((Map<String, Object>) m));
            } else if (v instanceof List<?> l) {
                List<Object> items = new java.util.ArrayList<>(l.size());
                for (Object item : l) {
                    items.add(item instanceof Map<?, ?> mm
                            ? deepCopy((Map<String, Object>) mm) : item);
                }
                copy.put(e.getKey(), items);
            } else {
                copy.put(e.getKey(), v);
            }
        }
        return copy;
    }
}
