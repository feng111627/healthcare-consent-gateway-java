package com.github.feng111627.healthcareconsentgatewayjava.consent;

/** 判定结果 + 人类可读原因 + 判定依据（规则版本）+ 当时的授权快照（允许时非空）。 */
public record DecisionResult(Decision decision, String reason, String ruleVersion,
                             ConsentSnapshot consentSnapshot) {

    public DecisionResult(Decision decision, String reason, String ruleVersion) {
        this(decision, reason, ruleVersion, null);
    }

    public boolean allowed() {
        return decision == Decision.ALLOWED || decision == Decision.PARTIAL_ALLOWED;
    }
}
