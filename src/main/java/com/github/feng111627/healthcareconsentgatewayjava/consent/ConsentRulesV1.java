package com.github.feng111627.healthcareconsentgatewayjava.consent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 授权规则 v1：撤回优先于过期；判定优先级 撤回 > 过期 > 用途匹配 > 范围匹配。
 * 字段 -> 数据区段映射在本版本固定，范围判定与映射一起随版本复现。
 */
public class ConsentRulesV1 implements ConsentRuleSet {

    public static final String VERSION = "consent-rules-v1";

    /** 数据区段：身份信息 / 联系方式 / 医疗信息；未登记字段视为 OPEN，始终可见。 */
    public static final String SECTION_IDENTITY = "IDENTITY";
    public static final String SECTION_CONTACT = "CONTACT";
    public static final String SECTION_MEDICAL = "MEDICAL";
    public static final String SECTION_OPEN = "OPEN";

    private static final Map<String, String> FIELD_SECTIONS = Map.ofEntries(
            Map.entry("name", SECTION_IDENTITY),
            Map.entry("firstName", SECTION_IDENTITY),
            Map.entry("lastName", SECTION_IDENTITY),
            Map.entry("fullName", SECTION_IDENTITY),
            Map.entry("idNumber", SECTION_IDENTITY),
            Map.entry("phone", SECTION_CONTACT),
            Map.entry("areaCode", SECTION_CONTACT),
            Map.entry("contactNumber", SECTION_CONTACT),
            Map.entry("contactPhone", SECTION_CONTACT),
            Map.entry("email", SECTION_CONTACT),
            Map.entry("address", SECTION_CONTACT),
            Map.entry("country", SECTION_CONTACT),
            Map.entry("city", SECTION_CONTACT),
            Map.entry("district", SECTION_CONTACT),
            Map.entry("street", SECTION_CONTACT),
            Map.entry("fullAddress", SECTION_CONTACT),
            Map.entry("emergencyContact", SECTION_CONTACT),
            Map.entry("visits", SECTION_MEDICAL));

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public DecisionResult evaluate(ConsentSnapshot consent, Purpose purpose, Instant now) {
        // v1：撤回优先于过期（撤回是患者主动行为，语义更强）
        if (consent.isRevoked()) {
            return new DecisionResult(Decision.DENIED_REVOKED,
                    "consent " + consent.consentId() + " revoked at " + consent.revokedAt(),
                    VERSION, consent);
        }
        if (consent.isExpired(now)) {
            return new DecisionResult(Decision.DENIED_EXPIRED,
                    "consent " + consent.consentId() + " expired at " + consent.expiresAt(),
                    VERSION, consent);
        }
        if (!consent.purposes().contains(purpose)) {
            return new DecisionResult(Decision.DENIED_PURPOSE_MISMATCH,
                    "purpose " + purpose + " not covered by consent " + consent.consentId()
                            + " (allowed: " + consent.purposes() + ")",
                    VERSION, consent);
        }
        if (consent.isFullScope()) {
            return new DecisionResult(Decision.ALLOWED,
                    "consent " + consent.consentId() + " covers purpose " + purpose
                            + " with full scope", VERSION, consent);
        }
        return new DecisionResult(Decision.PARTIAL_ALLOWED,
                "consent " + consent.consentId() + " covers purpose " + purpose
                        + " with scoped access " + consent.scopes(), VERSION, consent);
    }

    @Override
    public Map<String, String> fieldSections() {
        return FIELD_SECTIONS;
    }

    @Override
    public List<String> deniedSections(ConsentSnapshot consent, Map<String, Object> record) {
        if (consent.isFullScope()) {
            return List.of();
        }
        // 本次记录实际涉及、但授权范围里没有的区段；OPEN 区段不限制
        List<String> denied = new ArrayList<>();
        for (String section : presentSections(record)) {
            if (!consent.scopes().contains(section) && !denied.contains(section)) {
                denied.add(section);
            }
        }
        return denied;
    }

    @Override
    public List<String> presentSections(Map<String, Object> record) {
        List<String> present = new ArrayList<>();
        for (Map.Entry<String, Object> e : record.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            // 空集合表示该类数据本次并不存在，不计入区段
            if (e.getValue() instanceof java.util.Collection<?> c && c.isEmpty()) {
                continue;
            }
            String section = FIELD_SECTIONS.get(e.getKey());
            if (section != null && !present.contains(section)) {
                present.add(section);
            }
        }
        return present;
    }

    @Override
    public String partialReason(ConsentSnapshot consent, Purpose purpose,
                                List<String> deniedSections) {
        return "consent " + consent.consentId() + " covers purpose " + purpose
                + " with scoped access " + consent.scopes()
                + "; granted sections returned, denied sections withheld: " + deniedSections;
    }
}
