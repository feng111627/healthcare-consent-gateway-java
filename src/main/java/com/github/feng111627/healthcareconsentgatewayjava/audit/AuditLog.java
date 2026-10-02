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
 * - verify() 重放整条链，任何一条记录被改动都能被定位；
 * - 记录携带授权快照与当时的脱敏返回内容，支持按记录做历史复现。
 */
@Component
public class AuditLog {

    private static final String GENESIS = "GENESIS";

    private final ReentrantLock lock = new ReentrantLock();
    private final List<AuditRecord> chain = new ArrayList<>();

    /** 兼容旧用法：无授权快照与返回内容。 */
    public AuditRecord append(String requesterId, String patientId, String purpose,
                              String decision, String reason,
                              String ruleVersion, String maskingPolicyVersion) {
        return append(requesterId, patientId, purpose, decision, reason,
                ruleVersion, maskingPolicyVersion, "NONE", null);
    }

    public AuditRecord append(String requesterId, String patientId, String purpose,
                              String decision, String reason,
                              String ruleVersion, String maskingPolicyVersion,
                              String consentSnapshot, String maskedPayload) {
        lock.lock();
        try {
            long sequence = chain.size();
            String prevHash = chain.isEmpty() ? GENESIS : chain.get(chain.size() - 1).hash();
            Instant timestamp = Instant.now();
            String hash = computeHash(sequence, timestamp, requesterId, patientId, purpose,
                    decision, reason, ruleVersion, maskingPolicyVersion,
                    consentSnapshot, maskedPayload, prevHash);
            AuditRecord record = new AuditRecord(sequence, timestamp, requesterId, patientId,
                    purpose, decision, reason, ruleVersion, maskingPolicyVersion,
                    consentSnapshot, maskedPayload, prevHash, hash);
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

    /** 按序号取记录（重放入口），不存在返回 null。 */
    public AuditRecord recordAt(long sequence) {
        lock.lock();
        try {
            if (sequence < 0 || sequence >= chain.size()) {
                return null;
            }
            return chain.get((int) sequence);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 校验单条记录：自身哈希重算一致，且与前序记录的链接完好。
     * 重放历史记录前调用，确认该记录未被篡改。
     */
    public boolean verifyRecord(long sequence) {
        lock.lock();
        try {
            if (sequence < 0 || sequence >= chain.size()) {
                return false;
            }
            AuditRecord r = chain.get((int) sequence);
            String expectedPrev = sequence == 0 ? GENESIS : chain.get((int) sequence - 1).hash();
            if (!r.prevHash().equals(expectedPrev)) {
                return false;
            }
            String recomputed = computeHash(r.sequence(), r.timestamp(), r.requesterId(),
                    r.patientId(), r.purpose(), r.decision(), r.reason(),
                    r.ruleVersion(), r.maskingPolicyVersion(),
                    r.consentSnapshot(), r.maskedPayload(), r.prevHash());
            return recomputed.equals(r.hash());
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
                        r.ruleVersion(), r.maskingPolicyVersion(),
                        r.consentSnapshot(), r.maskedPayload(), r.prevHash());
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
                              String ruleVersion, String maskingPolicyVersion,
                              String consentSnapshot, String maskedPayload, String prevHash) {
        String canonical = String.join("|",
                String.valueOf(sequence), timestamp.toString(), requesterId, patientId,
                purpose, decision, reason, ruleVersion, maskingPolicyVersion,
                String.valueOf(consentSnapshot), String.valueOf(maskedPayload), prevHash);
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
