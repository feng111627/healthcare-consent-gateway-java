package com.github.feng111627.healthcareconsentgatewayjava.consent;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 某一版本的授权判定规则：既决定拒绝/允许的判定口径（如撤回与过期的优先级），
 * 也维护「记录字段 -> 数据区段」的范围映射，供部分授权与历史复现使用。
 */
public interface ConsentRuleSet {

    String version();

    DecisionResult evaluate(ConsentSnapshot consent, Purpose purpose, Instant now);

    /** 顶层记录字段 -> 数据区段（如 IDENTITY/CONTACT/MEDICAL）。 */
    Map<String, String> fieldSections();

    /** 根据授权范围计算本次记录被拒绝的数据区段；全集授权返回空列表。 */
    List<String> deniedSections(ConsentSnapshot consent, Map<String, Object> record);

    /** 记录中实际出现（非空）的受限区段；空集合字段不计入（如没有任何就诊记录）。 */
    List<String> presentSections(Map<String, Object> record);

    /** 部分可见时统一的人类可读原因口径（访问与历史重放共用，保证可复现）。 */
    String partialReason(ConsentSnapshot consent, Purpose purpose, List<String> deniedSections);
}
