package com.github.feng111627.healthcareconsentgatewayjava.consent;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 一条患者授权记录（内存模拟）。
 * scopes 为授权开放的数据维度：null 表示该授权未写明维度（升级前的旧授权，
 * 按全量访问处理，不做维度过滤）；非 null（含空集）表示确实列了维度，
 * 走「部分可见」判定，空集即一个维度都没授予。
 */
public class Consent {
    private final String consentId;
    private final String patientId;
    private final String requesterId;
    private final Set<Purpose> purposes;
    private final Set<String> scopes;
    private final Instant expiresAt;
    private final Instant revokedAt;

    /** 兼容旧用法：不携带数据维度授权（scopes=null，按全量访问处理）。 */
    public Consent(String consentId, String patientId, String requesterId,
                   Set<Purpose> purposes, Instant expiresAt, Instant revokedAt) {
        this(consentId, patientId, requesterId, purposes, null, expiresAt, revokedAt);
    }

    public Consent(String consentId, String patientId, String requesterId,
                   Set<Purpose> purposes, Set<String> scopes,
                   Instant expiresAt, Instant revokedAt) {
        this.consentId = consentId;
        this.patientId = patientId;
        this.requesterId = requesterId;
        this.purposes = purposes;
        this.scopes = scopes;
        this.expiresAt = expiresAt;
        this.revokedAt = revokedAt;
    }

    public String getConsentId() { return consentId; }
    public String getPatientId() { return patientId; }
    public String getRequesterId() { return requesterId; }
    public Set<Purpose> getPurposes() { return purposes; }
    /** 已列出的数据维度；未写明维度（旧授权）时返回空集，判定时须先查 hasScopeRestriction()。 */
    public Set<String> getScopes() { return scopes == null ? Set.of() : scopes; }

    /** 是否写明了数据维度：false=旧授权（全量访问），true=走部分可见判定。 */
    public boolean hasScopeRestriction() { return scopes != null; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }

    public boolean isRevoked() { return revokedAt != null; }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /**
     * 规范化快照：包含判定所需的全部输入，字段排序保证确定性。
     * 快照写入审计记录，重放时据此还原判定输入，与当前授权库状态无关。
     * scopes 未写明（旧授权）序列化为 "scopes=*"，与空集 "scopes=" 明确区分，
     * 保证重放时能还原「全量访问」与「零维度授权」两种不同的判定输入。
     */
    public String toSnapshot() {
        String purposesPart = purposes.stream().map(Enum::name).sorted()
                .collect(Collectors.joining(","));
        String scopesPart = scopes == null
                ? "*"
                : scopes.stream().sorted().collect(Collectors.joining(","));
        return String.join(";",
                "consentId=" + consentId,
                "patientId=" + patientId,
                "requesterId=" + requesterId,
                "purposes=" + purposesPart,
                "scopes=" + scopesPart,
                "expiresAt=" + expiresAt,
                "revokedAt=" + revokedAt);
    }

    /** 从快照还原授权记录；"NONE" 表示当时无授权，返回 null。 */
    public static Consent fromSnapshot(String snapshot) {
        if (snapshot == null || snapshot.equals("NONE")) {
            return null;
        }
        String consentId = null, patientId = null, requesterId = null;
        Set<Purpose> purposes = new TreeSet<>();
        Set<String> scopes = null; // null = 未写明维度（旧授权，全量访问）
        Instant expiresAt = null, revokedAt = null;
        for (String part : snapshot.split(";")) {
            int idx = part.indexOf('=');
            String key = part.substring(0, idx);
            String value = part.substring(idx + 1);
            switch (key) {
                case "consentId" -> consentId = value;
                case "patientId" -> patientId = value;
                case "requesterId" -> requesterId = value;
                case "purposes" -> {
                    if (!value.isEmpty()) {
                        Arrays.stream(value.split(",")).map(Purpose::valueOf).forEach(purposes::add);
                    }
                }
                case "scopes" -> {
                    if (!value.equals("*")) {
                        scopes = new TreeSet<>();
                        if (!value.isEmpty()) {
                            scopes.addAll(Arrays.asList(value.split(",")));
                        }
                    }
                }
                case "expiresAt" -> expiresAt = value.equals("null") ? null : Instant.parse(value);
                case "revokedAt" -> revokedAt = value.equals("null") ? null : Instant.parse(value);
                default -> throw new IllegalArgumentException("unknown snapshot key: " + key);
            }
        }
        return new Consent(consentId, patientId, requesterId, purposes, scopes,
                expiresAt, revokedAt);
    }
}
