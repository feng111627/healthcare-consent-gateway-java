package com.github.feng111627.healthcareconsentgatewayjava.consent;

import java.time.Instant;
import java.util.Set;

/** 访问时刻授权状态的不可变快照，用于历史复现，不受后续授权变更影响。 */
public record ConsentSnapshot(
        String consentId,
        String patientId,
        String requesterId,
        Set<Purpose> purposes,
        Instant expiresAt,
        Instant revokedAt,
        Set<String> scopes) {

    public static ConsentSnapshot from(Consent consent) {
        return new ConsentSnapshot(
                consent.getConsentId(),
                consent.getPatientId(),
                consent.getRequesterId(),
                Set.copyOf(consent.getPurposes()),
                consent.getExpiresAt(),
                consent.getRevokedAt(),
                consent.getScopes() == null ? null : Set.copyOf(consent.getScopes()));
    }

    public boolean isRevoked() { return revokedAt != null; }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public boolean isFullScope() {
        return scopes == null || scopes.contains("*");
    }
}
