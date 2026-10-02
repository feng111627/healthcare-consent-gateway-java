package com.github.feng111627.healthcareconsentgatewayjava.consent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 授权判定器：结合请求方用途与授权范围判定。
 * 判定口径来自 {@link ConsentRuleSet} 的当前生效版本（注册表保留全部历史版本），
 * 拒绝原因明确区分：无授权 / 已撤回 / 已过期 / 用途超出范围 / 数据范围无交集。
 */
@Component
public class ConsentEvaluator {

    private final ConsentRepository repository;
    private final ConsentRuleRegistry ruleRegistry;
    private final String fixedRuleVersion;

    @Autowired
    public ConsentEvaluator(ConsentRepository repository, ConsentRuleRegistry ruleRegistry) {
        this.repository = repository;
        this.ruleRegistry = ruleRegistry;
        this.fixedRuleVersion = null;
    }

    /** 测试用法：不使用注册表，固定按某版本规则判定（默认 v1）。 */
    public ConsentEvaluator(ConsentRepository repository, String fixedRuleVersion) {
        this.repository = repository;
        this.ruleRegistry = null;
        this.fixedRuleVersion = fixedRuleVersion;
    }

    private ConsentRuleSet ruleSet(String explicitVersion) {
        if (explicitVersion != null) {
            if (ruleRegistry == null) {
                throw new IllegalStateException(
                        "evaluator created without registry cannot replay other versions");
            }
            ConsentRuleSet rule = ruleRegistry.get(explicitVersion);
            if (rule == null) {
                throw new IllegalStateException("rule version not registered: " + explicitVersion);
            }
            return rule;
        }
        if (ruleRegistry != null) {
            return ruleRegistry.active();
        }
        return fixedRuleVersion == null || ConsentRulesV1.VERSION.equals(fixedRuleVersion)
                ? new ConsentRulesV1()
                : new ConsentRulesV2();
    }

    public DecisionResult evaluate(String patientId, String requesterId, Purpose purpose,
                                   Instant now) {
        return evaluate(patientId, requesterId, purpose, now, null);
    }

    /**
     * 按指定规则版本判定（历史复现时传审计记录中的 ruleVersion）。
     * explicitVersion 为 null 时按当前生效版本。
     */
    public DecisionResult evaluate(String patientId, String requesterId, Purpose purpose,
                                   Instant now, String explicitVersion) {
        ConsentRuleSet rule = ruleSet(explicitVersion);
        var consentOpt = repository.findByPatientAndRequester(patientId, requesterId);
        if (consentOpt.isEmpty()) {
            return new DecisionResult(Decision.DENIED_NO_CONSENT,
                    "no consent found for patient=" + patientId + ", requester=" + requesterId,
                    rule.version());
        }
        return rule.evaluate(ConsentSnapshot.from(consentOpt.get()), purpose, now);
    }
}
