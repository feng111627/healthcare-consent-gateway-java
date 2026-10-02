package com.github.feng111627.healthcareconsentgatewayjava.masking;

import java.util.List;
import java.util.Map;

/**
 * 可配置脱敏策略：字段名 -> 脱敏方式，外加「拼接字段」定义。
 * 拼接字段与策略版本绑定，历史复现时随版本一起取回，保证当时的拼接口径不变。
 */
public class MaskingPolicy {
    public enum Strategy { FULL, PARTIAL, HASH }

    private final String version;
    private final Map<String, Strategy> fieldStrategies;
    private final List<CompositeField> composites;

    public MaskingPolicy(String version, Map<String, Strategy> fieldStrategies) {
        this(version, fieldStrategies, List.of());
    }

    public MaskingPolicy(String version, Map<String, Strategy> fieldStrategies,
                         List<CompositeField> composites) {
        this.version = version;
        this.fieldStrategies = fieldStrategies;
        this.composites = composites == null ? List.of() : List.copyOf(composites);
    }

    public String getVersion() { return version; }
    public Map<String, Strategy> getFieldStrategies() { return fieldStrategies; }
    public List<CompositeField> getComposites() { return composites; }
}
