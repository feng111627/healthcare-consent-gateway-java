package com.github.feng111627.healthcareconsentgatewayjava.tests;

import com.github.feng111627.healthcareconsentgatewayjava.audit.AuditLog;
import com.github.feng111627.healthcareconsentgatewayjava.audit.AuditRecord;
import com.github.feng111627.healthcareconsentgatewayjava.consent.Consent;
import com.github.feng111627.healthcareconsentgatewayjava.consent.ConsentEvaluator;
import com.github.feng111627.healthcareconsentgatewayjava.consent.Decision;
import com.github.feng111627.healthcareconsentgatewayjava.consent.InMemoryConsentRepository;
import com.github.feng111627.healthcareconsentgatewayjava.consent.Purpose;
import com.github.feng111627.healthcareconsentgatewayjava.gateway.AccessRequest;
import com.github.feng111627.healthcareconsentgatewayjava.gateway.GatewayResponse;
import com.github.feng111627.healthcareconsentgatewayjava.gateway.PatientDataGateway;
import com.github.feng111627.healthcareconsentgatewayjava.gateway.PatientRecordStore;
import com.github.feng111627.healthcareconsentgatewayjava.masking.MaskingException;
import com.github.feng111627.healthcareconsentgatewayjava.masking.MaskingPolicy;
import com.github.feng111627.healthcareconsentgatewayjava.masking.MaskingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatientDataGatewayTest {

    private static final Logger log = LoggerFactory.getLogger(PatientDataGatewayTest.class);
    private static final Instant NOW = Instant.now();

    private InMemoryConsentRepository consentRepository;
    private AuditLog auditLog;
    private PatientDataGateway gateway;
    private MaskingService maskingService;

    @BeforeEach
    void setUp() {
        consentRepository = new InMemoryConsentRepository();
        auditLog = new AuditLog();
        maskingService = new MaskingService(new MockEnvironment());
        gateway = new PatientDataGateway(
                new ConsentEvaluator(consentRepository, "consent-rules-v1"),
                maskingService, auditLog, new PatientRecordStore());
    }

    private GatewayResponse accessAndLog(String requester, String patient, Purpose purpose) {
        GatewayResponse response = gateway.access(new AccessRequest(requester, patient, purpose));
        log.info("输入: requesterId={}, patientId={}, purpose={}", requester, patient, purpose);
        log.info("判定依据: decision={}, reason={}", response.decision(), response.reason());
        return response;
    }

    @Test
    void revokedConsentIsDeniedAndAudited() {
        consentRepository.save(new Consent("C1", "P001", "doctor-A",
                Set.of(Purpose.TREATMENT), NOW.plus(30, ChronoUnit.DAYS), NOW.minus(1, ChronoUnit.DAYS)));

        GatewayResponse response = accessAndLog("doctor-A", "P001", Purpose.TREATMENT);

        assertEquals(Decision.DENIED_REVOKED, response.decision());
        assertNull(response.data());
        AuditRecord last = auditLog.records().get(auditLog.records().size() - 1);
        assertEquals("DENIED_REVOKED", last.decision());
        assertEquals("consent-rules-v1", last.ruleVersion());
        assertEquals(-1, auditLog.verify());
    }

    @Test
    void expiredConsentIsDeniedAndAudited() {
        consentRepository.save(new Consent("C2", "P001", "doctor-A",
                Set.of(Purpose.TREATMENT), NOW.minus(1, ChronoUnit.HOURS), null));

        GatewayResponse response = accessAndLog("doctor-A", "P001", Purpose.TREATMENT);

        assertEquals(Decision.DENIED_EXPIRED, response.decision());
        assertNull(response.data());
        assertEquals("DENIED_EXPIRED",
                auditLog.records().get(auditLog.records().size() - 1).decision());
    }

    @Test
    void outOfScopePurposeIsDeniedAndAudited() {
        consentRepository.save(new Consent("C3", "P001", "marketer-B",
                Set.of(Purpose.BILLING), NOW.plus(30, ChronoUnit.DAYS), null));

        GatewayResponse response = accessAndLog("marketer-B", "P001", Purpose.MARKETING);

        assertEquals(Decision.DENIED_PURPOSE_MISMATCH, response.decision());
        assertNull(response.data());
        assertEquals("DENIED_PURPOSE_MISMATCH",
                auditLog.records().get(auditLog.records().size() - 1).decision());
    }

    @Test
    void allowedAccessReturnsMaskedDataWithoutRawLeak() {
        consentRepository.save(new Consent("C4", "P001", "doctor-A",
                Set.of(Purpose.TREATMENT), NOW.plus(30, ChronoUnit.DAYS), null));

        GatewayResponse response = accessAndLog("doctor-A", "P001", Purpose.TREATMENT);

        assertEquals(Decision.ALLOWED, response.decision());
        String serialized = String.valueOf(response.data());
        log.info("输出(拼接后): {}", serialized);
        // 原文不得出现在拼接后的响应里（含嵌套结构）
        for (String raw : new String[]{"张伟", "张强", "李医生", "110101199003077799",
                "13812345678", "13987654321", "北京市朝阳区建国路100号"}) {
            assertFalse(serialized.contains(raw), "原文泄露: " + raw);
        }
        // 脱敏形态可断言
        assertEquals("张*", response.data().get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> contact = (Map<String, Object>) response.data().get("emergencyContact");
        assertEquals("张*", contact.get("name"));
        // 审计记录携带当时的脱敏策略版本，可复现
        AuditRecord last = auditLog.records().get(auditLog.records().size() - 1);
        assertEquals(maskingService.currentPolicy().getVersion(), last.maskingPolicyVersion());
    }

    @Test
    void maskingFailureDoesNotLeakRawData() {
        MaskingService broken = new MaskingService(new MockEnvironment()) {
            @Override
            public Map<String, Object> mask(Map<String, Object> data, MaskingPolicy policy) {
                throw new MaskingException("simulated failure", null);
            }
        };
        PatientDataGateway brokenGateway = new PatientDataGateway(
                new ConsentEvaluator(consentRepository, "consent-rules-v1"),
                broken, auditLog, new PatientRecordStore());
        consentRepository.save(new Consent("C5", "P001", "doctor-A",
                Set.of(Purpose.TREATMENT), NOW.plus(30, ChronoUnit.DAYS), null));

        GatewayResponse response = brokenGateway.access(
                new AccessRequest("doctor-A", "P001", Purpose.TREATMENT));
        log.info("输入: 脱敏服务抛异常");
        log.info("判定依据: decision={}, reason={}", response.decision(), response.reason());

        assertEquals(Decision.ERROR_MASKING_FAILED, response.decision());
        assertNull(response.data());
        assertFalse(String.valueOf(response).contains("张伟"));
    }
}
