package com.github.feng111627.healthcareconsentgatewayjava.masking;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按可配置策略对数据递归脱敏。
 * - 支持嵌套 Map / List，拼接（toString/序列化）后的响应里不会漏出任何命中字段的原文；
 * - 支持「拼接字段」：各成分先按自己的策略分别脱敏，再按分隔符拼成输出字段，
 *   成分原文一律不保留（顶层、嵌套对象、列表中的 Map 同样处理）；
 * - 策略来自配置 gateway.masking.fields.<field>=FULL|PARTIAL|HASH；
 * - 历史策略版本保留在注册表中，发布新版本只切换 current，旧版本仍可按版本取回复现。
 */
@Service
public class MaskingService {

    public static final String MASK = "***";

    private volatile MaskingPolicy currentPolicy;
    private final Map<String, MaskingPolicy> policyRegistry = new ConcurrentHashMap<>();

    public MaskingService(Environment env) {
        String version = env.getProperty("gateway.masking.version", "masking-v1");

        // 内置历史版本 v0：全部全打码（含拼接成分字段），验证策略演进后仍能复现
        Map<String, MaskingPolicy.Strategy> v0Strategies = new LinkedHashMap<>();
        for (String f : List.of("name", "idNumber", "phone", "email", "address",
                "firstName", "lastName", "areaCode", "contactNumber",
                "country", "city", "district", "street")) {
            v0Strategies.put(f, MaskingPolicy.Strategy.FULL);
        }
        policyRegistry.put("masking-v0",
                new MaskingPolicy("masking-v0", v0Strategies, defaultComposites()));

        // 当前版本 v1：字段策略可配置，默认值与 application.properties 保持一致
        Map<String, String> defaults = Map.ofEntries(
                Map.entry("name", "PARTIAL"),
                Map.entry("idNumber", "HASH"),
                Map.entry("phone", "PARTIAL"),
                Map.entry("email", "PARTIAL"),
                Map.entry("address", "FULL"),
                Map.entry("firstName", "PARTIAL"),
                Map.entry("lastName", "PARTIAL"),
                Map.entry("areaCode", "HASH"),
                Map.entry("contactNumber", "PARTIAL"),
                Map.entry("country", "PARTIAL"),
                Map.entry("city", "PARTIAL"),
                Map.entry("district", "PARTIAL"),
                Map.entry("street", "PARTIAL"));
        Map<String, MaskingPolicy.Strategy> fields = new LinkedHashMap<>();
        for (String field : defaults.keySet()) {
            String s = env.getProperty("gateway.masking.fields." + field,
                    defaults.get(field));
            fields.put(field, MaskingPolicy.Strategy.valueOf(s.toUpperCase()));
        }
        MaskingPolicy v1 = new MaskingPolicy(version, fields, defaultComposites());
        this.currentPolicy = v1;
        policyRegistry.put(version, v1);

        // 预置新版本 v2 的策略产物（模拟「已构建、择机发布」）：
        // email/address 改为全打码、contactNumber 改 HASH、city 全打码
        Map<String, MaskingPolicy.Strategy> v2Strategies = new LinkedHashMap<>(fields);
        v2Strategies.put("email", MaskingPolicy.Strategy.FULL);
        v2Strategies.put("address", MaskingPolicy.Strategy.PARTIAL);
        v2Strategies.put("contactNumber", MaskingPolicy.Strategy.HASH);
        v2Strategies.put("city", MaskingPolicy.Strategy.FULL);
        policyRegistry.put("masking-v2",
                new MaskingPolicy("masking-v2", v2Strategies, defaultComposites()));
    }

    /** 拼接字段定义随脱敏策略版本走：不同版本可演进出不同拼接口径。 */
    private static List<CompositeField> defaultComposites() {
        return List.of(
                new CompositeField("fullName", List.of("firstName", "lastName"), " "),
                new CompositeField("contactPhone", List.of("areaCode", "contactNumber"), "-"),
                new CompositeField("fullAddress",
                        List.of("country", "city", "district", "street"), ""));
    }

    public MaskingPolicy currentPolicy() {
        return currentPolicy;
    }

    public MaskingPolicy policyByVersion(String version) {
        return policyRegistry.get(version);
    }

