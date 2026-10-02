package com.github.feng111627.healthcareconsentgatewayjava.consent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 授权判定器：按当前配置的规则版本委派给对应的 ConsentRule。
 * 历史版本规则保留在注册表中，审计重放时可按版本取回，不受当前版本影响。
 */
@Component
public class ConsentEvaluator {

    private final ConsentRepository repository;
    private final ConsentRuleRegistry ruleRegistry;
    private final String ruleVersion;

    /** 兼容旧用法：自带默认注册表（含 v1/v2）。 */
    public ConsentEvaluator(ConsentRepository repository,
                            String ruleVersion) {
        this(repository, new ConsentRuleRegistry(), ruleVersion);
    }

    @Autowired
    public ConsentEvaluator(ConsentRepository repository, ConsentRuleRegistry ruleRegistry,
                            @Value("${gateway.consent.rule-version:consent-rules-v1}") String ruleVersion) {
        this.repository = repository;
        this.ruleRegistry = ruleRegistry;
        this.ruleVersion = ruleVersion;
    }

    public DecisionResult evaluate(String patientId, String requesterId, Purpose purpose, Instant now) {
        Consent consent = repository.findByPatientAndRequester(patientId, requesterId).orElse(null);
        return currentRule().evaluate(consent, purpose, now);
    }

    /** 当前生效的规则（含其字段维度映射）。 */
    public ConsentRule currentRule() {
        ConsentRule rule = ruleRegistry.byVersion(ruleVersion);
        if (rule == null) {
            throw new IllegalStateException("unknown consent rule version: " + ruleVersion);
        }
        return rule;
    }

    public String ruleVersion() {
        return ruleVersion;
    }
}
