package com.github.feng111627.healthcareconsentgatewayjava.model;

import java.util.List;

/** 审计链完整性校验结果。 */
public record IntegrityReport(
        boolean valid,
        long recordCount,
        List<Long> brokenAtSeq,
        List<String> details
) {
}
