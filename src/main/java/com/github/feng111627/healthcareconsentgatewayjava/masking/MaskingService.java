package com.github.feng111627.healthcareconsentgatewayjava.masking;

import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按可配置策略对数据递归脱敏。
 * - 支持嵌套 Map / List，拼接响应中的任何命中字段都不会漏出原文；
 * - 拼接字段（composite）：成分字段先各自按策略脱敏，脱敏结果再组合，
 *   任何一层（顶层/嵌套对象/列表元素）的拼接输出都不含成分原文；
 * - 策略来自配置 gateway.masking.fields.<field>=FULL|PARTIAL|HASH，
 *   拼接字段来自 gateway.masking.composites.<output>=c1,c2（可选 .separator）；
 * - 历史策略版本保留在注册表中，新版本只增不改，审计复现时可按版本取回。
 */
@Service
public class MaskingService {

    public static final String MASK = "***";

    private static final String COMPOSITE_PREFIX = "gateway.masking.composites.";
    private static final String SEPARATOR_SUFFIX = ".separator";

    private final MaskingPolicy currentPolicy;
    private final Map<String, MaskingPolicy> policyRegistry = new ConcurrentHashMap<>();

    public MaskingService(Environment env) {
        String version = env.getProperty("gateway.masking.version", "masking-v1");
        Map<String, MaskingPolicy.Strategy> fields = new LinkedHashMap<>();
        for (String field : List.of("name", "idNumber", "phone", "email", "address",
                "lastName", "firstName", "areaCode", "phoneNumber",
                "province", "city", "street")) {
            String s = env.getProperty("gateway.masking.fields." + field, "PARTIAL");
            fields.put(field, MaskingPolicy.Strategy.valueOf(s.toUpperCase()));
        }
        this.currentPolicy = new MaskingPolicy(version, fields, loadComposites(env));
        // 保留一个历史版本，模拟策略演进后仍能复现旧判定
        this.policyRegistry.put("masking-v0", new MaskingPolicy("masking-v0",
                Map.of("name", MaskingPolicy.Strategy.FULL,
                        "idNumber", MaskingPolicy.Strategy.FULL,
                        "phone", MaskingPolicy.Strategy.FULL,
                        "email", MaskingPolicy.Strategy.FULL,
                        "address", MaskingPolicy.Strategy.FULL)));
        this.policyRegistry.put(version, currentPolicy);
    }

    /** 从环境配置解析拼接字段定义（可枚举的属性源，兼容 MockEnvironment 与 properties 文件）。 */
    private static List<CompositeField> loadComposites(Environment env) {
        Map<String, List<String>> components = new LinkedHashMap<>();
        Map<String, String> separators = new LinkedHashMap<>();
        if (env instanceof AbstractEnvironment absEnv) {
            for (PropertySource<?> ps : absEnv.getPropertySources()) {
                if (ps instanceof EnumerablePropertySource<?> eps) {
                    for (String name : eps.getPropertyNames()) {
                        if (!name.startsWith(COMPOSITE_PREFIX)) {
                            continue;
                        }
                        String rest = name.substring(COMPOSITE_PREFIX.length());
                        if (rest.endsWith(SEPARATOR_SUFFIX)) {
                            separators.put(rest.substring(0, rest.length() - SEPARATOR_SUFFIX.length()),
                                    eps.getProperty(name).toString());
                        } else {
                            components.put(rest, Arrays.asList(
                                    eps.getProperty(name).toString().split(",")));
                        }
                    }
                }
            }
        }
        List<CompositeField> composites = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : components.entrySet()) {
            composites.add(new CompositeField(e.getKey(), e.getValue(),
                    separators.getOrDefault(e.getKey(), "")));
        }
        return composites;
    }

    public MaskingPolicy currentPolicy() {
        return currentPolicy;
    }

    public MaskingPolicy policyByVersion(String version) {
        return policyRegistry.get(version);
    }

    /** 发布新策略版本。同名版本已存在时拒绝覆盖，保证历史记录可复现。 */
    public void publishPolicy(MaskingPolicy policy) {
        MaskingPolicy existing = policyRegistry.putIfAbsent(policy.getVersion(), policy);
        if (existing != null) {
            throw new IllegalArgumentException("masking policy version already published: "
                    + policy.getVersion());
        }
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
            // 成分先各自脱敏（上面的递归），脱敏结果再组合成拼接字段
            applyComposites(out, policy);
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
     * 在当前（已脱敏的）Map 节点上计算拼接字段：
     * 所有成分都在场时才生成，成分缺位则跳过，绝不输出未脱敏的组合。
     */
    private void applyComposites(Map<String, Object> maskedNode, MaskingPolicy policy) {
        for (CompositeField composite : policy.getComposites()) {
            boolean allPresent = composite.components().stream()
                    .allMatch(c -> maskedNode.get(c) != null);
            if (!allPresent) {
                continue;
            }
            StringBuilder composed = new StringBuilder();
            for (int i = 0; i < composite.components().size(); i++) {
                if (i > 0) {
                    composed.append(composite.separator());
                }
                composed.append(maskedNode.get(composite.components().get(i)));
            }
            maskedNode.put(composite.outputField(), composed.toString());
        }
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
