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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaskingServiceTest {

    private static final Logger log = LoggerFactory.getLogger(MaskingServiceTest.class);

    private MaskingService maskingService;

    @BeforeEach
    void setUp() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("gateway.masking.version", "masking-v1")
                .withProperty("gateway.masking.fields.name", "PARTIAL")
                .withProperty("gateway.masking.fields.idNumber", "HASH")
                .withProperty("gateway.masking.fields.phone", "PARTIAL")
                .withProperty("gateway.masking.fields.address", "FULL");
        maskingService = new MaskingService(env);
    }

    private Map<String, Object> sampleData() {
        return Map.of(
                "patientId", "P001",
                "name", "张伟",
                "idNumber", "110101199003077799",
                "phone", "13812345678",
                "address", "北京市朝阳区建国路100号",
                "emergencyContact", Map.of("name", "张强", "phone", "13987654321"),
                "visits", List.of(
                        Map.of("visitId", "V001",
                                "doctor", Map.of("name", "李医生", "phone", "13711112222"))));
    }

    @Test
    void masksNestedStructuresRecursively() {
        Map<String, Object> input = sampleData();
        Map<String, Object> masked = maskingService.mask(input, maskingService.currentPolicy());
        log.info("输入: {}", input);
        log.info("判定依据: policyVersion={}, rules={}",
                maskingService.currentPolicy().getVersion(),
                maskingService.currentPolicy().getFieldStrategies());
        log.info("输出: {}", masked);

        // 顶层字段
        assertEquals("张*", masked.get("name"));
        assertEquals("***", masked.get("address"));
        assertTrue(String.valueOf(masked.get("idNumber")).startsWith("sha256:"));
        // 嵌套 Map
        @SuppressWarnings("unchecked")
        Map<String, Object> contact = (Map<String, Object>) masked.get("emergencyContact");
        assertEquals("张*", contact.get("name"));
        assertEquals("1***1", contact.get("phone"));
        // 嵌套 List -> Map -> Map
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> visits = (List<Map<String, Object>>) masked.get("visits");
        @SuppressWarnings("unchecked")
        Map<String, Object> doctor = (Map<String, Object>) visits.get(0).get("doctor");
        assertEquals("李***生", doctor.get("name"));
        // 非敏感字段原样保留
        assertEquals("P001", masked.get("patientId"));
        assertEquals("V001", visits.get(0).get("visitId"));
    }

    @Test
    void noRawValueLeaksInSerializedOutput() {
        Map<String, Object> input = sampleData();
        Map<String, Object> masked = maskingService.mask(input, maskingService.currentPolicy());
        String serialized = masked.toString();
        log.info("输入: {}", input);
        log.info("输出(拼接后): {}", serialized);

        for (String raw : List.of("张伟", "张强", "李医生",
                "110101199003077799", "13812345678", "13987654321", "13711112222",
                "北京市朝阳区建国路100号")) {
            assertFalse(serialized.contains(raw), "原文泄露: " + raw);
        }
    }

    @Test
    void maskingFailureThrowsAndDoesNotReturnRawData() {
        Map<String, Object> input = sampleData();
        log.info("输入: {}, policy=null", input);
        // 策略为空时必须抛异常，而不是返回原始数据
        assertThrows(MaskingException.class, () -> maskingService.mask(input, null));
    }

    @Test
    void historicalPolicyVersionIsReproducible() {
        MaskingPolicy v0 = maskingService.policyByVersion("masking-v0");
        log.info("判定依据: 复现历史策略版本 masking-v0, rules={}", v0.getFieldStrategies());
        Map<String, Object> masked = maskingService.mask(sampleData(), v0);
        assertEquals("***", masked.get("name"));
        assertEquals("***", masked.get("phone"));
    }
}
