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
 * - verify() 重放整条链，任何一条记录被改动（含范围结论、结果哈希）都能被定位。
 */
@Component
public class AuditLog {

    private static final String GENESIS = "GENESIS";

    private final ReentrantLock lock = new ReentrantLock();
    private final List<AuditRecord> chain = new ArrayList<>();

    /** 便捷追加：无范围结论、无结果哈希（测试直接构造审计链时使用）。 */
    public AuditRecord append(String requesterId, String patientId, String purpose,
                              String decision, String reason,
                              String ruleVersion, String maskingPolicyVersion) {
        return append(requesterId, patientId, purpose, decision, reason,
                ruleVersion, maskingPolicyVersion, List.of(), "");
    }

    /**
     * 追加一条访问记录。
     * @param deniedSections 部分授权下被拒绝的数据区段（全集授权给空列表）
     * @param resultHash 当时返回结果（脱敏数据/拒绝结论）的哈希，供复现比对
     */
    public AuditRecord append(String requesterId, String patientId, String purpose,
                              String decision, String reason,
                              String ruleVersion, String maskingPolicyVersion,
                              List<String> deniedSections, String resultHash) {
        lock.lock();
        try {
            long sequence = chain.size();
            String prevHash = chain.isEmpty() ? GENESIS : chain.get(chain.size() - 1).hash();
            Instant timestamp = Instant.now();
            List<String> sections = deniedSections == null ? List.of() : List.copyOf(deniedSections);
            String hash = computeHash(sequence, timestamp, requesterId, patientId, purpose,
                    decision, reason, ruleVersion, maskingPolicyVersion,
                    sections, resultHash == null ? "" : resultHash, prevHash);
            AuditRecord record = new AuditRecord(sequence, timestamp, requesterId, patientId,
                    purpose, decision, reason, ruleVersion, maskingPolicyVersion,
                    sections, resultHash == null ? "" : resultHash, prevHash, hash);
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
                        r.deniedSections(), r.resultHash(), r.prevHash());
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
                              List<String> deniedSections, String resultHash, String prevHash) {
        String canonical = String.join("|",
                String.valueOf(sequence), timestamp.toString(), requesterId, patientId,
                purpose, decision, reason, ruleVersion, maskingPolicyVersion,
                String.join(",", deniedSections), resultHash, prevHash);
        return sha256Hex(canonical);
    }

    /** 对任意字符串计算 SHA-256 十六进制摘要（结果哈希与链哈希统一口径）。 */
    public static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
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
