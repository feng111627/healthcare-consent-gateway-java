package com.github.feng111627.healthcareconsentgatewayjava.web;

import com.github.feng111627.healthcareconsentgatewayjava.domain.AccessRequest;
import com.github.feng111627.healthcareconsentgatewayjava.domain.Purpose;
import com.github.feng111627.healthcareconsentgatewayjava.model.AuditRecord;
import com.github.feng111627.healthcareconsentgatewayjava.model.GatewayResponse;
import com.github.feng111627.healthcareconsentgatewayjava.model.IntegrityReport;
import com.github.feng111627.healthcareconsentgatewayjava.model.MaskingRuleSet;
import com.github.feng111627.healthcareconsentgatewayjava.policy.AccessPolicy;
import com.github.feng111627.healthcareconsentgatewayjava.policy.PolicyService;
import com.github.feng111627.healthcareconsentgatewayjava.service.AuditService;
import com.github.feng111627.healthcareconsentgatewayjava.service.GatewayService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class AccessController {

    private final GatewayService gatewayService;
    private final AuditService auditService;
    private final PolicyService policyService;

    public AccessController(GatewayService gatewayService, AuditService auditService,
                            PolicyService policyService) {
        this.gatewayService = gatewayService;
        this.auditService = auditService;
        this.policyService = policyService;
    }

    public record AccessRequestBody(String requesterId, String patientId, String purpose) {
    }

    @PostMapping("/patients/access")
    public ResponseEntity<GatewayResponse> access(@RequestBody AccessRequestBody body) {
        if (body == null || body.requesterId() == null || body.patientId() == null
                || body.purpose() == null) {
            throw new IllegalArgumentException(
                    "requesterId, patientId and purpose are required");
        }
        Purpose purpose;
        try {
            purpose = Purpose.valueOf(body.purpose());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown purpose: " + body.purpose());
        }
        GatewayResponse response = gatewayService.access(
                new AccessRequest(body.requesterId(), body.patientId(), purpose));
        // HTTP 200 表示请求处理成功；是否放行看 decision.allowed / reason
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @GetMapping("/audit")
    public List<AuditRecord> audit() {
        return auditService.all();
    }

    @GetMapping("/audit/verify")
    public IntegrityReport verify() {
        return auditService.verifyChain();
    }

    @GetMapping("/audit/{requestId}/replay")
    public AuditService.ReplayResult replay(@PathVariable String requestId) {
        return auditService.replay(requestId);
    }

    @GetMapping("/policy")
    public AccessPolicy policy() {
        return policyService.current();
    }

    public record PublishPolicyRequest(String version, MaskingRuleSet maskingRules) {
    }

    @PostMapping("/policy")
    public AccessPolicy publish(@RequestBody PublishPolicyRequest request) {
        if (request == null || request.version() == null || request.maskingRules() == null) {
            throw new IllegalArgumentException("version and maskingRules are required");
        }
        return policyService.publish(request.version(), request.maskingRules());
    }
}
