package com.github.feng111627.healthcareconsentgatewayjava.consent;

/** 判定结果 + 人类可读原因 + 判定依据（规则版本）。 */
public record DecisionResult(Decision decision, String reason, String ruleVersion) {
    public boolean allowed() {
        return decision == Decision.ALLOWED;
    }
}
