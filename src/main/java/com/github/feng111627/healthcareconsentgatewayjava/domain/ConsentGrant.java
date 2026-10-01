package com.github.feng111627.healthcareconsentgatewayjava.domain;

import java.time.Instant;
import java.util.List;

/**
 * 患者授予某个请求方的授权。
 * 撤回状态用 volatile 标记，保证并发读的可见性；判定依据以快照形式留痕。
 */
public final class ConsentGrant {
    private final String consentId;
    private final String patientId;
    private final String requesterId;
    private final List<Purpose> allowedPurposes;
    private final Instant expiresAt;
    private final Instant grantedAt;
    private volatile boolean revoked;
    private volatile Instant revokedAt;

    public ConsentGrant(String consentId, String patientId, String requesterId,
                        List<Purpose> allowedPurposes, Instant expiresAt, Instant grantedAt,
                        boolean revoked, Instant revokedAt) {
        this.consentId = consentId;
        this.patientId = patientId;
        this.requesterId = requesterId;
        this.allowedPurposes = allowedPurposes;
        this.expiresAt = expiresAt;
        this.grantedAt = grantedAt;
        this.revoked = revoked;
        this.revokedAt = revokedAt;
    }

    public String consentId() { return consentId; }
    public String patientId() { return patientId; }
    public String requesterId() { return requesterId; }
    public List<Purpose> allowedPurposes() { return allowedPurposes; }
    public Instant expiresAt() { return expiresAt; }
    public Instant grantedAt() { return grantedAt; }
    public boolean revoked() { return revoked; }
    public Instant revokedAt() { return revokedAt; }

    /** 撤回是一次性动作：只在尚未撤回时生效，保留首次撤回时间。 */
    public void revoke(Instant at) {
        if (!revoked) {
            revoked = true;
            revokedAt = at;
        }
    }
}
