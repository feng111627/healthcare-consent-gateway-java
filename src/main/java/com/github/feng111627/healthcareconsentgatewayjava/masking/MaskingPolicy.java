package com.github.feng111627.healthcareconsentgatewayjava.masking;

import java.util.List;
import java.util.Map;

/**
 * 可配置脱敏策略：字段名 -> 脱敏方式，外加拼接字段定义。
 * 策略整体随版本固化，发布后不可变，历史版本可按版本号取回复现。
 */
public class MaskingPolicy {
    public enum Strategy { FULL, PARTIAL, HASH }

    private final String version;
    private final Map<String, Strategy> fieldStrategies;
    private final List<CompositeField> composites;

    /** 兼容旧用法：无拼接字段。 */
    public MaskingPolicy(String version, Map<String, Strategy> fieldStrategies) {
        this(version, fieldStrategies, List.of());
    }

    public MaskingPolicy(String version, Map<String, Strategy> fieldStrategies,
                         List<CompositeField> composites) {
        this.version = version;
        this.fieldStrategies = fieldStrategies;
        this.composites = composites;
    }

    public String getVersion() { return version; }
    public Map<String, Strategy> getFieldStrategies() { return fieldStrategies; }
    public List<CompositeField> getComposites() { return composites; }
}
