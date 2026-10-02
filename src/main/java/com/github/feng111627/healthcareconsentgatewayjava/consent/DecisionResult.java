package com.github.feng111627.healthcareconsentgatewayjava.consent;

import java.util.Set;

/**
 * 判定结果 + 人类可读原因 + 判定依据（规则版本）。
 *
 * @param grantedScopes   本次被授予的数据维度；null 表示规则不做范围过滤（返回全部），
 *                        非 null（含空集）表示按该集合过滤
 * @param consentSnapshot 判定所依据的授权快照（规范化字符串），无授权时为 "NONE"，
 *                        写入审计后可在重放时还原判定输入
 */
public record DecisionResult(Decision decision, String reason, String ruleVersion,
                             Set<String> grantedScopes, String consentSnapshot) {

    /** 兼容旧用法：无范围过滤、无快照。 */
    public DecisionResult(Decision decision, String reason, String ruleVersion) {
        this(decision, reason, ruleVersion, null, "NONE");
    }

    public boolean allowed() {
        return decision == Decision.ALLOWED || decision == Decision.ALLOWED_PARTIAL;
    }
}
