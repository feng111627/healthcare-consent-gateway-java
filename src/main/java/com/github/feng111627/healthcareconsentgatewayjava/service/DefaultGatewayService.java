package com.github.feng111627.healthcareconsentgatewayjava.service;

import com.github.feng111627.healthcareconsentgatewayjava.domain.AccessDecision;
import com.github.feng111627.healthcareconsentgatewayjava.domain.AccessRequest;
import com.github.feng111627.healthcareconsentgatewayjava.domain.DecisionReason;
import com.github.feng111627.healthcareconsentgatewayjava.model.AuditRecord;
import com.github.feng111627.healthcareconsentgatewayjava.model.GatewayResponse;
import com.github.feng111627.healthcareconsentgatewayjava.model.PatientRecord;
import com.github.feng111627.healthcareconsentgatewayjava.policy.PolicyService;
import com.github.feng111627.healthcareconsentgatewayjava.repo.PatientDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

@Service
public class DefaultGatewayService implements GatewayService {

    private static final Logger log = LoggerFactory.getLogger(DefaultGatewayService.class);

    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final MaskingService maskingService;
    private final PatientDataRepository patientDataRepository;
    private final PolicyService policyService;

    public DefaultGatewayService(AuthorizationService authorizationService,
                                 AuditService auditService,
                                 MaskingService maskingService,
                                 PatientDataRepository patientDataRepository,
                                 PolicyService policyService) {
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.maskingService = maskingService;
        this.patientDataRepository = patientDataRepository;
        this.policyService = policyService;
    }

    @Override
    public GatewayResponse access(AccessRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("access request must not be null");
        }
        log.info("网关收到访问请求: requesterId={}, patientId={}, purpose={}",
                request.requesterId(), request.patientId(), request.purpose());

        // 1) 授权判定
        AccessDecision decision = authorizationService.evaluate(request, Instant.now());

        // 2) 无论允许/拒绝都先留痕；拒绝路径在拿到任何患者数据之前结束
        if (!decision.allowed()) {
            AuditRecord rec = auditService.record(request, decision, false, null);
            log.info("网关拒绝访问: requestId={}, reason={}", rec.requestId(), decision.reason());
            return new GatewayResponse(rec.requestId(), decision, null, false, null);
        }

        // 3) 取患者数据
        PatientRecord record = patientDataRepository.findById(request.patientId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "patient not found: " + request.patientId()));

        // 4) 返回前脱敏。任何异常都兜底：不带出原文，并把失败事实写进审计。
        try {
            Map<String, Object> maskedData =
                    maskingService.mask(record.data(), policyService.current().maskingRules());
            AuditRecord rec = auditService.record(request, decision, true, null);
            log.info("网关放行并脱敏: requestId={}, policyVersion={}",
                    rec.requestId(), policyService.current().version());
            return new GatewayResponse(rec.requestId(), decision, maskedData, true, null);
        } catch (MaskingException ex) {
            log.error("脱敏失败，拒绝返回原文: patientId={}, error={}",
                    request.patientId(), ex.getMessage());
            AccessDecision safeDecision = new AccessDecision(
                    true, DecisionReason.GRANTED,
                    "access allowed but response withheld due to masking failure",
                    decision.basis());
            AuditRecord rec = auditService.record(request, safeDecision, false, "MASKING_FAILED");
            return new GatewayResponse(rec.requestId(), safeDecision, null, false,
                    "MASKING_FAILED");
        }
    }
}
