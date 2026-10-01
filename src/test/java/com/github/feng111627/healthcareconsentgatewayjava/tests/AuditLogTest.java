package com.github.feng111627.healthcareconsentgatewayjava.tests;

import com.github.feng111627.healthcareconsentgatewayjava.audit.AuditLog;
import com.github.feng111627.healthcareconsentgatewayjava.audit.AuditRecord;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditLogTest {

    private static final Logger log = LoggerFactory.getLogger(AuditLogTest.class);

    @Test
    void concurrentAppendsKeepChainVerifiable() throws InterruptedException {
        AuditLog auditLog = new AuditLog();
        int threads = 16;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);

        for (int t = 0; t < threads; t++) {
            final String requester = "requester-" + t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        auditLog.append(requester, "P001", "TREATMENT",
                                "ALLOWED", "ok-" + i, "consent-rules-v1", "masking-v1");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        List<AuditRecord> records = auditLog.records();
        log.info("输入: {} 线程 x {} 条并发写入", threads, perThread);
        log.info("判定依据: 总记录数={}, 首条prevHash={}, 末条hash={}",
                records.size(), records.get(0).prevHash(), records.get(records.size() - 1).hash());

        assertEquals(threads * perThread, records.size());
        // 序号连续无空洞
        for (int i = 0; i < records.size(); i++) {
            assertEquals(i, records.get(i).sequence());
        }
        // 并发写入后整条链仍可校验
        assertEquals(-1, auditLog.verify());
    }

    @Test
    void tamperedRecordIsDetected() throws Exception {
        AuditLog auditLog = new AuditLog();
        auditLog.append("doctor-A", "P001", "TREATMENT", "ALLOWED", "ok", "consent-rules-v1", "masking-v1");
        AuditRecord second = auditLog.append("marketer-B", "P001", "MARKETING",
                "DENIED_PURPOSE_MISMATCH", "denied", "consent-rules-v1", "masking-v1");
        auditLog.append("doctor-A", "P002", "TREATMENT", "ALLOWED", "ok", "consent-rules-v1", "masking-v1");

        // 模拟篡改：把第二条记录的判定结果从 DENIED 改成 ALLOWED
        AuditRecord forged = new AuditRecord(second.sequence(), second.timestamp(),
                second.requesterId(), second.patientId(), second.purpose(),
                "ALLOWED", second.reason(), second.ruleVersion(),
                second.maskingPolicyVersion(), second.prevHash(), second.hash());
        replaceRecord(auditLog, 1, forged);

        long tamperedAt = auditLog.verify();
        log.info("输入: 篡改 sequence=1 的 decision 为 ALLOWED");
        log.info("判定依据: verify() 返回首个被篡改序号={}", tamperedAt);

        assertEquals(1, tamperedAt);
    }

    @Test
    void intactChainVerifiesToMinusOne() {
        AuditLog auditLog = new AuditLog();
        auditLog.append("doctor-A", "P001", "TREATMENT", "ALLOWED", "ok", "consent-rules-v1", "masking-v1");
        auditLog.append("doctor-A", "P001", "BILLING", "DENIED_EXPIRED", "expired", "consent-rules-v1", "masking-v1");
        log.info("输入: 顺序写入 2 条, 未篡改");
        log.info("判定依据: verify()={}", auditLog.verify());
        assertEquals(-1, auditLog.verify());
        // 相邻记录哈希链接
        List<AuditRecord> records = auditLog.records();
        assertEquals(records.get(0).hash(), records.get(1).prevHash());
        assertNotEquals(records.get(0).hash(), records.get(1).hash());
    }

    @SuppressWarnings("unchecked")
    private void replaceRecord(AuditLog auditLog, int index, AuditRecord record) throws Exception {
        Field chainField = AuditLog.class.getDeclaredField("chain");
        chainField.setAccessible(true);
        ((List<AuditRecord>) chainField.get(auditLog)).set(index, record);
    }
}
