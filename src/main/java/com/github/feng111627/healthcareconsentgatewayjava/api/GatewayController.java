package com.github.feng111627.healthcareconsentgatewayjava.api;

import com.github.feng111627.healthcareconsentgatewayjava.audit.AuditLog;
import com.github.feng111627.healthcareconsentgatewayjava.audit.AuditRecord;
import com.github.feng111627.healthcareconsentgatewayjava.consent.Consent;
import com.github.feng111627.healthcareconsentgatewayjava.consent.ConsentRepository;
import com.github.feng111627.healthcareconsentgatewayjava.consent.Purpose;
import com.github.feng111627.healthcareconsentgatewayjava.gateway.AccessRequest;
import com.github.feng111627.healthcareconsentgatewayjava.gateway.GatewayResponse;
import com.github.feng111627.healthcareconsentgatewayjava.gateway.PatientDataGateway;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class GatewayController {

    private final PatientDataGateway gateway;
    private final AuditLog auditLog;
    private final ConsentRepository consentRepository;

    public GatewayController(PatientDataGateway gateway, AuditLog auditLog,
                             ConsentRepository consentRepository) {
        this.gateway = gateway;
        this.auditLog = auditLog;
        this.consentRepository = consentRepository;
    }

    /** 读取患者数据：每次调用都会经过授权判定、审计留痕与脱敏。 */
    @GetMapping("/patients/{patientId}")
    public ResponseEntity<GatewayResponse> getPatient(@PathVariable String patientId,
                                                      @RequestParam String requesterId,
                                                      @RequestParam Purpose purpose) {
        GatewayResponse response = gateway.access(new AccessRequest(requesterId, patientId, purpose));
        if (response.decision().name().startsWith("DENIED")
                || response.decision().name().startsWith("ERROR")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        }
        return ResponseEntity.ok(response);
    }

    /** 登记授权（本地模拟）。 */
    @PostMapping("/consents")
    public ResponseEntity<Map<String, String>> createConsent(@RequestBody ConsentDto dto) {
        consentRepository.save(new Consent(dto.consentId(), dto.patientId(), dto.requesterId(),
                dto.purposes(), dto.expiresAt(), dto.revokedAt()));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("consentId", dto.consentId()));
    }

    /** 查看审计链。 */
    @GetMapping("/audit")
    public List<AuditRecord> auditRecords() {
        return auditLog.records();
    }

    /** 校验审计链完整性：-1 表示未被篡改。 */
    @GetMapping("/audit/verify")
    public Map<String, Object> verifyAudit() {
        long tamperedAt = auditLog.verify();
        return Map.of("intact", tamperedAt == -1, "firstTamperedSequence", tamperedAt);
    }

    public record ConsentDto(String consentId, String patientId, String requesterId,
                             Set<Purpose> purposes, Instant expiresAt, Instant revokedAt) {
    }
}
