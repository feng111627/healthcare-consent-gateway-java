package com.github.feng111627.healthcareconsentgatewayjava.tests;

import com.github.feng111627.healthcareconsentgatewayjava.audit.AuditLog;
import com.github.feng111627.healthcareconsentgatewayjava.audit.AuditRecord;
import com.github.feng111627.healthcareconsentgatewayjava.consent.Consent;
import com.github.feng111627.healthcareconsentgatewayjava.consent.ConsentEvaluator;
import com.github.feng111627.healthcareconsentgatewayjava.consent.ConsentRuleRegistry;
import com.github.feng111627.healthcareconsentgatewayjava.consent.Decision;
import com.github.feng111627.healthcareconsentgatewayjava.consent.InMemoryConsentRepository;
import com.github.feng111627.healthcareconsentgatewayjava.consent.Purpose;
import com.github.feng111627.healthcareconsentgatewayjava.gateway.AccessRequest;
import com.github.feng111627.healthcareconsentgatewayjava.gateway.GatewayResponse;
import com.github.feng111627.healthcareconsentgatewayjava.gateway.PatientDataGateway;
import com.github.feng111627.healthcareconsentgatewayjava.gateway.PatientRecordStore;
import com.github.feng111627.healthcareconsentgatewayjava.history.HistoryStore;
import com.github.feng111627.healthcareconsentgatewayjava.masking.MaskingService;
import com.github.feng111627.healthcareconsentgatewayjava.replay.ReplayResult;
import com.github.feng111627.healthcareconsentgatewayjava.replay.ReplayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 范围授权：
 * - 部分可见时只返回授权区段（其余不返回、无原文、无占位），decision=PARTIAL_ALLOWED；
 * - 授权区段与记录完全无交集时整体拒绝 DENIED_OUT_OF_SCOPE；
 * - 审计可区分上述类别；范围结论同样可按历史版本复现。
 */
class ScopeAuthorizationTest {

    private static final Logger log = LoggerFactory.getLogger(ScopeAuthorizationTest.class);
    private static final Instant NOW = Instant.now();

    private InMemoryConsentRepository consentRepository;
    private AuditLog auditLog;
    private MaskingService maskingService;
    private ConsentRuleRegistry ruleRegistry;
    private HistoryStore historyStore;
    private PatientDataGateway gateway;
    private ReplayService replayService;

    @BeforeEach
    void setUp() {
        consentRepository = new InMemoryConsentRepository();
        auditLog = new AuditLog();
        maskingService = new MaskingService(new MockEnvironment());
        ruleRegistry = new ConsentRuleRegistry();
        historyStore = new HistoryStore();
        ConsentEvaluator evaluator = new ConsentEvaluator(consentRepository, ruleRegistry);
        gateway = new PatientDataGateway(evaluator, maskingService, auditLog,
                new PatientRecordStore(), ruleRegistry, historyStore);
        replayService = new ReplayService(historyStore, auditLog, ruleRegistry, maskingService);
    }

    private GatewayResponse access(String requester) {
        GatewayResponse response = gateway.access(
                new AccessRequest(requester, "P001", Purpose.TREATMENT));
        AuditRecord last = auditLog.records().get(auditLog.records().size() - 1);
        log.info("输入: requester={}, 授权范围按登记值", requester);
        log.info("判定依据: decision={}, deniedSections={}, reason={}",
                response.decision(), response.deniedSections(), response.reason());
        log.info("审计: seq={}, decision={}, deniedSections={}, resultHash={}",
                last.sequence(), last.decision(), last.deniedSections(),
                last.resultHash().substring(0, 12));
        return response;
    }

    @Test
    void partialScopeReturnsOnlyGrantedSectionsWithoutRawLeak() {
        // 只授权身份信息：IDENTITY 可见；CONTACT/MEDICAL 不返回
        consentRepository.save(new Consent("C-SCOPE1", "P001", "nurse-A",
                Set.of(Purpose.TREATMENT), NOW.plus(30, ChronoUnit.DAYS), null,
                Set.of("IDENTITY")));

        GatewayResponse response = access("nurse-A");
        assertEquals(Decision.PARTIAL_ALLOWED, response.decision());
        assertEquals(Set.of("CONTACT", "MEDICAL"), Set.copyOf(response.deniedSections()));
        assertNotNull(response.data());

        // 授权区段返回（且仍脱敏、拼接字段正常）
        assertEquals("张*", response.data().get("name"));
        assertTrue(response.data().containsKey("fullName"));
        // 非授权区段顶层字段一律不返回（不是打码占位）
        for (String key : List.of("phone", "email", "address", "contactPhone",
                "fullAddress", "emergencyContact", "visits", "areaCode", "contactNumber")) {
            assertFalse(response.data().containsKey(key), "未授权字段不应返回: " + key);
        }
        // 未授权区段原文不随序列化泄露
        String serialized = String.valueOf(response.data());
        for (String raw : List.of("13812345678", "北京市朝阳区建国路100号", "高血压", "糖尿病",
                "李医生", "建国路100号")) {
            assertFalse(serialized.contains(raw), "未授权区段原文泄露: " + raw);
        }
    }

