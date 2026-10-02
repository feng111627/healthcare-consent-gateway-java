package com.github.feng111627.healthcareconsentgatewayjava.tests;

import com.github.feng111627.healthcareconsentgatewayjava.audit.AuditLog;
import com.github.feng111627.healthcareconsentgatewayjava.audit.AuditRecord;
import com.github.feng111627.healthcareconsentgatewayjava.audit.CanonicalPayload;
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
import com.github.feng111627.healthcareconsentgatewayjava.masking.MaskingPolicy;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 历史复现：任意一条审计记录都能按当时生效的规则/策略版本，
 * 重放出与当时完全一致的判定结论与脱敏结果；
 * 新版本发布后旧记录不被带跑，新老记录共存、互不影响。
 */
class ReplayServiceTest {

    private static final Logger log = LoggerFactory.getLogger(ReplayServiceTest.class);
    private static final Instant NOW = Instant.now();

    private InMemoryConsentRepository consentRepository;
    private ConsentRuleRegistry ruleRegistry;
    private MaskingService maskingService;
    private AuditLog auditLog;
    private PatientRecordStore recordStore;
    private ReplayService replayService;

    @BeforeEach
    void setUp() {
        consentRepository = new InMemoryConsentRepository();
        ruleRegistry = new ConsentRuleRegistry();
        maskingService = new MaskingService(new MockEnvironment());
        auditLog = new AuditLog();
        recordStore = new PatientRecordStore();
        replayService = new ReplayService(auditLog, ruleRegistry, maskingService);
    }

    private PatientDataGateway gatewayAt(String ruleVersion) {
        return new PatientDataGateway(
                new ConsentEvaluator(consentRepository, ruleRegistry, ruleVersion),
                maskingService, auditLog, recordStore);
    }

    private void logReplay(long sequence, ReplayResult result) {
        log.info("输入: replay(sequence={})", sequence);
        log.info("判定依据: 记录[decision={}, ruleVersion={}, maskingPolicyVersion={}], "
                        + "重放[decision={}, consistent={}]",
                result.record().decision(), result.record().ruleVersion(),
                result.record().maskingPolicyVersion(),
                result.replayedDecision(), result.consistent());
    }

    @Test
    void oldRecordReplaysToOriginalConclusionAfterNewRuleVersionPublished() {
        // 授权不携带任何数据维度：v1 下整体允许，v2 下等同于未授予任何维度
        consentRepository.save(new Consent("C1", "P001", "doctor-A",
                Set.of(Purpose.TREATMENT), Set.of(), NOW.plus(30, ChronoUnit.DAYS), null));

        // 第一阶段：v1 规则生效时的访问
        GatewayResponse v1Response = gatewayAt("consent-rules-v1")
                .access(new AccessRequest("doctor-A", "P001", Purpose.TREATMENT));
        assertEquals(Decision.ALLOWED, v1Response.decision());
        long v1Seq = auditLog.records().size() - 1;

        // 发布新规则版本（v2 已在注册表中，模拟规则升级后切到 v2）
        GatewayResponse v2Response = gatewayAt("consent-rules-v2")
                .access(new AccessRequest("doctor-A", "P001", Purpose.TREATMENT));
        assertEquals(Decision.DENIED_NO_SCOPE, v2Response.decision());
        long v2Seq = auditLog.records().size() - 1;
        log.info("输入: 同一授权 C1 先后在 v1/v2 规则下访问");
        log.info("判定依据: v1 访问 decision={}, v2 访问 decision={}",
                v1Response.decision(), v2Response.decision());

        // 重放 v1 时代的记录：结论必须是当时的 ALLOWED，不能被 v2 带跑
        ReplayResult replayV1 = replayService.replay(v1Seq);
        logReplay(v1Seq, replayV1);
        assertTrue(replayV1.consistent());
        assertEquals("ALLOWED", replayV1.replayedDecision());
        assertNotEquals(replayV1.replayedDecision(), v2Response.decision().name());
        // 当时返回的脱敏结果原样复现
        assertEquals(CanonicalPayload.of(v1Response.data()), replayV1.maskedPayload());

        // 重放 v2 时代的记录：各自的结论各自复现，互不影响
        ReplayResult replayV2 = replayService.replay(v2Seq);
        logReplay(v2Seq, replayV2);
        assertTrue(replayV2.consistent());
        assertEquals("DENIED_NO_SCOPE", replayV2.replayedDecision());

        // 新老记录共存，整条链校验不受影响
        assertEquals(-1, auditLog.verify());
    }

