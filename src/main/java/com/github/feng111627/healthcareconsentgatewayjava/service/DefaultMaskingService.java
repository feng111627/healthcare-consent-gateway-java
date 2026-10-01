package com.github.feng111627.healthcareconsentgatewayjava.service;

import com.github.feng111627.healthcareconsentgatewayjava.model.CompositeFieldRule;
import com.github.feng111627.healthcareconsentgatewayjava.model.MaskingRuleSet;
import com.github.feng111627.healthcareconsentgatewayjava.model.MaskStrategy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 脱敏实现：
 * - 递归遍历 Map / List / 数组，覆盖任意嵌套结构；
 * - 规则 key 优先按点号全路径匹配，其次按叶子字段名匹配；
 * - 拼接字段（如 fullName = first + last）用各成分脱敏值重组；
 * - 任何处理异常统一包装成 MaskingException，由上层兜底，禁止原文外泄。
 */
@Service
public class DefaultMaskingService implements MaskingService {

    private static final String REDACTED = "[REDACTED]";

    @Override
    public Map<String, Object> mask(Map<String, Object> raw, MaskingRuleSet ruleSet) {
        if (raw == null) {
            return null;
        }
        if (ruleSet == null) {
            throw new MaskingException("masking rule set must not be null");
        }
        try {
            Map<String, Object> masked = new LinkedHashMap<>();
            maskMap(raw, masked, "", ruleSet);
            applyCompositeRules(raw, masked, ruleSet);
            return masked;
        } catch (MaskingException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MaskingException("masking failed: " + e.getMessage(), e);
        }
    }

    private void maskMap(Map<String, Object> source, Map<String, Object> target,
                         String path, MaskingRuleSet ruleSet) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            String childPath = path.isEmpty() ? key : path + "." + key;
            Object value = entry.getValue();
            target.put(key, maskValue(value, childPath, key, ruleSet));
        }
    }

    private Object maskValue(Object value, String fullPath, String leafName,
                             MaskingRuleSet ruleSet) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> source = (Map<String, Object>) map;
            Map<String, Object> child = new LinkedHashMap<>();
            maskMap(source, child, fullPath, ruleSet);
            return child;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(maskValue(item, fullPath, leafName, ruleSet));
            }
            return out;
        }

        MaskStrategy strategy = resolveStrategy(fullPath, leafName, ruleSet);
        if (strategy == null || value == null) {
            return value;
        }
        if (!(value instanceof String s)) {
            throw new MaskingException(
                    "field '" + fullPath + "' expects a string value but got "
                            + value.getClass().getSimpleName());
        }
        return applyStrategy(s, strategy, fullPath);
    }

    private MaskStrategy resolveStrategy(String fullPath, String leafName,
                                         MaskingRuleSet ruleSet) {
        Map<String, MaskStrategy> rules = ruleSet.fieldRules();
        if (rules == null) {
            return null;
        }
        MaskStrategy byPath = rules.get(fullPath);
        return byPath != null ? byPath : rules.get(leafName);
    }

    private String applyStrategy(String value, MaskStrategy strategy, String path) {
        return switch (strategy) {
            case REDACT -> REDACTED;
            case PARTIAL -> partial(value);
            case EMAIL -> email(value);
            case PHONE -> phone(value);
        };
    }

    /** 保留首尾各 1 位；长度不足 3 位整体打码。 */
    public static String partial(String value) {
        int n = value.length();
        if (n == 0) {
            return value;
        }
        if (n <= 2) {
            return "*".repeat(n);
        }
        return value.charAt(0) + "*".repeat(n - 2) + value.charAt(n - 1);
    }

    /** 邮箱：本地部分保留首字符，其余打码；非邮箱格式退化为 PARTIAL。 */
    public static String email(String value) {
        int at = value.indexOf('@');
        if (at <= 0) {
            return partial(value);
        }
        String local = value.substring(0, at);
        String domain = value.substring(at);
        if (local.length() == 1) {
            return "*" + domain;
        }
        return local.charAt(0) + "***" + domain;
    }

    /** 电话：保留前导 + 与号/国家码和最后 4 位，中间打码；长度不足整体打码。 */
    public static String phone(String value) {
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.length() < 4) {
            return REDACTED;
        }
        String prefix = value.startsWith("+") ? "+" : "";
        String tail = digits.substring(digits.length() - 4);
        return prefix + "***" + tail;
    }

    /**
     * 拼接字段重组：从原始数据按点号路径取成分值，逐个脱敏后填入模板。
     * 模板使用 {0},{1}... 占位；任一成分缺失或类型错误都直接失败，不回退原文。
     */
    private void applyCompositeRules(Map<String, Object> raw, Map<String, Object> masked,
                                     MaskingRuleSet ruleSet) {
        if (ruleSet.compositeRules() == null) {
            return;
        }
        for (CompositeFieldRule rule : ruleSet.compositeRules()) {
            List<String> parts = rule.componentFields();
            Object[] maskedParts = new Object[parts.size()];
            for (int i = 0; i < parts.size(); i++) {
                String field = parts.get(i);
                Object original = readPath(raw, field);
                if (!(original instanceof String s)) {
                    throw new MaskingException(
                            "composite field '" + rule.outputField()
                                    + "' component '" + field + "' is missing or not a string");
                }
                String leaf = field.contains(".")
                        ? field.substring(field.lastIndexOf('.') + 1) : field;
                maskedParts[i] = applyStrategy(s, rule.strategy(), field);
            }
            String rendered = renderTemplate(rule.template(), maskedParts);
            writePath(masked, rule.outputField(), rendered);
        }
    }

    private static String renderTemplate(String template, Object[] parts) {
        String out = template;
        for (int i = 0; i < parts.length; i++) {
            out = out.replace("{" + i + "}", String.valueOf(parts[i]));
        }
        return out;
    }

    private static Object readPath(Map<String, Object> root, String path) {
        Object current = root;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static void writePath(Map<String, Object> root, String path, Object value) {
        String[] segments = path.split("\\.");
        Map<String, Object> current = root;
        for (int i = 0; i < segments.length - 1; i++) {
            Object next = current.get(segments[i]);
            if (!(next instanceof Map<?, ?>)) {
                throw new MaskingException(
                        "cannot write composite field at '" + path + "': missing parent map");
            }
            current = (Map<String, Object>) next;
        }
        current.put(segments[segments.length - 1], value);
    }
}
