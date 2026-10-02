package com.github.feng111627.healthcareconsentgatewayjava.consent;

import java.time.Instant;
import java.util.Map;

/**
 * 一个版本的授权判定规则。规则按版本注册，发布后不可变，
 * 历史审计记录可按当时版本重放出一致结论。
 */
public interface ConsentRule {

    /** 规则版本号，与审计记录中的 ruleVersion 对应。 */
    String version();

    /** 按本版本规则判定。consent 为 null 表示无授权记录。 */
    DecisionResult evaluate(Consent consent, Purpose purpose, Instant now);

    /** 本版本规则下的「字段 -> 数据维度(scope)」映射；空表示不做范围过滤。 */
    Map<String, String> fieldScopes();
}
