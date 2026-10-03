package com.github.feng111627.healthcareconsentgatewayjava.gateway;

import com.github.feng111627.healthcareconsentgatewayjava.audit.AuditLog;
import com.github.feng111627.healthcareconsentgatewayjava.audit.CanonicalPayload;
import com.github.feng111627.healthcareconsentgatewayjava.consent.ConsentEvaluator;
import com.github.feng111627.healthcareconsentgatewayjava.consent.ConsentRule;
import com.github.feng111627.healthcareconsentgatewayjava.consent.Decision;
import com.github.feng111627.healthcareconsentgatewayjava.consent.DecisionResult;
import com.github.feng111627.healthcareconsentgatewayjava.consent.ScopeFilter;
import com.github.feng111627.healthcareconsentgatewayjava.masking.MaskingException;
import com.github.feng111627.healthcareconsentgatewayjava.masking.MaskingPolicy;
import com.github.feng111627.healthcareconsentgatewayjava.masking.MaskingService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * 合规网关编排：每次访问都经过「判定 -> 脱敏 -> 范围裁剪 -> 留痕返回」。
 * - 无论允许、部分允许还是拒绝都会写审计，记录当时的规则与策略版本、
 *   授权快照与实际返回内容，保证任意历史记录可复现；
 * - 部分允许（ALLOWED_PARTIAL）时，未授权维度以 [UNAUTHORIZED] 标记扣留；
 * - 脱敏失败时返回错误，绝不带出原始数据。
 */
@Service
public class PatientDataGateway {

    /** 脱敏失败时写入响应与审计的统一原因（重放复现失败场景时使用同一文本）。 */
    public static final String MASKING_FAILURE_REASON = "masking failed, data withheld";

    private final ConsentEvaluator consentEvaluator;
    private final MaskingService maskingService;
    private final AuditLog auditLog;
    private final PatientRecordStore recordStore;

    public PatientDataGateway(ConsentEvaluator consentEvaluator,
                              MaskingService maskingService,
                              AuditLog auditLog,
                              PatientRecordStore recordStore) {
        this.consentEvaluator = consentEvaluator;
        this.maskingService = maskingService;
        this.auditLog = auditLog;
        this.recordStore = recordStore;
    }

    public GatewayResponse access(AccessRequest request) {
        MaskingPolicy policy = maskingService.currentPolicy();
        ConsentRule rule = consentEvaluator.currentRule();
        DecisionResult result = consentEvaluator.evaluate(
                request.patientId(), request.requesterId(), request.purpose(), Instant.now());

        if (!result.allowed()) {
            audit(request, result, policy, null);
            return new GatewayResponse(result.decision(), result.reason(), null);
        }

        Optional<Map<String, Object>> record = recordStore.findById(request.patientId());
        if (record.isEmpty()) {
            audit(request, result, policy, null);
            return new GatewayResponse(result.decision(), result.reason(), null);
        }
        try {
            Map<String, Object> masked = maskingService.mask(record.get(), policy);
            // 范围裁剪：未授权维度替换为明确标记（grantedScopes 为 null 时不过滤）
            Map<String, Object> visible = ScopeFilter.apply(
                    masked, rule.fieldScopes(), result.grantedScopes());
            String payload = CanonicalPayload.of(visible);
            audit(request, result, policy, payload);
            return new GatewayResponse(result.decision(), result.reason(), visible);
        } catch (MaskingException e) {
            // 脱敏出错：返回错误并留痕，不带任何原始数据
            DecisionResult failure = new DecisionResult(Decision.ERROR_MASKING_FAILED,
                    MASKING_FAILURE_REASON, result.ruleVersion(),
                    null, result.consentSnapshot());
            audit(request, failure, policy, null);
            return new GatewayResponse(failure.decision(), failure.reason(), null);
        }
    }

    /** 每次访问（无论结果）都留痕：判定结论 + 规则/策略版本 + 授权快照 + 实际返回内容。 */
    private void audit(AccessRequest request, DecisionResult result,
                       MaskingPolicy policy, String maskedPayload) {
        auditLog.append(request.requesterId(), request.patientId(),
                String.valueOf(request.purpose()),
                result.decision().name(), result.reason(),
                result.ruleVersion(), policy.getVersion(),
                result.consentSnapshot(), maskedPayload);
    }
}
