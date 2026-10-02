package com.github.feng111627.healthcareconsentgatewayjava.consent;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则版本注册表：新版本只增不改（发布后不可覆盖），历史版本永久可取，
 * 保证任意历史审计记录都能按当时版本重放出一致结论。
 */
@Component
public class ConsentRuleRegistry {

    private final Map<String, ConsentRule> rules = new ConcurrentHashMap<>();

    public ConsentRuleRegistry() {
        register(new ConsentRuleV1());
        register(new ConsentRuleV2());
    }

    /** 发布新版本。同名版本已存在时拒绝覆盖，防止历史记录被新规则带跑。 */
    public void register(ConsentRule rule) {
        ConsentRule existing = rules.putIfAbsent(rule.version(), rule);
        if (existing != null) {
            throw new IllegalArgumentException("rule version already published: " + rule.version());
        }
    }

    public ConsentRule byVersion(String version) {
        return rules.get(version);
    }

    public Set<String> versions() {
        return Set.copyOf(rules.keySet());
    }
}
