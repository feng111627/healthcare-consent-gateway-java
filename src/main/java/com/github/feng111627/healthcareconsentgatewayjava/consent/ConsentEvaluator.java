package com.github.feng111627.healthcareconsentgatewayjava.consent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 授权判定器：结合请求方用途判断授权是否成立。
 * 拒绝原因明确区分：无授权 / 已撤回 / 已过期 / 用途超出范围。
 * ruleVersion 标识当前判定规则版本，写入审计以便复现。
 */
@Component
public class ConsentEvaluator {

    private final ConsentRepository repository;
    private final String ruleVersion;

    public ConsentEvaluator(ConsentRepository repository,
                            @Value("${gateway.consent.rule-version:consent-rules-v1}") String ruleVersion) {
        this.repository = repository;
        this.ruleVersion = ruleVersion;
    }

    public DecisionResult evaluate(String patientId, String requesterId, Purpose purpose, Instant now) {
        var consentOpt = repository.findByPatientAndRequester(patientId, requesterId);
        if (consentOpt.isEmpty()) {
            return new DecisionResult(Decision.DENIED_NO_CONSENT,
                    "no consent found for patient=" + patientId + ", requester=" + requesterId,
                    ruleVersion);
        }
        Consent consent = consentOpt.get();
        // 撤回优先于过期判定：撤回是患者主动行为，语义更强
        if (consent.isRevoked()) {
            return new DecisionResult(Decision.DENIED_REVOKED,
                    "consent " + consent.getConsentId() + " revoked at " + consent.getRevokedAt(),
                    ruleVersion);
        }
        if (consent.isExpired(now)) {
            return new DecisionResult(Decision.DENIED_EXPIRED,
                    "consent " + consent.getConsentId() + " expired at " + consent.getExpiresAt(),
                    ruleVersion);
        }
        if (!consent.getPurposes().contains(purpose)) {
            return new DecisionResult(Decision.DENIED_PURPOSE_MISMATCH,
                    "purpose " + purpose + " not covered by consent " + consent.getConsentId()
                            + " (allowed: " + consent.getPurposes() + ")",
                    ruleVersion);
        }
        return new DecisionResult(Decision.ALLOWED,
                "consent " + consent.getConsentId() + " covers purpose " + purpose,
                ruleVersion);
    }
}
