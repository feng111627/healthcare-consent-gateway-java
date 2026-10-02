package com.github.feng111627.healthcareconsentgatewayjava.api;

import com.github.feng111627.healthcareconsentgatewayjava.audit.AuditLog;
import com.github.feng111627.healthcareconsentgatewayjava.audit.AuditRecord;
import com.github.feng111627.healthcareconsentgatewayjava.consent.Consent;
import com.github.feng111627.healthcareconsentgatewayjava.consent.ConsentRepository;
import com.github.feng111627.healthcareconsentgatewayjava.consent.ConsentRuleRegistry;
import com.github.feng111627.healthcareconsentgatewayjava.consent.Purpose;
import com.github.feng111627.healthcareconsentgatewayjava.gateway.AccessRequest;
import com.github.feng111627.healthcareconsentgatewayjava.gateway.GatewayResponse;
import com.github.feng111627.healthcareconsentgatewayjava.gateway.PatientDataGateway;
import com.github.feng111627.healthcareconsentgatewayjava.masking.MaskingService;
import com.github.feng111627.healthcareconsentgatewayjava.replay.ReplayResult;
import com.github.feng111627.healthcareconsentgatewayjava.replay.ReplayService;
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
    private final ReplayService replayService;
    private final ConsentRuleRegistry ruleRegistry;
    private final MaskingService maskingService;

    public GatewayController(PatientDataGateway gateway, AuditLog auditLog,
                             ConsentRepository consentRepository, ReplayService replayService,
                             ConsentRuleRegistry ruleRegistry, MaskingService maskingService) {
        this.gateway = gateway;
        this.auditLog = auditLog;
        this.consentRepository = consentRepository;
        this.replayService = replayService;
        this.ruleRegistry = ruleRegistry;
        this.maskingService = maskingService;
    }

    /** 读取患者数据：每次调用都会经过授权判定、范围裁剪、审计留痕与脱敏。 */
    @GetMapping("/patients/{patientId}")
    public ResponseEntity<GatewayResponse> getPatient(@PathVariable String patientId,
                                                      @RequestParam String requesterId,
                                                      @RequestParam Purpose purpose) {
        GatewayResponse response = gateway.access(new AccessRequest(requesterId, patientId, purpose));
        if (response.decision().name().startsWith("DENIED")
                || response.decision().name().startsWith("ERROR")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        }
        // PARTIAL_ALLOWED 仍是成功访问（200），但 data 只含授权区段，
        // deniedSections 明确标出被拒绝的部分，与整体拒绝（403）区分
        return ResponseEntity.ok(response);
    }

    /** 登记授权（本地模拟）。scopes 为空/缺省表示全集授权，否则只放开列出的数据区段。 */
    @PostMapping("/consents")
    public ResponseEntity<Map<String, String>> createConsent(@RequestBody ConsentDto dto) {
        consentRepository.save(new Consent(dto.consentId(), dto.patientId(), dto.requesterId(),
                dto.purposes(), dto.expiresAt(), dto.revokedAt(), dto.scopes()));
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

    /** 按审计序号复现一次历史访问：使用当时的规则/策略版本与访问时刻快照。 */
    @GetMapping("/audit/{sequence}/replay")
    public ReplayResult replay(@PathVariable long sequence) {
        return replayService.replay(sequence);
    }

    /** 发布新的授权规则版本（只影响之后的访问，历史记录仍按各自版本复现）。 */
    @PostMapping("/rules/active")
    public Map<String, Object> publishRules(@RequestParam String version) {
        ruleRegistry.publish(version);
        return Map.of("ruleVersion", ruleRegistry.activeVersion());
    }

    /** 发布新的脱敏策略版本。 */
    @PostMapping("/masking-policies/active")
    public Map<String, Object> publishMaskingPolicy(@RequestParam String version) {
        maskingService.publish(version);
        return Map.of("maskingPolicyVersion", maskingService.currentPolicy().getVersion());
    }

    /** 查看当前生效版本。 */
    @GetMapping("/versions")
    public Map<String, String> activeVersions() {
        return Map.of(
                "ruleVersion", ruleRegistry.activeVersion(),
                "maskingPolicyVersion", maskingService.currentPolicy().getVersion());
    }

    public record ConsentDto(String consentId, String patientId, String requesterId,
                             Set<Purpose> purposes, Instant expiresAt, Instant revokedAt,
                             Set<String> scopes) {
    }
}
