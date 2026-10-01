package com.github.feng111627.healthcareconsentgatewayjava.tests.unit;

import com.github.feng111627.healthcareconsentgatewayjava.config.DataInitializer;
import com.github.feng111627.healthcareconsentgatewayjava.domain.AccessDecision;
import com.github.feng111627.healthcareconsentgatewayjava.domain.AccessRequest;
import com.github.feng111627.healthcareconsentgatewayjava.domain.DecisionReason;
import com.github.feng111627.healthcareconsentgatewayjava.domain.Purpose;
import com.github.feng111627.healthcareconsentgatewayjava.model.AuditRecord;
import com.github.feng111627.healthcareconsentgatewayjava.model.IntegrityReport;
import com.github.feng111627.healthcareconsentgatewayjava.model.MaskingRuleSet;
import com.github.feng111627.healthcareconsentgatewayjava.model.MaskStrategy;
import com.github.feng111627.healthcareconsentgatewayjava.policy.InMemoryPolicyService;
import com.github.feng111627.healthcareconsentgatewayjava.repo.InMemoryAuditRepository;
import com.github.feng111627.healthcareconsentgatewayjava.repo.InMemoryConsentRepository;
import com.github.feng111627.healthcareconsentgatewayjava.service.AuditService;
import com.github.feng111627.healthcareconsentgatewayjava.service.DefaultAuditService;
import com.github.feng111627.healthcareconsentgatewayjava.service.DefaultAuthorizationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 审计哈希链：并发写、篡改检测、历史复现。 */
class AuditChainTest {

    private static final Logger log = LoggerFactory.getLogger(AuditChainTest.class);

    private TestContext ctx() {
        InMemoryPolicyService policies = new InMemoryPolicyService();
        policies.publish(DataInitializer.POLICY_V1, DataInitializer.maskingV1());
        InMemoryConsentRepository consents = new InMemoryConsentRepository();
        DefaultAuthorizationService auth =
                new DefaultAuthorizationService(consents, policies);
        InMemoryAuditRepository repo = new InMemoryAuditRepository();
        DefaultAuditService audit = new DefaultAuditService(repo, auth, policies);
        return new TestContext(policies, auth, audit);
    }

    private record TestContext(InMemoryPolicyService policies,
                               DefaultAuthorizationService auth,
                               DefaultAuditService audit) {
    }

    @Test
    @DisplayName("并发写入 200 条：seq 连续、哈希链完整、无重复 hash")
    void concurrentAppendsKeepChainIntact() throws InterruptedException {
        TestContext c = ctx();
        int threads = 20;
        int perThread = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        for (int t = 0; t < threads; t++) {
            final int idx = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        AccessRequest req = new AccessRequest(
                                "req-" + idx, "P-1", Purpose.TREATMENT);
                        AccessDecision d = c.auth.evaluate(req, Instant.now());
                        c.audit.record(req, d, false, null);
                    }
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(errors).isEmpty();

        List<AuditRecord> all = c.audit.all();
        assertThat(all).hasSize(threads * perThread);

        // seq 必须 1..N 连续
        List<Long> seqs = all.stream().map(AuditRecord::seq).sorted().toList();
        for (int i = 0; i < seqs.size(); i++) {
            assertThat(seqs.get(i)).isEqualTo(i + 1L);
        }
        // 每条 hash 唯一（prevHash/seq/请求不同）
        assertThat(all.stream().map(AuditRecord::recordHash).distinct()).hasSameSizeAs(all);

        IntegrityReport report = c.audit.verifyChain();
        log.info("[测试断言] 并发写后链校验: valid={}, count={}, broken={}",
                report.valid(), report.recordCount(), report.brokenAtSeq());
        assertThat(report.valid()).isTrue();
    }

