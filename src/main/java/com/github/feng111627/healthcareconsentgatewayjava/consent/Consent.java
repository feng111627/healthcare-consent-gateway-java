package com.github.feng111627.healthcareconsentgatewayjava.consent;

import java.time.Instant;
import java.util.Set;

/** 一条患者授权记录（内存模拟）。 */
public class Consent {
    private final String consentId;
    private final String patientId;
    private final String requesterId;
    private final Set<Purpose> purposes;
    private final Instant expiresAt;
    private final Instant revokedAt;

    public Consent(String consentId, String patientId, String requesterId,
                   Set<Purpose> purposes, Instant expiresAt, Instant revokedAt) {
        this.consentId = consentId;
        this.patientId = patientId;
        this.requesterId = requesterId;
        this.purposes = purposes;
        this.expiresAt = expiresAt;
        this.revokedAt = revokedAt;
    }

    public String getConsentId() { return consentId; }
    public String getPatientId() { return patientId; }
    public String getRequesterId() { return requesterId; }
    public Set<Purpose> getPurposes() { return purposes; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }

    public boolean isRevoked() { return revokedAt != null; }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }
}
