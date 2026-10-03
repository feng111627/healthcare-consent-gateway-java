package com.github.feng111627.healthcareconsentgatewayjava.tests;

import com.github.feng111627.healthcareconsentgatewayjava.masking.CompositeField;
import com.github.feng111627.healthcareconsentgatewayjava.masking.MaskingPolicy;
import com.github.feng111627.healthcareconsentgatewayjava.masking.MaskingService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拼接字段的失败安全：任一成分处理不了（未配置脱敏策略、或成分值不是简单文本）时，
 * 该拼接字段整体不输出，且成分原文绝不出现在结果里——
 * 顶层字段、嵌套对象、列表元素三个层级同样严格。
 */
class CompositeStrictnessTest {

    private static final Logger log = LoggerFactory.getLogger(CompositeStrictnessTest.class);

    private final MaskingService maskingService = new MaskingService(new MockEnvironment());

    /**
     * 策略中故意不给 firstName 配规则：fullName 的一个成分处理不了。
     * （直接构造策略对象，模拟「该成分没有对应处理规则」的策略版本。）
     */
    private MaskingPolicy policyWithoutFirstNameRule() {
        return new MaskingPolicy("masking-strict-test",
                Map.of("lastName", MaskingPolicy.Strategy.PARTIAL),
                List.of(new CompositeField("fullName", List.of("lastName", "firstName"), "")));
    }

    /** 成分都配了规则，但 firstName 的值可能不是简单文本。 */
    private MaskingPolicy policyWithBothRules() {
        return new MaskingPolicy("masking-strict-test-2",
                Map.of("lastName", MaskingPolicy.Strategy.PARTIAL,
                        "firstName", MaskingPolicy.Strategy.FULL),
                List.of(new CompositeField("fullName", List.of("lastName", "firstName"), "")));
    }

    private void logCase(String scene, Map<String, Object> input, MaskingPolicy policy,
                         Map<String, Object> masked) {
        log.info("输入({}): {}", scene, input);
        log.info("判定依据: composites={}, 已配置策略的字段={}",
                policy.getComposites(), policy.getFieldStrategies().keySet());
        log.info("输出({}): {}", scene, masked);
    }

    @Test
    void topLevelCompositeDroppedWhenComponentHasNoStrategy() {
        MaskingPolicy policy = policyWithoutFirstNameRule();
        Map<String, Object> input = Map.of(
                "patientId", "P200",
                "lastName", "欧阳",
                "firstName", "娜娜"); // firstName 无策略
        Map<String, Object> masked = maskingService.mask(input, policy);
        logCase("顶层-成分缺规则", input, policy, masked);

        // 拼接字段整体不输出，成分原文不会被拼进组合值带出去
        assertFalse(masked.containsKey("fullName"), "成分缺规则时拼接字段不得输出: " + masked);
        assertFalse(String.valueOf(masked.getOrDefault("fullName", "")).contains("欧阳"));
    }

    @Test
    void nestedObjectCompositeDroppedWhenComponentHasNoStrategy() {
        MaskingPolicy policy = policyWithoutFirstNameRule();
        Map<String, Object> input = Map.of(
                "patientId", "P200",
                "emergencyContact", Map.of(
                        "lastName", "张",
                        "firstName", "伟")); // 嵌套对象内 firstName 无策略
        Map<String, Object> masked = maskingService.mask(input, policy);
        logCase("嵌套对象-成分缺规则", input, policy, masked);

        @SuppressWarnings("unchecked")
        Map<String, Object> contact = (Map<String, Object>) masked.get("emergencyContact");
        assertFalse(contact.containsKey("fullName"), "嵌套对象中拼接字段不得输出: " + contact);
    }

    @Test
    void listElementCompositeDroppedWhenComponentHasNoStrategy() {
        MaskingPolicy policy = policyWithoutFirstNameRule();
        Map<String, Object> input = Map.of(
                "patientId", "P200",
                "visits", List.of(
                        Map.of("doctor", Map.of(
                                "lastName", "李",
                                "firstName", "四")))); // 列表元素内 firstName 无策略
        Map<String, Object> masked = maskingService.mask(input, policy);
        logCase("列表元素-成分缺规则", input, policy, masked);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> visits = (List<Map<String, Object>>) masked.get("visits");
        @SuppressWarnings("unchecked")
        Map<String, Object> doctor = (Map<String, Object>) visits.get(0).get("doctor");
        assertFalse(doctor.containsKey("fullName"), "列表元素中拼接字段不得输出: " + doctor);
    }

    @Test
    void compositeDroppedWhenComponentValueIsNotPlainText() {
        MaskingPolicy policy = policyWithBothRules();
        Map<String, Object> input = Map.of(
                "patientId", "P200",
                "lastName", "欧阳",
                "firstName", Map.of("given", "娜娜", "pinyin", "nana")); // 成分值是嵌套对象
        Map<String, Object> masked = maskingService.mask(input, policy);
        logCase("成分值非文本", input, policy, masked);

        // 拼接字段整体不输出，嵌套结构的原文不会被 toString 拼进组合值
        assertFalse(masked.containsKey("fullName"), "成分值非文本时拼接字段不得输出: " + masked);
        assertFalse(String.valueOf(masked.getOrDefault("fullName", "")).contains("娜娜"));
    }

    @Test
    void compositeDroppedWhenComponentValueIsList() {
        MaskingPolicy policy = policyWithBothRules();
        Map<String, Object> input = Map.of(
                "patientId", "P200",
                "lastName", "欧阳",
                "firstName", List.of("娜", "娜")); // 成分值是列表
        Map<String, Object> masked = maskingService.mask(input, policy);
        logCase("成分值为列表", input, policy, masked);

        assertFalse(masked.containsKey("fullName"), "成分值为列表时拼接字段不得输出: " + masked);
    }

    @Test
    void fullyConfiguredCompositeStillWorks() {
        // 对照组：成分都配了策略且都是简单文本时，拼接字段正常输出且不泄露原文
        MaskingPolicy policy = policyWithBothRules();
        Map<String, Object> input = Map.of("lastName", "欧阳", "firstName", "娜娜");
        Map<String, Object> masked = maskingService.mask(input, policy);
        logCase("对照组", input, policy, masked);

        assertTrue(masked.containsKey("fullName"));
        String composed = String.valueOf(masked.get("fullName"));
        assertFalse(composed.contains("欧阳") || composed.contains("娜娜"),
                "组合值泄露成分原文: " + composed);
    }
}