    @Test
    void noOverlappingScopeIsDeniedOutOfScope() {
        // 只授权医疗信息的请求方去访问不含 MEDICAL 字段的 P002 -> 完全无交集，整体拒绝
        consentRepository.save(new Consent("C-SCOPE2", "P002", "billing-B",
                Set.of(Purpose.TREATMENT), NOW.plus(30, ChronoUnit.DAYS), null,
                Set.of("MEDICAL")));

        GatewayResponse response = gateway.access(
                new AccessRequest("billing-B", "P002", Purpose.TREATMENT));
        log.info("输入: requester=billing-B, scopes=MEDICAL, patient=P002(无MEDICAL字段)");
        log.info("判定依据: decision={}, deniedSections={}",
                response.decision(), response.deniedSections());

        assertEquals(Decision.DENIED_OUT_OF_SCOPE, response.decision());
        assertNull(response.data());
        AuditRecord last = auditLog.records().get(auditLog.records().size() - 1);
        assertEquals("DENIED_OUT_OF_SCOPE", last.decision());
        assertTrue(last.deniedSections().contains("IDENTITY"));
        assertTrue(last.deniedSections().contains("CONTACT"));
    }

    @Test
    void fullScopeAndNoScopeAreDifferentFromPartial() {
        consentRepository.save(new Consent("C-FULL", "P001", "doctor-A",
                Set.of(Purpose.TREATMENT), NOW.plus(30, ChronoUnit.DAYS), null)); // scopes=null 全集
        GatewayResponse full = access("doctor-A");
        assertEquals(Decision.ALLOWED, full.decision());
        assertTrue(full.deniedSections().isEmpty());
        assertTrue(full.data().containsKey("visits"));
    }

    @Test
    void partialScopeDecisionIsReproducibleFromHistory() {
        consentRepository.save(new Consent("C-SCOPE3", "P001", "nurse-C",
                Set.of(Purpose.TREATMENT), NOW.plus(30, ChronoUnit.DAYS), null,
                Set.of("CONTACT")));
        long seq = auditLog.records().size();
        GatewayResponse response = access("nurse-C");
        assertEquals(Decision.PARTIAL_ALLOWED, response.decision());
        List<String> deniedAtAccess = response.deniedSections();

        // 发布新规则版本后，旧的部分访问记录仍重放出相同范围结论与数据
        ruleRegistry.publish("consent-rules-v2");
        maskingService.publish("masking-v2");
        ReplayResult replay = replayService.replay(seq);
        log.info("重放部分授权记录: decision={}, denied={}, resultMatches={}",
                replay.decision(), replay.deniedSections(), replay.resultMatches());

        assertEquals(Decision.PARTIAL_ALLOWED.name(), replay.decision());
        assertEquals(deniedAtAccess, replay.deniedSections());
        assertEquals(response.data(), replay.data());
        assertTrue(replay.decisionMatches(), replay.mismatchDetail());
        assertTrue(replay.resultMatches(), replay.mismatchDetail());
        // 授权的 CONTACT 区段在，未授权的不在
        assertTrue(replay.data().containsKey("contactPhone"));
        assertFalse(replay.data().containsKey("name"));
    }

    @Test
    void auditChainDistinguishesPartialFromFullDenial() {
        consentRepository.save(new Consent("C-A", "P001", "nurse-D",
                Set.of(Purpose.TREATMENT), NOW.plus(30, ChronoUnit.DAYS), null,
                Set.of("IDENTITY")));
        gateway.access(new AccessRequest("nurse-D", "P001", Purpose.TREATMENT));

        consentRepository.save(new Consent("C-B", "P002", "nurse-E",
                Set.of(Purpose.TREATMENT), NOW.plus(30, ChronoUnit.DAYS), null,
                Set.of("MEDICAL")));
        gateway.access(new AccessRequest("nurse-E", "P002", Purpose.TREATMENT));

        List<AuditRecord> records = auditLog.records();
        AuditRecord partial = records.get(records.size() - 2);
        AuditRecord outOfScope = records.get(records.size() - 1);
        assertEquals("PARTIAL_ALLOWED", partial.decision());
        assertFalse(partial.deniedSections().isEmpty());
        assertEquals("DENIED_OUT_OF_SCOPE", outOfScope.decision());
        assertEquals(-1, auditLog.verify());
        log.info("审计可区分: {} (denied={}) vs {} (denied={})",
                partial.decision(), partial.deniedSections(),
                outOfScope.decision(), outOfScope.deniedSections());
    }
}
