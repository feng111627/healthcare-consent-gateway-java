package com.github.feng111627.healthcareconsentgatewayjava.domain;

import java.time.Instant;
import java.util.List;

/**
 * 判定依据的不可变快照。落进审计记录后，即使后续规则/策略变更，
 * 仍可凭此快照复现当时为什么这样判定。
 *
 * @param consentId         命中的授权 ID；无授权时为 null
 * @param consentVersion    授权版本标识（授权内容的快照标签）
 * @param revoked           判定时刻授权是否已撤回
 * @param revokedAt         撤回时间（未撤回为 null）
 * @param consentExpiresAt  授权过期时间（null 表示长期有效）
 * @param allowedPurposes   授权允许的用途范围快照
 * @param requestedPurpose  本次请求声明的用途
 * @param evaluatedAt       判定发生的时刻
 * @param policyVersion     判定时使用的访问策略版本
 */
public record DecisionBasis(
        String consentId,
        String consentVersion,
        boolean revoked,
        Instant revokedAt,
        Instant consentExpiresAt,
        List<Purpose> allowedPurposes,
        Purpose requestedPurpose,
        Instant evaluatedAt,
        String policyVersion
) {
    /** 无授权时的依据快照：只保留请求用途、判定时刻与策略版本。 */
    public static DecisionBasis empty(Purpose requestedPurpose, Instant evaluatedAt,
                                      String policyVersion) {
        return new DecisionBasis(null, null, false, null, null,
                List.of(), requestedPurpose, evaluatedAt, policyVersion);
    }

    /** 基于某条授权生成判定快照（对允许用途做防御性拷贝）。 */
    public static DecisionBasis of(ConsentGrant grant, Purpose requestedPurpose,
                                   Instant evaluatedAt, String policyVersion) {
        return new DecisionBasis(
                grant.consentId(),
                grant.consentId() + "@" + grant.grantedAt(),
                grant.revoked(),
                grant.revokedAt(),
                grant.expiresAt(),
                List.copyOf(grant.allowedPurposes()),
                requestedPurpose,
                evaluatedAt,
                policyVersion
        );
    }
}
