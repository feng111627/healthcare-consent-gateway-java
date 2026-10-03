package com.github.feng111627.healthcareconsentgatewayjava.consent;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 一条患者授权记录（内存模拟）。
 * scopes 为授权开放的数据维度，三种语义明确区分：
 * - null：未写维度的旧授权（升级前创建），按全量访问处理；
 * - 空集：明确授予了「零个维度」，等同于整体拒绝（DENIED_NO_SCOPE）；
 * - 非空集：按「部分可见」判定。
 */
public class Consent {
    private final String consentId;
    private final String patientId;
    private final String requesterId;
    private final Set<Purpose> purposes;
    private final Set<String> scopes;
    private final Instant expiresAt;
    private final Instant revokedAt;

    /** 兼容旧用法：不携带数据维度授权（scopes=null，升级后仍按全量访问处理）。 */
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

    /** 授权的数据维度；null 表示未写维度的旧授权（全量访问），空集表示明确未授予任何维度。 */
    public Set<String> getScopes() { return scopes; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }

    public boolean isRevoked() { return revokedAt != null; }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /**
     * 规范化快照：包含判定所需的全部输入，字段排序保证确定性。
     * 快照写入审计记录，重放时据此还原判定输入，与当前授权库状态无关。
     * scopes 编码：null（旧授权）-> "null"；空集 -> 空串；非空 -> 逗号分隔，
     * 三种形态在快照中可区分、可还原。
     */
    public String toSnapshot() {
        String purposesPart = purposes.stream().map(Enum::name).sorted()
                .collect(Collectors.joining(","));
        String scopesPart = scopes == null ? "null"
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
        Set<String> scopes = null;
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
                    if (!value.equals("null")) {
                        // 空串 = 明确授予零个维度；否则按逗号分隔还原
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
