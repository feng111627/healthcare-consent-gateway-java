package com.github.feng111627.healthcareconsentgatewayjava.gateway;

import com.github.feng111627.healthcareconsentgatewayjava.audit.AuditLog;
import com.github.feng111627.healthcareconsentgatewayjava.consent.ConsentEvaluator;
import com.github.feng111627.healthcareconsentgatewayjava.consent.Decision;
import com.github.feng111627.healthcareconsentgatewayjava.consent.DecisionResult;
import com.github.feng111627.healthcareconsentgatewayjava.masking.MaskingException;
import com.github.feng111627.healthcareconsentgatewayjava.masking.MaskingPolicy;
import com.github.feng111627.healthcareconsentgatewayjava.masking.MaskingService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * 合规网关编排：每次访问都经过「判定 -> 留痕 -> 脱敏返回」。
 * 无论允许还是拒绝都会写审计；脱敏失败时返回错误，绝不带出原始数据。
 */
@Service
public class PatientDataGateway {

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
        DecisionResult result = consentEvaluator.evaluate(
                request.patientId(), request.requesterId(), request.purpose(), Instant.now());

        // 每次判定都留痕，记录当时的规则与策略版本，保证可复现
        auditLog.append(request.requesterId(), request.patientId(),
                String.valueOf(request.purpose()),
                result.decision().name(), result.reason(),
                result.ruleVersion(), policy.getVersion());

        if (!result.allowed()) {
            return new GatewayResponse(result.decision(), result.reason(), null);
        }

        Optional<Map<String, Object>> record = recordStore.findById(request.patientId());
        if (record.isEmpty()) {
            return new GatewayResponse(Decision.ALLOWED, result.reason(), null);
        }
        try {
            Map<String, Object> masked = maskingService.mask(record.get(), policy);
            return new GatewayResponse(Decision.ALLOWED, result.reason(), masked);
        } catch (MaskingException e) {
            // 脱敏出错：返回错误，不带任何原始数据
            return new GatewayResponse(Decision.ERROR_MASKING_FAILED,
                    "masking failed, data withheld", null);
        }
    }
}
