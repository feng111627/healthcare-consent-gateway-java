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
import com.github.feng111627.healthcareconsentgatewayjava.replay.ReplayResult;
import com.github.feng111627.healthcareconsentgatewayjava.replay.ReplayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 升级兼容性：没写数据维度的旧授权（scopes=null）在 v2 规则下继续按全量访问处理，
 * 不会被误判为「一个维度都没授予」而拒绝；
 * 只有确实列了维度的授权（含显式空集）才走「部分可见」判定。
 */
class LegacyConsentCompatibilityTest {

    private static final Logger log = LoggerFactory.getLogger(LegacyConsentCompatibilityTest.class);
    private static final Instant NOW = Instant.now();

    private InMemoryConsentRepository consentRepository;
    private AuditLog auditLog;
    private PatientDataGateway gatewayV2;
    private ReplayService replayService;

    @BeforeEach
    void setUp() {
        consentRepository = new InMemoryConsentRepository();
        auditLog = new AuditLog();
        ConsentRuleRegistry ruleRegistry = new ConsentRuleRegistry();
        MaskingService maskingService = new MaskingService(new MockEnvironment()
                .withProperty("gateway.masking.composites.fullName", "lastName,firstName")
                .withProperty("gateway.masking.composites.contactPhone", "areaCode,phoneNumber")
                .withProperty("gateway.masking.composites.contactPhone.separator", "-")
                .withProperty("gateway.masking.composites.fullAddress", "province,city,street"));
        PatientRecordStore recordStore = new PatientRecordStore();
        // 网关已升级到 v2 规则
        gatewayV2 = new PatientDataGateway(
                new ConsentEvaluator(consentRepository, ruleRegistry, "consent-rules-v2"),
                maskingService, auditLog, recordStore);
        replayService = new ReplayService(auditLog, ruleRegistry, maskingService, recordStore);
    }

    private GatewayResponse accessAndLog(String requester, String patient, Purpose purpose) {
        GatewayResponse response = gatewayV2.access(new AccessRequest(requester, patient, purpose));
        log.info("输入: requesterId={}, patientId={}, purpose={}", requester, patient, purpose);
        log.info("判定依据: decision={}, reason={}", response.decision(), response.reason());
        return response;
    }

    @Test
    void legacyConsentWithoutScopesStillGetsFullAccessAfterUpgrade() {
        // 旧授权：创建时没有数据维度的概念（scopes=null）
        consentRepository.save(new Consent("C-LEGACY", "P001", "doctor-A",
                Set.of(Purpose.TREATMENT), NOW.plus(30, ChronoUnit.DAYS), null));

        GatewayResponse response = accessAndLog("doctor-A", "P001", Purpose.TREATMENT);

        // 升级前能全量读的请求，升级后不能被误拒
        assertEquals(Decision.ALLOWED, response.decision());
        String serialized = String.valueOf(response.data());
        log.info("输出: {}", serialized);
        // 全量返回：没有 [UNAUTHORIZED] 标记，各维度字段都在
        assertFalse(serialized.contains(ScopeFilter.UNAUTHORIZED));
        assertTrue(serialized.contains("visits"));
        assertTrue(serialized.contains("fullName"));
        // 审计记录的判定类别是 ALLOWED 而不是任何 DENIED
        AuditRecord last = auditLog.records().get(auditLog.records().size() - 1);
        assertEquals("ALLOWED", last.decision());
    }

    @Test
    void explicitEmptyScopesIsStillDeniedNoScope() {
        // 明确列了维度、但一个都没授予（空集）：与「没写维度」不同，仍然整体拒绝
        consentRepository.save(new Consent("C-EMPTY", "P001", "analyst-B",
                Set.of(Purpose.RESEARCH), Set.of(),
                NOW.plus(30, ChronoUnit.DAYS), null));

        GatewayResponse response = accessAndLog("analyst-B", "P001", Purpose.RESEARCH);

        assertEquals(Decision.DENIED_NO_SCOPE, response.decision());
        assertNull(response.data());
    }

    @Test
    void explicitPartialScopesStillGetPartialView() {
        // 明确列了部分维度：走「部分可见」判定，不受兼容逻辑影响
        consentRepository.save(new Consent("C-PARTIAL", "P001", "analyst-C",
                Set.of(Purpose.RESEARCH), Set.of("identity"),
                NOW.plus(30, ChronoUnit.DAYS), null));

        GatewayResponse response = accessAndLog("analyst-C", "P001", Purpose.RESEARCH);

        assertEquals(Decision.ALLOWED_PARTIAL, response.decision());
        assertEquals(ScopeFilter.UNAUTHORIZED, response.data().get("visits"));
        assertEquals("张*", response.data().get("name"));
    }

    @Test
    void snapshotRoundTripDistinguishesUnspecifiedFromEmptyScopes() {
        Consent legacy = new Consent("C-L", "P001", "doctor-A",
                Set.of(Purpose.TREATMENT), NOW.plus(30, ChronoUnit.DAYS), null);
        Consent empty = new Consent("C-E", "P001", "analyst-B",
                Set.of(Purpose.RESEARCH), Set.of(),
                NOW.plus(30, ChronoUnit.DAYS), null);

        Consent legacyRestored = Consent.fromSnapshot(legacy.toSnapshot());
        Consent emptyRestored = Consent.fromSnapshot(empty.toSnapshot());
        log.info("输入: 旧授权快照={}, 空维度授权快照={}", legacy.toSnapshot(), empty.toSnapshot());
        log.info("判定依据: 还原后 旧授权scopes={}, 空维度授权scopes={}",
                legacyRestored.getScopes(), emptyRestored.getScopes());

        // 两种语义在快照中可区分、可还原：重放旧记录时判定不会走样
        assertNull(legacyRestored.getScopes());
        assertTrue(emptyRestored.getScopes() != null && emptyRestored.getScopes().isEmpty());
    }

    @Test
    void legacyConsentRecordReplaysConsistentlyAfterUpgrade() {
        // 旧授权在 v2 下产生的访问记录，重放时结论与脱敏结果都能一致复现
        consentRepository.save(new Consent("C-LEGACY2", "P001", "doctor-A",
                Set.of(Purpose.TREATMENT), NOW.plus(30, ChronoUnit.DAYS), null));
        GatewayResponse response = accessAndLog("doctor-A", "P001", Purpose.TREATMENT);
        assertEquals(Decision.ALLOWED, response.decision());
        long seq = auditLog.records().size() - 1;

        ReplayResult replay = replayService.replay(seq);
        log.info("输入: replay(sequence={})", seq);
        log.info("判定依据: 记录[decision={}, ruleVersion={}], 重放[decision={}, consistent={}]",
                replay.record().decision(), replay.record().ruleVersion(),
                replay.replayedDecision(), replay.consistent());

        assertTrue(replay.consistent());
        assertEquals("ALLOWED", replay.replayedDecision());
        assertEquals(replay.recordedMaskedPayload(), replay.replayedMaskedPayload());
        assertEquals(-1, auditLog.verify());
    }
}
