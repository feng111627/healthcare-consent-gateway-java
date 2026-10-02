package com.github.feng111627.healthcareconsentgatewayjava.consent;

import java.time.Instant;
import java.util.Map;

/**
 * v1 规则：整体允许/拒绝，不做数据维度（scope）过滤。
 * 拒绝原因明确区分：无授权 / 已撤回 / 已过期 / 用途超出范围。
 * 判定优先级：撤回 > 过期 > 用途匹配（撤回是患者主动行为，语义最强）。
 */
public class ConsentRuleV1 implements ConsentRule {

    public static final String VERSION = "consent-rules-v1";

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public DecisionResult evaluate(Consent consent, Purpose purpose, Instant now) {
        if (consent == null) {
            return new DecisionResult(Decision.DENIED_NO_CONSENT,
                    "no consent found", VERSION, null, "NONE");
        }
        String snapshot = consent.toSnapshot();
        // 撤回优先于过期判定：撤回是患者主动行为，语义更强
        if (consent.isRevoked()) {
            return new DecisionResult(Decision.DENIED_REVOKED,
                    "consent " + consent.getConsentId() + " revoked at " + consent.getRevokedAt(),
                    VERSION, null, snapshot);
        }
        if (consent.isExpired(now)) {
            return new DecisionResult(Decision.DENIED_EXPIRED,
                    "consent " + consent.getConsentId() + " expired at " + consent.getExpiresAt(),
                    VERSION, null, snapshot);
        }
        if (!consent.getPurposes().contains(purpose)) {
            return new DecisionResult(Decision.DENIED_PURPOSE_MISMATCH,
                    "purpose " + purpose + " not covered by consent " + consent.getConsentId()
                            + " (allowed: " + consent.getPurposes() + ")",
                    VERSION, null, snapshot);
        }
        // v1 不做范围过滤：grantedScopes = null 表示返回全部数据
        return new DecisionResult(Decision.ALLOWED,
                "consent " + consent.getConsentId() + " covers purpose " + purpose,
                VERSION, null, snapshot);
    }

    @Override
    public Map<String, String> fieldScopes() {
        return Map.of();
    }
}
