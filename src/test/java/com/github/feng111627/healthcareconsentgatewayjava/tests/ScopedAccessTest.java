package com.github.feng111627.healthcareconsentgatewayjava.tests;

import com.github.feng111627.healthcareconsentgatewayjava.audit.AuditLog;
import com.github.feng111627.healthcareconsentgatewayjava.audit.AuditRecord;
import com.github.feng111627.healthcareconsentgatewayjava.consent.Consent;
import com.github.feng111627.healthcareconsentgatewayjava.consent.ConsentEvaluator;
import com.github.feng111627.healthcareconsentgatewayjava.consent.ConsentRuleRegistry;
import com.github.feng111627.healthcareconsentgatewayjava.consent.Decision;
import com.github.feng111627.healthcareconsentgatewayjava.consent.InMemoryConsentRepository;
import com.github.feng111627.healthcareconsentgatewayjava.consent.Purpose;
import com.github.feng111627.healthcareconsentgatewayjava.consent.ScopeFilter;
import com.github.feng111627.healthcareconsentgatewayjava.gateway.AccessRequest;
import com.github.feng111627.healthcareconsentgatewayjava.gateway.GatewayResponse;
import com.github.feng111627.healthcareconsentgatewayjava.gateway.PatientDataGateway;
import com.github.feng111627.healthcareconsentgatewayjava.gateway.PatientRecordStore;
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

/**
 * 范围授权：同一次访问中部分维度有授权、部分没有。
 * 授权部分照常（脱敏后）返回；未授权部分以 [UNAUTHORIZED] 明确扣留、不带出原文；
 * 「部分可见」ALLOWED_PARTIAL 与「整体拒绝」DENIED_* 在响应与审计中都明确区分。
 */
class ScopedAccessTest {

    private static final Logger log = LoggerFactory.getLogger(ScopedAccessTest.class);
    private static final Instant NOW = Instant.now();
    private static final Set<String> ALL_SCOPES = Set.of("identity", "contact", "address", "medical");

    private InMemoryConsentRepository consentRepository;
    private AuditLog auditLog;
    private PatientDataGateway gateway;

    @BeforeEach
    void setUp() {
        consentRepository = new InMemoryConsentRepository();
        auditLog = new AuditLog();
        gateway = new PatientDataGateway(
                new ConsentEvaluator(consentRepository, new ConsentRuleRegistry(), "consent-rules-v2"),
                new MaskingService(new MockEnvironment()
                        .withProperty("gateway.masking.composites.fullName", "lastName,firstName")
                        .withProperty("gateway.masking.composites.contactPhone", "areaCode,phoneNumber")
                        .withProperty("gateway.masking.composites.contactPhone.separator", "-")
                        .withProperty("gateway.masking.composites.fullAddress", "province,city,street")),
                auditLog, new PatientRecordStore());
    }

    private GatewayResponse accessAndLog(String requester, String patient, Purpose purpose) {
        GatewayResponse response = gateway.access(new AccessRequest(requester, patient, purpose));
        AuditRecord last = auditLog.records().get(auditLog.records().size() - 1);
        log.info("输入: requesterId={}, patientId={}, purpose={}", requester, patient, purpose);
        log.info("判定依据: decision={}, reason={}, 审计记录[decision={}, ruleVersion={}]",
                response.decision(), response.reason(), last.decision(), last.ruleVersion());
        return response;
    }

