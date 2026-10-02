package com.github.feng111627.healthcareconsentgatewayjava.consent;

import java.time.Instant;
import java.util.Set;

/** 一条患者授权记录（内存模拟）。scopes 为 null/含 "*" 表示全集授权，否则只放开列出的数据区段。 */
public class Consent {
    private final String consentId;
    private final String patientId;
    private final String requesterId;
    private final Set<Purpose> purposes;
    private final Instant expiresAt;
    private final Instant revokedAt;
    private final Set<String> scopes;

    public Consent(String consentId, String patientId, String requesterId,
                   Set<Purpose> purposes, Instant expiresAt, Instant revokedAt) {
        this(consentId, patientId, requesterId, purposes, expiresAt, revokedAt, null);
    }

    public Consent(String consentId, String patientId, String requesterId,
                   Set<Purpose> purposes, Instant expiresAt, Instant revokedAt,
                   Set<String> scopes) {
        this.consentId = consentId;
        this.patientId = patientId;
        this.requesterId = requesterId;
        this.purposes = purposes;
        this.expiresAt = expiresAt;
        this.revokedAt = revokedAt;
        this.scopes = scopes;
    }

    public String getConsentId() { return consentId; }
    public String getPatientId() { return patientId; }
    public String getRequesterId() { return requesterId; }
    public Set<Purpose> getPurposes() { return purposes; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    /** @return null 表示全集授权；非 null 时只允许其中数据区段（"*" 同样表示全集）。 */
    public Set<String> getScopes() { return scopes; }

    public boolean isRevoked() { return revokedAt != null; }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public boolean isFullScope() {
        return scopes == null || scopes.contains("*");
    }
}
