package com.github.feng111627.healthcareconsentgatewayjava.policy;

import com.github.feng111627.healthcareconsentgatewayjava.model.MaskingRuleSet;

public interface PolicyService {
    /** 当前生效策略。 */
    AccessPolicy current();

    /** 按版本取历史策略；不存在抛 IllegalArgumentException。 */
    AccessPolicy version(String version);

    /** 发布新版本（历史版本全部保留），并自动成为当前版本。 */
    AccessPolicy publish(String version, MaskingRuleSet ruleSet);

    /** 把当前生效版本切回一个已经发布过的历史版本（不删除任何版本）。 */
    AccessPolicy activate(String version);
}
