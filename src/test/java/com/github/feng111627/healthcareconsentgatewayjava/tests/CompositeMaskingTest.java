package com.github.feng111627.healthcareconsentgatewayjava.tests;

import com.github.feng111627.healthcareconsentgatewayjava.masking.CompositeField;
import com.github.feng111627.healthcareconsentgatewayjava.masking.MaskingPolicy;
import com.github.feng111627.healthcareconsentgatewayjava.masking.MaskingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拼接字段：成分先各自按策略脱敏、脱敏结果再组合；
 * 顶层 / 嵌套对象 / 列表元素中的组合输出都不得含任何成分原文。
 */
class CompositeMaskingTest {

    private static final Logger log = LoggerFactory.getLogger(CompositeMaskingTest.class);

    private MaskingService maskingService;

    @BeforeEach
    void setUp() {
        // 成分策略故意各不相同：PARTIAL / FULL / HASH 混合
        MockEnvironment env = new MockEnvironment()
                .withProperty("gateway.masking.version", "masking-v1")
                .withProperty("gateway.masking.fields.lastName", "PARTIAL")
                .withProperty("gateway.masking.fields.firstName", "FULL")
                .withProperty("gateway.masking.fields.areaCode", "PARTIAL")
                .withProperty("gateway.masking.fields.phoneNumber", "HASH")
                .withProperty("gateway.masking.fields.province", "PARTIAL")
                .withProperty("gateway.masking.fields.city", "FULL")
                .withProperty("gateway.masking.fields.street", "PARTIAL")
                .withProperty("gateway.masking.composites.fullName", "lastName,firstName")
                .withProperty("gateway.masking.composites.contactPhone", "areaCode,phoneNumber")
                .withProperty("gateway.masking.composites.contactPhone.separator", "-")
                .withProperty("gateway.masking.composites.fullAddress", "province,city,street");
        maskingService = new MaskingService(env);
    }

    private Map<String, Object> sampleData() {
        return Map.of(
                "patientId", "P100",
                "lastName", "欧阳",
                "firstName", "娜娜",
                "areaCode", "010",
                "phoneNumber", "67890123",
                "province", "北京市",
                "city", "朝阳区",
                "street", "建国路100号",
                "emergencyContact", Map.of(
                        "lastName", "张",
                        "firstName", "伟"),
                "visits", List.of(
                        Map.of("doctor", Map.of(
                                "lastName", "李",
                                "firstName", "四"))));
    }

    @Test
    void compositeIsBuiltFromMaskedComponentsWithPerComponentStrategies() {
        Map<String, Object> input = sampleData();
        Map<String, Object> masked = maskingService.mask(input, maskingService.currentPolicy());
        log.info("输入: {}", input);
        log.info("判定依据: composites={}, strategies={}",
                maskingService.currentPolicy().getComposites(),
                maskingService.currentPolicy().getFieldStrategies());
        log.info("输出: {}", masked);

        // 成分各自按自己的策略脱敏
        assertEquals("欧*", masked.get("lastName"));          // PARTIAL
        assertEquals("***", masked.get("firstName"));         // FULL
        assertEquals("0***0", masked.get("areaCode"));        // PARTIAL
        assertTrue(String.valueOf(masked.get("phoneNumber")).startsWith("sha256:")); // HASH
        // 组合值 = 脱敏后的成分按序拼接（先处理再组合，不是先组合再处理）
        assertEquals("欧****", masked.get("fullName"));
        String contactPhone = String.valueOf(masked.get("contactPhone"));
        assertTrue(contactPhone.startsWith("0***0-sha256:"), "contactPhone=" + contactPhone);
        assertEquals("北***市***建***号", masked.get("fullAddress"));
    }

    @Test
    void compositeAppliesInNestedMapsAndLists() {
        Map<String, Object> masked = maskingService.mask(sampleData(), maskingService.currentPolicy());
        log.info("输出: {}", masked);

        // 嵌套对象中的拼接
        @SuppressWarnings("unchecked")
        Map<String, Object> contact = (Map<String, Object>) masked.get("emergencyContact");
        assertEquals("******", contact.get("fullName")); // "***"(PARTIAL 单字) + "***"(FULL)
        // 列表 -> 元素 -> 嵌套对象中的拼接
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> visits = (List<Map<String, Object>>) masked.get("visits");
        @SuppressWarnings("unchecked")
        Map<String, Object> doctor = (Map<String, Object>) visits.get(0).get("doctor");
        assertEquals("******", doctor.get("fullName"));
    }

    @Test
    void noRawComponentLeaksInComposedOrSerializedOutput() {
        Map<String, Object> input = sampleData();
        Map<String, Object> masked = maskingService.mask(input, maskingService.currentPolicy());
        String serialized = masked.toString();
        log.info("输入: {}", input);
        log.info("输出(序列化拼接后): {}", serialized);

        // 序列化后的整体输出不含任何成分原文
        for (String raw : List.of("欧阳", "娜娜", "010", "67890123",
                "北京市", "朝阳区", "建国路100号", "张", "伟", "李", "四")) {
            assertFalse(serialized.contains(raw), "原文泄露: " + raw);
        }
        // 组合值本身也不含成分原文
        for (String compositeKey : List.of("fullName", "contactPhone", "fullAddress")) {
            String composed = String.valueOf(masked.get(compositeKey));
            for (String raw : List.of("欧阳", "娜娜", "010", "67890123",
                    "北京市", "朝阳区", "建国路100号")) {
                assertFalse(composed.contains(raw),
                    "组合值 " + compositeKey + "=" + composed + " 泄露成分原文: " + raw);
            }
        }
    }

