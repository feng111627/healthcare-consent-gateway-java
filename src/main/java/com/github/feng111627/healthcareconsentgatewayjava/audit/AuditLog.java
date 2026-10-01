package com.github.feng111627.healthcareconsentgatewayjava.audit;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 防篡改审计日志。
 * - 每条记录携带 prevHash 与自身 hash，构成哈希链；
 * - 追加在锁内完成，并发写入不会破坏链的连续性；
 * - verify() 重放整条链，任何一条记录被改动都能被定位。
 */
@Component
public class AuditLog {

    private static final String GENESIS = "GENESIS";

    private final ReentrantLock lock = new ReentrantLock();
    private final List<AuditRecord> chain = new ArrayList<>();

    public AuditRecord append(String requesterId, String patientId, String purpose,
                              String decision, String reason,
                              String ruleVersion, String maskingPolicyVersion) {
        lock.lock();
        try {
            long sequence = chain.size();
            String prevHash = chain.isEmpty() ? GENESIS : chain.get(chain.size() - 1).hash();
            Instant timestamp = Instant.now();
            String hash = computeHash(sequence, timestamp, requesterId, patientId, purpose,
                    decision, reason, ruleVersion, maskingPolicyVersion, prevHash);
            AuditRecord record = new AuditRecord(sequence, timestamp, requesterId, patientId,
                    purpose, decision, reason, ruleVersion, maskingPolicyVersion, prevHash, hash);
            chain.add(record);
            return record;
        } finally {
            lock.unlock();
        }
    }

    public List<AuditRecord> records() {
        lock.lock();
        try {
            return List.copyOf(chain);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 校验整条链。
     * @return 第一条被篡改记录的序号；全部合法返回 -1。
     */
    public long verify() {
        lock.lock();
        try {
            String expectedPrev = GENESIS;
            for (AuditRecord r : chain) {
                if (!r.prevHash().equals(expectedPrev)) {
                    return r.sequence();
                }
                String recomputed = computeHash(r.sequence(), r.timestamp(), r.requesterId(),
                        r.patientId(), r.purpose(), r.decision(), r.reason(),
                        r.ruleVersion(), r.maskingPolicyVersion(), r.prevHash());
                if (!recomputed.equals(r.hash())) {
                    return r.sequence();
                }
                expectedPrev = r.hash();
            }
            return -1;
        } finally {
            lock.unlock();
        }
    }

    static String computeHash(long sequence, Instant timestamp, String requesterId,
                              String patientId, String purpose, String decision, String reason,
                              String ruleVersion, String maskingPolicyVersion, String prevHash) {
        String canonical = String.join("|",
                String.valueOf(sequence), timestamp.toString(), requesterId, patientId,
                purpose, decision, reason, ruleVersion, maskingPolicyVersion, prevHash);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