    /** 发布（切换）当前生效策略版本；目标版本必须已在注册表中，旧版本继续保留可复现。 */
    public void publish(String version) {
        MaskingPolicy policy = policyRegistry.get(version);
        if (policy == null) {
            throw new IllegalArgumentException("unknown masking policy version: " + version);
        }
        this.currentPolicy = policy;
    }

    /** 对嵌套结构递归脱敏，返回新对象，不修改入参。 */
    public Map<String, Object> mask(Map<String, Object> data, MaskingPolicy policy) {
        if (data == null || policy == null) {
            throw new MaskingException("masking input or policy is null", null);
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) maskNode(data, policy, null);
            return result;
        } catch (MaskingException e) {
            throw e;
        } catch (Exception e) {
            // 处理出错时抛出异常，绝不返回部分脱敏/原始数据
            throw new MaskingException("masking failed", e);
        }
    }

    private Object maskNode(Object node, MaskingPolicy policy, String fieldName) {
        if (node instanceof Map<?, ?> map) {
            // 1. 找出该 Map 上「成分齐全」的拼接字段，登记将被消费的成分键
            java.util.Set<String> consumed = new java.util.HashSet<>();
            List<CompositeField> activeComposites = new ArrayList<>();
            for (CompositeField composite : policy.getComposites()) {
                if (map.keySet().containsAll(composite.componentFields())) {
                    activeComposites.add(composite);
                    consumed.addAll(composite.componentFields());
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            // 2. 普通字段照常递归脱敏；拼接成分键不再单独输出，避免原文从成分键漏出
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (consumed.contains(key)) {
                    continue;
                }
                out.put(key, maskNode(e.getValue(), policy, key));
            }
            // 3. 拼接字段：各成分按各自策略分别脱敏后再组合（绝不先拼原文再处理）
            for (CompositeField composite : activeComposites) {
                out.put(composite.outputName(), applyComposite(map, composite, policy));
            }
            return out;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(maskNode(item, policy, fieldName));
            }
            return out;
        }
        if (fieldName != null && node != null) {
            MaskingPolicy.Strategy strategy = policy.getFieldStrategies().get(fieldName);
            if (strategy != null) {
                return applyStrategy(String.valueOf(node), strategy);
            }
        }
        return node;
    }

    /**
     * 处理一个拼接字段：每个成分按自己字段名对应的策略脱敏，再用分隔符拼接。
     * 任何成分缺失策略（意味着可能漏原文）都 fail-close 抛 {@link MaskingException}。
     */
    private String applyComposite(Map<?, ?> rawMap, CompositeField composite, MaskingPolicy policy) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < composite.componentFields().size(); i++) {
            if (i > 0) {
                sb.append(composite.delimiter());
            }
            String componentName = composite.componentFields().get(i);
            Object raw = rawMap.get(componentName);
            MaskingPolicy.Strategy strategy = policy.getFieldStrategies().get(componentName);
            // 成分没有对应策略则拒绝处理，避免把原文拼进输出
            if (strategy == null) {
                throw new MaskingException(
                        "no masking strategy for composite component: " + componentName, null);
            }
            if (raw != null && !(raw instanceof String || raw instanceof Number
                    || raw instanceof Boolean || raw instanceof Character)) {
                // 成分必须是标量；复杂结构不能当拼接成分，防止结构化原文被拼入
                throw new MaskingException(
                        "composite component must be scalar: " + componentName, null);
            }
            sb.append(raw == null ? MASK : applyStrategy(String.valueOf(raw), strategy));
        }
        return sb.toString();
    }

    private String applyStrategy(String value, MaskingPolicy.Strategy strategy) {
        return switch (strategy) {
            case FULL -> MASK;
            case PARTIAL -> partial(value);
            case HASH -> "sha256:" + sha256Hex(value).substring(0, 12);
        };
    }

    /** 保留首尾字符、中间打码；单字符全打码，双字符保留首字符。 */
    private String partial(String value) {
        if (value.length() <= 1) {
            return MASK;
        }
        if (value.length() == 2) {
            return value.charAt(0) + "*";
        }
        return value.charAt(0) + MASK + value.charAt(value.length() - 1);
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new MaskingException("SHA-256 unavailable", e);
        }
    }
}