    @Test
    void missingComponentSkipsCompositeInsteadOfLeaking() {
        Map<String, Object> input = Map.of(
                "lastName", "欧阳",
                // firstName 缺失
                "areaCode", "010",
                "phoneNumber", "67890123");
        Map<String, Object> masked = maskingService.mask(input, maskingService.currentPolicy());
        log.info("输入(成分缺失): {}", input);
        log.info("输出: {}", masked);

        // 成分不全时不生成拼接字段，也不会把半个原文拼出去
        assertFalse(masked.containsKey("fullName"));
        assertTrue(String.valueOf(masked.get("contactPhone")).startsWith("0***0-sha256:"));
        assertFalse(masked.toString().contains("欧阳"));
    }

    /** 成分没配到处理规则的策略：firstName 无规则，fullName=lastName,firstName。 */
    private MaskingPolicy policyWithoutFirstNameRule() {
        return new MaskingPolicy("test-no-firstname-rule",
                Map.of("lastName", MaskingPolicy.Strategy.PARTIAL),
                List.of(new CompositeField("fullName", List.of("lastName", "firstName"), "")));
    }

    @Test
    void componentWithoutRuleSkipsCompositeAtTopLevel() {
        MaskingPolicy policy = policyWithoutFirstNameRule();
        Map<String, Object> input = Map.of("lastName", "欧阳", "firstName", "娜娜");
        Map<String, Object> masked = maskingService.mask(input, policy);
        log.info("输入(顶层成分 firstName 无规则): {}", input);
        log.info("判定依据: strategies={}, composites={}", policy.getFieldStrategies(), policy.getComposites());
        log.info("输出: {}", masked);

        // firstName 没配规则 -> 拼接字段整体不输出，绝不能把未脱敏的「娜娜」拼进结果
        assertFalse(masked.containsKey("fullName"), "成分无规则时不得输出拼接字段");
        Object fullName = masked.get("fullName");
        assertFalse(fullName != null && String.valueOf(fullName).contains("娜娜"), "拼接值泄露成分原文");
    }

    @Test
    void nonTextComponentSkipsCompositeAtTopLevel() {
        // firstName 配了规则，但它的值不是简单文本（结构化对象）
        Map<String, Object> input = Map.of(
                "lastName", "欧阳",
                "firstName", Map.of("given", "娜娜"));
        Map<String, Object> masked = maskingService.mask(input, maskingService.currentPolicy());
        log.info("输入(顶层成分 firstName 为对象): {}", input);
        log.info("输出: {}", masked);

        assertFalse(masked.containsKey("fullName"), "成分不是简单文本时不得输出拼接字段");
        // 拼接字段不存在，结构化内容不会被 toString 拼进任何字符串值
        for (Object value : masked.values()) {
            if (value instanceof CharSequence) {
                assertFalse(value.toString().contains("娜娜"), "字符串值泄露成分原文: " + value);
            }
        }
    }

    @Test
    void unprocessableComponentSkipsCompositeInNestedObject() {
        MaskingPolicy policy = policyWithoutFirstNameRule();
        Map<String, Object> input = Map.of(
                "emergencyContact", Map.of("lastName", "张", "firstName", "伟"),
                "legalGuardian", Map.of("lastName", "李", "firstName", Map.of("given", "四")));
        Map<String, Object> masked = maskingService.mask(input, maskingService.currentPolicy());
        // 嵌套对象里：firstName 无规则（用无规则策略重算一次）
        Map<String, Object> maskedNoRule = maskingService.mask(input, policy);
        log.info("输入(嵌套对象成分无规则/非文本): {}", input);
        log.info("输出(无规则策略): {}", maskedNoRule);
        log.info("输出(非文本成分): {}", masked);

        @SuppressWarnings("unchecked")
        Map<String, Object> contact = (Map<String, Object>) maskedNoRule.get("emergencyContact");
        assertFalse(contact.containsKey("fullName"), "嵌套对象中成分无规则时不得输出拼接字段");
        @SuppressWarnings("unchecked")
        Map<String, Object> guardian = (Map<String, Object>) masked.get("legalGuardian");
        assertFalse(guardian.containsKey("fullName"), "嵌套对象中成分非文本时不得输出拼接字段");
    }

    @Test
    void unprocessableComponentSkipsCompositeInListElements() {
        MaskingPolicy policy = policyWithoutFirstNameRule();
        Map<String, Object> input = Map.of("visits", List.of(
                Map.of("doctor", Map.of("lastName", "李", "firstName", "四")),
                Map.of("doctor", Map.of("lastName", "王", "firstName", Map.of("given", "五")))));
        Map<String, Object> maskedNoRule = maskingService.mask(input, policy);
        Map<String, Object> maskedNonText = maskingService.mask(input, maskingService.currentPolicy());
        log.info("输入(列表元素成分无规则/非文本): {}", input);
        log.info("输出(无规则策略): {}", maskedNoRule);
        log.info("输出(非文本成分): {}", maskedNonText);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> visitsNoRule = (List<Map<String, Object>>) maskedNoRule.get("visits");
        @SuppressWarnings("unchecked")
        Map<String, Object> doctor0 = (Map<String, Object>) visitsNoRule.get(0).get("doctor");
        assertFalse(doctor0.containsKey("fullName"), "列表元素中成分无规则时不得输出拼接字段");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> visitsNonText = (List<Map<String, Object>>) maskedNonText.get("visits");
        @SuppressWarnings("unchecked")
        Map<String, Object> doctor1 = (Map<String, Object>) visitsNonText.get(1).get("doctor");
        assertFalse(doctor1.containsKey("fullName"), "列表元素中成分非文本时不得输出拼接字段");
        // 无规则策略下两个列表元素都不得生成拼接字段（序列化输出中不存在 fullName）
        assertFalse(maskedNoRule.toString().contains("fullName"));
    }
}
