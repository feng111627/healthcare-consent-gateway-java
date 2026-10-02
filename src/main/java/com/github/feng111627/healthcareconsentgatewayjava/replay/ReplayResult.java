package com.github.feng111627.healthcareconsentgatewayjava.replay;

import java.util.List;
import java.util.Map;

/** 一次历史重放的结果：重算结论、重算数据，以及与原审计记录的比对情况。 */
public record ReplayResult(
        long sequence,
        String decision,
        String reason,
        String ruleVersion,
        String maskingPolicyVersion,
        List<String> deniedSections,
        Map<String, Object> data,
        String resultHash,
        boolean decisionMatches,
        boolean resultMatches,
        String mismatchDetail) {
}
