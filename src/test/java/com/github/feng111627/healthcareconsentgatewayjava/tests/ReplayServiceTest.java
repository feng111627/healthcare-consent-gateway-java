package com.github.feng111627.healthcareconsentgatewayjava.tests;

import com.github.feng111627.healthcareconsentgatewayjava.audit.AuditLog;
import com.github.feng111627.healthcareconsentgatewayjava.audit.AuditRecord;
import com.github.feng111627.healthcareconsentgatewayjava.consent.Consent;
import com.github.feng111627.healthcareconsentgatewayjava.consent.ConsentEvaluator;
import com.github.feng111627.healthcareconsentgatewayjava.consent.ConsentRuleRegistry;
import com.github.feng111627.healthcareconsentgatewayjava.consent.ConsentRulesV1;
import com.github.feng111627.healthcareconsentgatewayjava.consent.ConsentRulesV2;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 历史复现：规则/脱敏策略发布新版本后，旧记录仍按当时版本重放出一致的
 * 判定类别与脱敏结果；新老记录共存，哈希链校验不受影响。
 */
class ReplayServiceTest {

    private static final Logger log = LoggerFactory.getLogger(ReplayServiceTest.class);
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
        ruleRegistry = new ConsentRuleRegistry(); // 默认 v1
        historyStore = new HistoryStore();
        ConsentEvaluator evaluator = new ConsentEvaluator(consentRepository, ruleRegistry);
        gateway = new PatientDataGateway(evaluator, maskingService, auditLog,
                new PatientRecordStore(), ruleRegistry, historyStore);
        replayService = new ReplayService(historyStore, auditLog, ruleRegistry, maskingService);
    }

    private GatewayResponse accessAndLog(String requester, String patient, Purpose purpose) {
        GatewayResponse response = gateway.access(new AccessRequest(requester, patient, purpose));
        log.info("输入: requester={}, patient={}, purpose={}", requester, patient, purpose);
        log.info("判定依据: decision={}, ruleVersion={}, maskingVersion={}",
                response.decision(), ruleRegistry.activeVersion(),
                maskingService.currentPolicy().getVersion());
        return response;
    }

    @Test
    void oldRecordsReplayWithSameDecisionAfterRuleVersionUpgrade() {
        // 既过期又撤回的授权：v1 判撤回，v2 判过期
        consentRepository.save(new Consent("C-OLD", "P001", "doctor-A",
                Set.of(Purpose.TREATMENT), NOW.minus(2, ChronoUnit.HOURS),
                NOW.minus(1, ChronoUnit.HOURS)));

        GatewayResponse oldAccess = accessAndLog("doctor-A", "P001", Purpose.TREATMENT);
        assertEquals(Decision.DENIED_REVOKED, oldAccess.decision());
        long seq = auditLog.records().size() - 1;

        // 发布 v2 规则：过期优先于撤回，之后的新访问结论发生变化
        ruleRegistry.publish(ConsentRulesV2.VERSION);
        GatewayResponse newAccess = accessAndLog("doctor-A", "P001", Purpose.TREATMENT);
        assertEquals(Decision.DENIED_EXPIRED, newAccess.decision());

        // 旧记录重放：必须仍是当时的 DENIED_REVOKED，不被 v2 带跑
        ReplayResult replay = replayService.replay(seq);
        AuditRecord recorded = auditLog.recordAt(seq);
        log.info("重放旧记录 seq={}: recorded={}/{}, replayed={}/{}",
                seq, recorded.decision(), recorded.ruleVersion(),
                replay.decision(), replay.ruleVersion());
        assertEquals(ConsentRulesV1.VERSION, replay.ruleVersion());
        assertEquals(Decision.DENIED_REVOKED.name(), replay.decision());
        assertTrue(replay.decisionMatches(), replay.mismatchDetail());
        assertTrue(replay.resultMatches(), replay.mismatchDetail());
    }

    @Test
    void oldAllowedRecordReplaysSameMaskedDataAfterMaskingPolicyUpgrade() {
        consentRepository.save(new Consent("C-ALLOW", "P001", "doctor-A",
                Set.of(Purpose.TREATMENT), NOW.plus(30, ChronoUnit.DAYS), null));
        GatewayResponse oldAccess = accessAndLog("doctor-A", "P001", Purpose.TREATMENT);
        long seq = auditLog.records().size() - 1;
        String oldAddressMask = (String) oldAccess.data().get("address");
        String oldContactPhone = (String) oldAccess.data().get("contactPhone");
        log.info("旧访问脱敏结果: address={}, contactPhone={}", oldAddressMask, oldContactPhone);

        // 发布 masking-v2（address 从 FULL 变 PARTIAL、contactNumber 从 PARTIAL 变 HASH）
        maskingService.publish("masking-v2");
        GatewayResponse newAccess = accessAndLog("doctor-A", "P001", Purpose.TREATMENT);
        assertNotEquals(oldAddressMask, newAccess.data().get("address"));

        // 旧记录按 masking-v1 重放：脱敏结果与当时完全一致
        ReplayResult replay = replayService.replay(seq);
        log.info("重放旧记录: maskingVersion={}, address={}, contactPhone={}",
                replay.maskingPolicyVersion(), replay.data().get("address"),
                replay.data().get("contactPhone"));
        assertEquals("masking-v1", replay.maskingPolicyVersion());
        assertEquals(oldAccess.data(), replay.data());
        assertTrue(replay.resultMatches(), replay.mismatchDetail());
        // 拼接成分原文也没有在重放结果中泄露
        String serialized = String.valueOf(replay.data());
        for (String raw : List.of("张伟", "0086", "13812345678", "北京市朝阳区建国路100号")) {
            assertFalse(serialized.contains(raw), "重放结果原文泄露: " + raw);
        }
    }

    @Test
    void newAndOldRecordsCoexistAndChainStaysVerifiable() {
        consentRepository.save(new Consent("C-MIX", "P001", "doctor-A",
                Set.of(Purpose.TREATMENT), NOW.plus(30, ChronoUnit.DAYS), null));
        long v1Seq = auditLog.records().size();
        accessAndLog("doctor-A", "P001", Purpose.TREATMENT);

        maskingService.publish("masking-v2");
        ruleRegistry.publish(ConsentRulesV2.VERSION);
        long v2Seq = auditLog.records().size();
        accessAndLog("doctor-A", "P001", Purpose.TREATMENT);

        // 两条记录分别携带各自版本
        assertEquals(ConsentRulesV1.VERSION, auditLog.recordAt(v1Seq).ruleVersion());
        assertEquals(ConsentRulesV2.VERSION, auditLog.recordAt(v2Seq).ruleVersion());
        assertEquals("masking-v1", auditLog.recordAt(v1Seq).maskingPolicyVersion());
        assertEquals("masking-v2", auditLog.recordAt(v2Seq).maskingPolicyVersion());

        // 互不影响地各自重放
        ReplayResult r1 = replayService.replay(v1Seq);
        ReplayResult r2 = replayService.replay(v2Seq);
        assertTrue(r1.resultMatches() && r2.resultMatches());
        assertNotEquals(r1.data(), r2.data());
        log.info("新老记录共存: v1地址={}, v2地址={}",
                r1.data().get("address"), r2.data().get("address"));

        // 混版记录共存，哈希链依然完整
        assertEquals(-1, auditLog.verify());
    }

    @Test
    void replayIsStableUnderConcurrentGrowth() throws InterruptedException {
        consentRepository.save(new Consent("C-CONC", "P001", "doctor-A",
                Set.of(Purpose.TREATMENT), NOW.plus(30, ChronoUnit.DAYS), null));
        long anchor = auditLog.records().size();
        accessAndLog("doctor-A", "P001", Purpose.TREATMENT);
        ReplayResult before = replayService.replay(anchor);

        int threads = 8;
        int perThread = 25;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        gateway.access(new AccessRequest(
                                "doctor-A", "P001", Purpose.TREATMENT));
                        replayService.replay(anchor);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS));

        ReplayResult after = replayService.replay(anchor);
        log.info("并发增长 {} 次后重放 seq={}: resultMatches={}, 链校验={}",
                threads * perThread, anchor, after.resultMatches(), auditLog.verify());
        assertEquals(before.data(), after.data());
        assertTrue(after.resultMatches());
        assertEquals(-1, auditLog.verify());
    }
}
