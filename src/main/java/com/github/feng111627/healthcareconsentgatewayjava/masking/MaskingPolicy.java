package com.github.feng111627.healthcareconsentgatewayjava.masking;

import java.util.Map;

/** 可配置脱敏策略：字段名 -> 脱敏方式。 */
public class MaskingPolicy {
    public enum Strategy { FULL, PARTIAL, HASH }

    private final String version;
    private final Map<String, Strategy> fieldStrategies;

    public MaskingPolicy(String version, Map<String, Strategy> fieldStrategies) {
        this.version = version;
        this.fieldStrategies = fieldStrategies;
    }

    public String getVersion() { return version; }
    public Map<String, Strategy> getFieldStrategies() { return fieldStrategies; }
}