    @Test
    @DisplayName("改动任意一条记录的字段 -> verifyChain 报告具体 seq 且 valid=false")
    void tamperedRecordDetected() {
        TestContext c = ctx();
        AccessRequest r1 = new AccessRequest("a", "P-1", Purpose.TREATMENT);
        AccessRequest r2 = new AccessRequest("b", "P-1", Purpose.PAYMENT);
        AccessRequest r3 = new AccessRequest("d", "P-1", Purpose.TREATMENT);
        c.audit.record(r1, c.auth.evaluate(r1, Instant.now()), false, null);
        c.audit.record(r2, c.auth.evaluate(r2, Instant.now()), false, null);
        c.audit.record(r3, c.auth.evaluate(r3, Instant.now()), false, null);
        assertThat(c.audit.verifyChain().valid()).isTrue();

        List<AuditRecord> all = new ArrayList<>(c.audit.all());
        AuditRecord middle = all.get(1);
        // 模拟磁盘/传输层被篡改：保留 hash 但把用途改成越权场景的值
        AccessRequest forged = new AccessRequest("b", "P-1", Purpose.MARKETING);
        AuditRecord tampered = new AuditRecord(middle.seq(), middle.requestId(),
                middle.timestamp(), forged, middle.decision(), middle.basis(),
                middle.policyVersion(), middle.masked(), middle.maskingErrorCode(),
                middle.prevHash(), middle.recordHash());
        List<AuditRecord> forgedChain = new ArrayList<>(all);
        forgedChain.set(1, tampered);

        IntegrityReport report = c.audit.verifyChain(forgedChain);
        log.info("[测试断言] 篡改 seq=2 后校验: valid={}, brokenAt={}, details={}",
                report.valid(), report.brokenAtSeq(), report.details());
        assertThat(report.valid()).isFalse();
        assertThat(report.brokenAtSeq()).contains(2L);
    }

    @Test
    @DisplayName("删除中间一条记录 -> 断链在后继位置被检出")
    void removedRecordDetected() {
        TestContext c = ctx();
        for (int i = 0; i < 4; i++) {
            AccessRequest req = new AccessRequest("x" + i, "P-1", Purpose.TREATMENT);
            c.audit.record(req, c.auth.evaluate(req, Instant.now()), false, null);
        }
        List<AuditRecord> all = new ArrayList<>(c.audit.all());
        all.remove(1);
        IntegrityReport report = c.audit.verifyChain(all);
        assertThat(report.valid()).isFalse();
        assertThat(report.brokenAtSeq()).isNotEmpty();
    }

    @Test
    @DisplayName("策略发布新版本后，历史审计记录仍能复现当时的判定依据与结论")
    void replaySurvivesPolicyChange() {
        TestContext c = ctx();
        // 先在 v1 下产生一次“越权”判定并留痕
        AccessRequest req = new AccessRequest("lab", "P-1", Purpose.TREATMENT);
        AccessDecision original = c.auth.evaluate(req, Instant.parse("2026-10-01T08:00:00Z"));
        // evaluate 用 now，这里手动构造一个稳定结论用于留痕
        AuditRecord rec = c.audit.record(req, original, false, null);
        String requestId = rec.requestId();
        String oldPolicy = rec.policyVersion();
        assertThat(original.reason()).isEqualTo(DecisionReason.CONSENT_NOT_FOUND);

        // 调整策略：发布 v2（脱敏规则变化），历史版本仍保留
        c.policies.publish("policy-v2", new MaskingRuleSet("policy-v2",
                Map.of("phone", MaskStrategy.REDACT), List.of()));
        assertThat(c.policies.current().version()).isEqualTo("policy-v2");

        AuditService.ReplayResult replay = c.audit.replay(requestId);
        log.info("[测试断言] 历史复现: requestId={}, 历史策略={}, 当时结论={}, 复现结论={}",
                requestId, oldPolicy, original.reason(),
                replay.reproducedDecision().reason());
        assertThat(replay.record().policyVersion()).isEqualTo(DataInitializer.POLICY_V1);
        assertThat(replay.reproducedDecision().reason()).isEqualTo(original.reason());
        assertThat(replay.reproducedDecision().basis().policyVersion())
                .isEqualTo(DataInitializer.POLICY_V1);
        // 链依旧完整
        assertThat(c.audit.verifyChain().valid()).isTrue();
    }
}
