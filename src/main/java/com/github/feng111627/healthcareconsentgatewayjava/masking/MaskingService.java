package com.github.feng111627.healthcareconsentgatewayjava.masking;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按可配置策略对数据递归脱敏。
 * - 支持嵌套 Map / List，拼接响应中的任何命中字段都不会漏出原文；
 * - 策略来自配置 gateway.masking.fields.<field>=FULL|PARTIAL|HASH；
 * - 历史策略版本保留在注册表中，审计复现时可按版本取回。
 */
@Service
public class MaskingService {

    public static final String MASK = "***";

    private final MaskingPolicy currentPolicy;
    private final Map<String, MaskingPolicy> policyRegistry = new ConcurrentHashMap<>();

    public MaskingService(Environment env) {
        String version = env.getProperty("gateway.masking.version", "masking-v1");
        Map<String, MaskingPolicy.Strategy> fields = new LinkedHashMap<>();
        for (String field : List.of("name", "idNumber", "phone", "email", "address")) {
            String s = env.getProperty("gateway.masking.fields." + field, "PARTIAL");
            fields.put(field, MaskingPolicy.Strategy.valueOf(s.toUpperCase()));
        }
        this.currentPolicy = new MaskingPolicy(version, fields);
        // 保留一个历史版本，模拟策略演进后仍能复现旧判定
        this.policyRegistry.put("masking-v0", new MaskingPolicy("masking-v0",
                Map.of("name", MaskingPolicy.Strategy.FULL,
                        "idNumber", MaskingPolicy.Strategy.FULL,
                        "phone", MaskingPolicy.Strategy.FULL,
                        "email", MaskingPolicy.Strategy.FULL,
                        "address", MaskingPolicy.Strategy.FULL)));
        this.policyRegistry.put(version, currentPolicy);
    }

    public MaskingPolicy currentPolicy() {
        return currentPolicy;
    }

    public MaskingPolicy policyByVersion(String version) {
        return policyRegistry.get(version);
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
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                out.put(key, maskNode(e.getValue(), policy, key));
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
