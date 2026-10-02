package com.github.feng111627.healthcareconsentgatewayjava.consent;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * v2 规则：在 v1 的整体判定之上，支持数据维度（scope）级授权。
 * - 授权覆盖全部维度      -> ALLOWED（返回全部）；
 * - 只覆盖部分维度        -> ALLOWED_PARTIAL（授权部分返回，未授权部分扣留）；
 * - 一个维度都没授予      -> DENIED_NO_SCOPE（整体拒绝，与 ALLOWED_PARTIAL 明确区分）。
 * 字段 -> 维度映射随规则版本固化，重放旧记录时不受后续版本调整影响。
 */
public class ConsentRuleV2 implements ConsentRule {

    public static final String VERSION = "consent-rules-v2";

    /** 字段 -> 数据维度映射，随本版本固化。 */
    private static final Map<String, String> FIELD_SCOPES = Map.ofEntries(
            Map.entry("name", "identity"),
            Map.entry("idNumber", "identity"),
            Map.entry("lastName", "identity"),
            Map.entry("firstName", "identity"),
            Map.entry("fullName", "identity"),
            Map.entry("phone", "contact"),
            Map.entry("email", "contact"),
            Map.entry("areaCode", "contact"),
            Map.entry("phoneNumber", "contact"),
            Map.entry("contactPhone", "contact"),
            Map.entry("emergencyContact", "contact"),
            Map.entry("address", "address"),
            Map.entry("province", "address"),
            Map.entry("city", "address"),
            Map.entry("street", "address"),
            Map.entry("fullAddress", "address"),
            Map.entry("visits", "medical"));

    private static final Set<String> KNOWN_SCOPES =
            Set.copyOf(new LinkedHashSet<>(FIELD_SCOPES.values()));

    private final ConsentRuleV1 base = new ConsentRuleV1();

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public DecisionResult evaluate(Consent consent, Purpose purpose, Instant now) {
        // 整体判定（无授权/撤回/过期/用途不符）与 v1 完全一致
        DecisionResult baseResult = base.evaluate(consent, purpose, now);
        if (!baseResult.allowed()) {
            return new DecisionResult(baseResult.decision(), baseResult.reason(),
                    VERSION, null, baseResult.consentSnapshot());
        }
        // 在整体允许的基础上做维度判定
        Set<String> granted = new TreeSet<>(consent.getScopes());
        granted.retainAll(KNOWN_SCOPES);
        String snapshot = consent.toSnapshot();
        if (granted.isEmpty()) {
            return new DecisionResult(Decision.DENIED_NO_SCOPE,
                    "consent " + consent.getConsentId() + " grants no data scope (granted: "
                            + consent.getScopes() + ", known: " + KNOWN_SCOPES + ")",
                    VERSION, Set.of(), snapshot);
        }
        if (granted.containsAll(KNOWN_SCOPES)) {
            return new DecisionResult(Decision.ALLOWED,
                    "consent " + consent.getConsentId() + " covers purpose " + purpose
                            + " and all data scopes",
                    VERSION, granted, snapshot);
        }
        return new DecisionResult(Decision.ALLOWED_PARTIAL,
                "consent " + consent.getConsentId() + " covers purpose " + purpose
                        + " with partial scopes " + granted + " (withheld: " + withheld(granted) + ")",
                VERSION, granted, snapshot);
    }

    private Set<String> withheld(Set<String> granted) {
        Set<String> withheld = new TreeSet<>(KNOWN_SCOPES);
        withheld.removeAll(granted);
        return withheld;
    }

    @Override
    public Map<String, String> fieldScopes() {
        return FIELD_SCOPES;
    }
}