    @Test
    void oldRecordReplaysAfterNewMaskingPolicyPublished() {
        consentRepository.save(new Consent("C2", "P001", "doctor-A",
                Set.of(Purpose.TREATMENT), NOW.plus(30, ChronoUnit.DAYS), null));
        GatewayResponse response = gatewayAt("consent-rules-v1")
                .access(new AccessRequest("doctor-A", "P001", Purpose.TREATMENT));
        long seq = auditLog.records().size() - 1;
        String payloadAtAccess = auditLog.recordAt(seq).maskedPayload();
        log.info("输入: masking-v1 下访问产生记录 seq={}, 随后发布 masking-v2", seq);

        // 发布新脱敏策略版本（只增不改）
        maskingService.publishPolicy(new MaskingPolicy("masking-v2",
                Map.of("name", MaskingPolicy.Strategy.FULL)));
        // 同名版本拒绝覆盖，防止历史记录被带跑
        assertThrows(IllegalArgumentException.class, () ->
                maskingService.publishPolicy(new MaskingPolicy("masking-v2", Map.of())));

        ReplayResult replay = replayService.replay(seq);
        logReplay(seq, replay);
        assertTrue(replay.consistent());
        assertEquals("masking-v1", replay.record().maskingPolicyVersion());
        // 脱敏结果与当时返回完全一致，不受 masking-v2 影响
        assertEquals(payloadAtAccess, replay.maskedPayload());
        assertEquals(CanonicalPayload.of(response.data()), replay.maskedPayload());
        assertEquals(-1, auditLog.verify());
    }

    @Test
    void partialAccessRecordReplaysToSameScopedConclusion() {
        consentRepository.save(new Consent("C3", "P001", "analyst-B",
                Set.of(Purpose.RESEARCH), Set.of("identity"),
                NOW.plus(30, ChronoUnit.DAYS), null));
        GatewayResponse response = gatewayAt("consent-rules-v2")
                .access(new AccessRequest("analyst-B", "P001", Purpose.RESEARCH));
        assertEquals(Decision.ALLOWED_PARTIAL, response.decision());
        long seq = auditLog.records().size() - 1;

        ReplayResult replay = replayService.replay(seq);
        logReplay(seq, replay);
        assertTrue(replay.consistent());
        assertEquals("ALLOWED_PARTIAL", replay.replayedDecision());
        // 当时返回的内容里未授权维度确实被扣留
        assertTrue(replay.maskedPayload().contains("[UNAUTHORIZED]"));
        assertEquals(CanonicalPayload.of(response.data()), replay.maskedPayload());
    }

    @Test
    void deniedRecordReplaysToSameDenialCategory() {
        // 无授权访问 -> DENIED_NO_CONSENT
        gatewayAt("consent-rules-v2")
                .access(new AccessRequest("stranger", "P001", Purpose.TREATMENT));
        long seq = auditLog.records().size() - 1;

        ReplayResult replay = replayService.replay(seq);
        logReplay(seq, replay);
        assertTrue(replay.consistent());
        assertEquals("DENIED_NO_CONSENT", replay.replayedDecision());
    }

    @Test
    void replayStaysConsistentUnderConcurrentAccessAndGrowth() throws InterruptedException {
        consentRepository.save(new Consent("C4", "P001", "doctor-A",
                Set.of(Purpose.TREATMENT),
                Set.of("identity", "contact", "address", "medical"),
                NOW.plus(30, ChronoUnit.DAYS), null));
        PatientDataGateway gateway = gatewayAt("consent-rules-v2");

        // 先产生一批基线记录
        int baseline = 20;
        for (int i = 0; i < baseline; i++) {
            gateway.access(new AccessRequest("doctor-A", "P001", Purpose.TREATMENT));
        }

        int writers = 8;
        int writesPerThread = 25;
        int readers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger replayFailures = new AtomicInteger();

        for (int w = 0; w < writers; w++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < writesPerThread; i++) {
                        gateway.access(new AccessRequest("doctor-A", "P001", Purpose.TREATMENT));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        for (int r = 0; r < readers; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    // 记录持续增长期间，并发重放基线记录 + 校验整条链
                    for (int i = 0; i < 50; i++) {
                        ReplayResult result = replayService.replay(i % baseline);
                        if (!result.consistent() || !"ALLOWED".equals(result.replayedDecision())) {
                            replayFailures.incrementAndGet();
                        }
                        auditLog.verify();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        List<AuditRecord> records = auditLog.records();
        log.info("输入: {} 写线程 x {} 次访问 + {} 读线程并发重放/校验",
                writers, writesPerThread, readers);
        log.info("判定依据: 总记录数={}, 重放失败数={}, verify()={}",
                records.size(), replayFailures.get(), auditLog.verify());

        assertEquals(0, replayFailures.get());
        assertEquals(baseline + writers * writesPerThread, records.size());
        assertEquals(-1, auditLog.verify());
        // 增长后的每一条记录都能一致重放
        for (AuditRecord record : records) {
            ReplayResult result = replayService.replay(record.sequence());
            assertTrue(result.consistent(), "replay inconsistent at seq=" + record.sequence());
        }
    }
}
