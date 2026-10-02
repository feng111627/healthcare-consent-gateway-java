package com.github.feng111627.healthcareconsentgatewayjava.consent;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 授权规则版本注册表：保留全部历史规则版本，当前生效版本可发布切换。
 * 历史复现按审计记录中的 ruleVersion 取回当时规则，新版本发布不影响旧记录复现。
 */
@Component
public class ConsentRuleRegistry {

    private final Map<String, ConsentRuleSet> rules = new LinkedHashMap<>();
    private volatile ConsentRuleSet active;

    public ConsentRuleRegistry() {
        this(new ConsentRulesV1());
    }

    /** Spring 使用：注册 v1/v2，并从配置读取当前生效版本。 */
    @org.springframework.beans.factory.annotation.Autowired
    public ConsentRuleRegistry(Environment env) {
        this();
        String configured = env.getProperty("gateway.consent.rule-version", ConsentRulesV1.VERSION);
        publish(configured);
    }

    private ConsentRuleRegistry(ConsentRuleSet initial) {
        register(new ConsentRulesV1());
        register(new ConsentRulesV2());
        this.active = initial;
    }

    private void register(ConsentRuleSet ruleSet) {
        rules.put(ruleSet.version(), ruleSet);
    }

    public String activeVersion() {
        return active.version();
    }

    public ConsentRuleSet active() {
        return active;
    }

    public ConsentRuleSet get(String version) {
        return rules.get(version);
    }

    /** 发布（切换）当前生效规则版本；目标版本必须已注册，历史版本继续保留。 */
    public void publish(String version) {
        ConsentRuleSet ruleSet = rules.get(version);
        if (ruleSet == null) {
            throw new IllegalArgumentException("unknown consent rule version: " + version);
        }
        this.active = ruleSet;
    }
}
