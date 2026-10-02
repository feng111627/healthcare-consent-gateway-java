package com.github.feng111627.healthcareconsentgatewayjava.consent;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 授权规则 v2：规则升级示例——过期优先于撤回（到期授权一律不再放行，即使撤回时间更晚）。
 * 范围映射沿用 v1。历史复现时，v1 时代的记录仍按 v1 判定，不会被 v2 口径带跑。
 */
public class ConsentRulesV2 implements ConsentRuleSet {

    public static final String VERSION = "consent-rules-v2";

    private final ConsentRulesV1 v1 = new ConsentRulesV1();

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public DecisionResult evaluate(ConsentSnapshot consent, Purpose purpose, Instant now) {
        // v2：过期优先于撤回
        if (consent.isExpired(now)) {
            return new DecisionResult(Decision.DENIED_EXPIRED,
                    "consent " + consent.consentId() + " expired at " + consent.expiresAt(),
                    VERSION, consent);
        }
        if (consent.isRevoked()) {
            return new DecisionResult(Decision.DENIED_REVOKED,
                    "consent " + consent.consentId() + " revoked at " + consent.revokedAt(),
                    VERSION, consent);
        }
        if (!consent.purposes().contains(purpose)) {
            return new DecisionResult(Decision.DENIED_PURPOSE_MISMATCH,
                    "purpose " + purpose + " not covered by consent " + consent.consentId()
                            + " (allowed: " + consent.purposes() + ")",
                    VERSION, consent);
        }
        if (consent.isFullScope()) {
            return new DecisionResult(Decision.ALLOWED,
                    "consent " + consent.consentId() + " covers purpose " + purpose
                            + " with full scope", VERSION, consent);
        }
        return new DecisionResult(Decision.PARTIAL_ALLOWED,
                "consent " + consent.consentId() + " covers purpose " + purpose
                        + " with scoped access " + consent.scopes(), VERSION, consent);
    }

    @Override
    public Map<String, String> fieldSections() {
        return v1.fieldSections();
    }

    @Override
    public List<String> deniedSections(ConsentSnapshot consent, Map<String, Object> record) {
        return v1.deniedSections(consent, record);
    }

    @Override
    public List<String> presentSections(Map<String, Object> record) {
        return v1.presentSections(record);
    }

    @Override
    public String partialReason(ConsentSnapshot consent, Purpose purpose,
                                List<String> deniedSections) {
        return v1.partialReason(consent, purpose, deniedSections);
    }
}