    @Test
    void partialScopeReturnsAuthorizedPartsAndWithholdsRest() {
        // 只授权 identity + contact 两个维度
        consentRepository.save(new Consent("C1", "P001", "analyst-B",
                Set.of(Purpose.RESEARCH), Set.of("identity", "contact"),
                NOW.plus(30, ChronoUnit.DAYS), null));

        GatewayResponse response = accessAndLog("analyst-B", "P001", Purpose.RESEARCH);

        assertEquals(Decision.ALLOWED_PARTIAL, response.decision());
        Map<String, Object> data = response.data();
        log.info("输出: {}", data);

        // 授权维度：照常返回（脱敏后）
        assertEquals("张*", data.get("name"));                       // identity
        assertEquals("1***8", data.get("phone"));                    // contact
        // 未授权维度：明确标记扣留，不是静默省略、更不是原文
        assertEquals(ScopeFilter.UNAUTHORIZED, data.get("address"));   // address
        assertEquals(ScopeFilter.UNAUTHORIZED, data.get("fullAddress"));
        assertEquals(ScopeFilter.UNAUTHORIZED, data.get("visits"));    // medical
        // 非敏感元数据始终可见
        assertEquals("P001", data.get("patientId"));

        // 未授权维度的原文不出现在序列化输出中
        String serialized = String.valueOf(data);
        for (String raw : new String[]{"北京市朝阳区建国路100号", "建国路100号", "朝阳区",
                "高血压", "糖尿病", "李医生", "13711112222"}) {
            assertFalse(serialized.contains(raw), "未授权维度原文泄露: " + raw);
        }

        // 审计：部分可见与整体拒绝明确区分
        AuditRecord last = auditLog.records().get(auditLog.records().size() - 1);
        assertEquals("ALLOWED_PARTIAL", last.decision());
        assertFalse(last.decision().startsWith("DENIED"));
        assertEquals(-1, auditLog.verify());
    }

    @Test
    void noScopeGrantedIsDistinctDenial() {
        // 授权有效但一个数据维度都没授予
        consentRepository.save(new Consent("C2", "P001", "analyst-B",
                Set.of(Purpose.RESEARCH), Set.of(),
                NOW.plus(30, ChronoUnit.DAYS), null));

        GatewayResponse response = accessAndLog("analyst-B", "P001", Purpose.RESEARCH);

        // 整体拒绝，且与「部分可见」是不同类别
        assertEquals(Decision.DENIED_NO_SCOPE, response.decision());
        assertNull(response.data());
        AuditRecord last = auditLog.records().get(auditLog.records().size() - 1);
        assertEquals("DENIED_NO_SCOPE", last.decision());
        assertNotEqualsAlloweds(last.decision());
    }

    @Test
    void fullScopeReturnsEverythingWithoutMarkers() {
        consentRepository.save(new Consent("C3", "P001", "doctor-A",
                Set.of(Purpose.TREATMENT), ALL_SCOPES,
                NOW.plus(30, ChronoUnit.DAYS), null));

        GatewayResponse response = accessAndLog("doctor-A", "P001", Purpose.TREATMENT);

        assertEquals(Decision.ALLOWED, response.decision());
        String serialized = String.valueOf(response.data());
        log.info("输出: {}", serialized);
        assertFalse(serialized.contains(ScopeFilter.UNAUTHORIZED));
        // 全量返回时拼接字段也在（由脱敏成分组合）
        assertTrue(serialized.contains("fullName"));
        // 原文依然不泄露
        for (String raw : new String[]{"张伟", "张强", "110101199003077799", "13812345678"}) {
            assertFalse(serialized.contains(raw), "原文泄露: " + raw);
        }
        assertEquals("ALLOWED",
                auditLog.records().get(auditLog.records().size() - 1).decision());
    }

    @Test
    void partialDecisionIsDistinguishableFromDenialsInAudit() {
        consentRepository.save(new Consent("C4", "P001", "analyst-B",
                Set.of(Purpose.RESEARCH), Set.of("identity"),
                NOW.plus(30, ChronoUnit.DAYS), null));
        accessAndLog("analyst-B", "P001", Purpose.RESEARCH);   // ALLOWED_PARTIAL
        accessAndLog("stranger", "P001", Purpose.TREATMENT);   // DENIED_NO_CONSENT

        var decisions = auditLog.records().stream().map(AuditRecord::decision).toList();
        log.info("判定依据: 审计决策序列={}", decisions);
        assertEquals("ALLOWED_PARTIAL", decisions.get(0));
        assertEquals("DENIED_NO_CONSENT", decisions.get(1));
        // 部分可见不属于拒绝类别
        assertFalse(decisions.get(0).startsWith("DENIED"));
        assertEquals(-1, auditLog.verify());
    }

    private static void assertNotEqualsAlloweds(String decision) {
        assertFalse(decision.equals("ALLOWED") || decision.equals("ALLOWED_PARTIAL"),
                "应当与部分可见区分: " + decision);
    }

    private static void assertNotEquals(String unexpected, String actual) {
        org.junit.jupiter.api.Assertions.assertNotEquals(unexpected, actual);
    }
}
