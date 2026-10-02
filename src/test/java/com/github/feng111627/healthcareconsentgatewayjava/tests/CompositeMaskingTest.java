package com.github.feng111627.healthcareconsentgatewayjava.tests;

import com.github.feng111627.healthcareconsentgatewayjava.masking.MaskingException;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拼接字段脱敏：
 * - 各成分按各自策略脱敏后再拼，输出里不出现任何成分原文（顶层/嵌套/列表都验证）；
 * - 成分缺策略时 fail-close 抛异常，绝不先拼原文。
 */
class CompositeMaskingTest {

    private static final Logger log = LoggerFactory.getLogger(CompositeMaskingTest.class);

    private MaskingService maskingService;

    @BeforeEach
    void setUp() {
        maskingService = new MaskingService(new MockEnvironment()
                .withProperty("gateway.masking.version", "masking-v1")
                .withProperty("gateway.masking.fields.name", "PARTIAL")
                .withProperty("gateway.masking.fields.idNumber", "HASH")
                .withProperty("gateway.masking.fields.phone", "PARTIAL")
                .withProperty("gateway.masking.fields.areaCode", "HASH")
                .withProperty("gateway.masking.fields.contactNumber", "PARTIAL")
                .withProperty("gateway.masking.fields.address", "FULL"));
    }

    private Map<String, Object> sample() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("patientId", "P001");
        m.put("firstName", "欧阳");
        m.put("lastName", "伟");
        m.put("areaCode", "0086");
        m.put("contactNumber", "13812345678");
        m.put("country", "中国");
        m.put("city", "北京市");
        m.put("district", "朝阳区");
        m.put("street", "建国路100号");
        m.put("emergencyContact", Map.of(
                "firstName", "司马", "lastName", "强",
                "areaCode", "0086", "contactNumber", "13987654321"));
        m.put("visits", List.of(Map.of(
                "visitId", "V001",
                "country", "中国", "city", "北京市",
                "district", "朝阳区", "street", "第一医院")));
        return m;
    }

    @Test
    void compositeComponentsAreMaskedSeparatelyBeforeJoining() {
        Map<String, Object> masked = maskingService.mask(sample(), maskingService.currentPolicy());
        log.info("输入: {}", sample());
        log.info("判定依据: policy={}, composites={}",
                maskingService.currentPolicy().getVersion(),
                maskingService.currentPolicy().getComposites());
        log.info("输出: {}", masked);

        // fullName = 先各自脱敏再以空格拼接：双字姓保留首尾，单字名整体打码
        assertEquals("欧* ***", masked.get("fullName"));
        // contactPhone = areaCode(HASH) + "-" + contactNumber(PARTIAL)：两成分规则不同
        String contactPhone = (String) masked.get("contactPhone");
        assertTrue(contactPhone.startsWith("sha256:"), contactPhone);
        assertTrue(contactPhone.endsWith("-1***8"), contactPhone);
        // fullAddress = 多段各自 PARTIAL 后无分隔符合并
        String fullAddress = (String) masked.get("fullAddress");
        assertTrue(fullAddress.startsWith("中*"), fullAddress);
        // 成分键不单独输出
        for (String component : List.of("firstName", "lastName", "areaCode",
                "contactNumber", "country", "city", "district", "street")) {
            assertFalse(masked.containsKey(component), "成分键不应出现在输出: " + component);
        }
    }

    @Test
    void noRawComponentLeaksAtTopLevelNestedAndList() {
        Map<String, Object> masked = maskingService.mask(sample(), maskingService.currentPolicy());
        String serialized = String.valueOf(masked);
        log.info("输入: {}", sample());
        log.info("输出(拼接后): {}", serialized);

        // 所有成分原文（顶层/嵌套/列表）都不得出现在拼接/序列化结果中
        for (String raw : List.of("欧阳伟", "司马强", "欧阳", "司马", "0086",
                "13812345678", "13987654321",
                "中国", "北京市", "朝阳区", "建国路100号", "第一医院")) {
            assertFalse(serialized.contains(raw), "拼接输出原文泄露: " + raw);
        }
        // 嵌套对象与列表里的拼接字段同样被替换
        @SuppressWarnings("unchecked")
        Map<String, Object> contact = (Map<String, Object>) masked.get("emergencyContact");
        assertTrue(contact.containsKey("fullName"));
        assertTrue(contact.containsKey("contactPhone"));
        assertNull(contact.get("contactNumber"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> visits = (List<Map<String, Object>>) masked.get("visits");
        assertTrue(visits.get(0).containsKey("fullAddress"));
        assertFalse(String.valueOf(visits.get(0)).contains("第一医院"));
    }

    @Test
    void compositeMissingStrategyFailsClosed() {
        // 策略没有 firstName 的成分规则时，处理必须抛异常而不是把原文拼出去
        MaskingPolicy unsafe = new MaskingPolicy("unsafe",
                Map.of("lastName", MaskingPolicy.Strategy.FULL),
                List.of(new com.github.feng111627.healthcareconsentgatewayjava.masking.CompositeField(
                        "fullName", List.of("firstName", "lastName"), " ")));
        log.info("输入: 拼接成分 firstName 无脱敏策略");
        MaskingException ex = assertThrows(MaskingException.class,
                () -> maskingService.mask(
                        Map.of("firstName", "张", "lastName", "伟"), unsafe));
        log.info("判定依据: fail-close -> {}", ex.getMessage());
        assertTrue(ex.getMessage().contains("firstName"));
    }

    @Test
    void historicalPolicyCompositesAreReproducible() {
        Map<String, Object> masked = maskingService.mask(sample(),
                maskingService.policyByVersion("masking-v0"));
        log.info("判定依据: 复现 masking-v0（全打码）输出: {}", masked);
        assertEquals("*** ***", masked.get("fullName"));
        assertEquals("***-***", masked.get("contactPhone"));
        assertEquals("************", masked.get("fullAddress"));
    }
}
