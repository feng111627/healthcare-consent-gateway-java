package com.github.feng111627.healthcareconsentgatewayjava.tests.unit;

import com.github.feng111627.healthcareconsentgatewayjava.config.DataInitializer;
import com.github.feng111627.healthcareconsentgatewayjava.model.CompositeFieldRule;
import com.github.feng111627.healthcareconsentgatewayjava.model.MaskingRuleSet;
import com.github.feng111627.healthcareconsentgatewayjava.model.MaskStrategy;
import com.github.feng111627.healthcareconsentgatewayjava.service.DefaultMaskingService;
import com.github.feng111627.healthcareconsentgatewayjava.service.MaskingException;
import com.github.feng111627.healthcareconsentgatewayjava.service.MaskingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 脱敏：策略效果、嵌套/列表、拼接字段、失败兜底。 */
class MaskingTest {

    private static final Logger log = LoggerFactory.getLogger(MaskingTest.class);

    private final MaskingService masking = new DefaultMaskingService();

    @Test
    @DisplayName("四种策略分别产生可区分的脱敏结果")
    void strategies() {
        log.info("[测试断言] 输入 PARTIAL='ZhangSan' => 期望首尾保留; "
                + "EMAIL='a@b.com'; PHONE='13800138000'; REDACT 整段遮蔽");
        assertThat(DefaultMaskingService.partial("ZhangSan")).isEqualTo("Z******n");
        assertThat(DefaultMaskingService.email("zhangsan@example.com"))
                .isEqualTo("z***@example.com");
        assertThat(DefaultMaskingService.phone("13800138000")).isEqualTo("***8000");
        Map<String, Object> in = Map.of("x", "secret");
        MaskingRuleSet rules = new MaskingRuleSet("t",
                Map.of("x", MaskStrategy.REDACT), List.of());
        assertThat(masking.mask(in, rules)).containsEntry("x", "[REDACTED]");
    }

    @Test
    @DisplayName("v1 规则：嵌套 contact/emergencyContact 中的字段也被脱敏，原文不残留")
    void nestedMaskingWithV1() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("firstName", "San");
        raw.put("lastName", "Zhang");
        raw.put("fullName", "San Zhang");
        raw.put("idCardNo", "110101199001011234");
        raw.put("contact", Map.of("email", "zhangsan@example.com", "phone", "13800138000"));
        raw.put("emergencyContact", Map.of("name", "Si Li", "phone", "13911112222"));
        raw.put("diagnosis", "hypertension");

        log.info("[测试断言] 输入原文={}", raw);
        Map<String, Object> out = masking.mask(raw, DataInitializer.maskingV1());
        log.info("[测试断言] 脱敏结果={}", out);

        assertThat(out.get("firstName")).isEqualTo("S*n");
        assertThat(out.get("lastName")).isEqualTo("Z***g");
        // 拼接字段：两个成分都脱敏后重组，不能出现完整姓名
        assertThat((String) out.get("fullName")).isEqualTo("S*n Z***g");
        assertThat(out.get("idCardNo")).isEqualTo("[REDACTED]");

        @SuppressWarnings("unchecked")
        Map<String, Object> contact = (Map<String, Object>) out.get("contact");
        assertThat(contact.get("email")).isEqualTo("z***@example.com");
        assertThat(contact.get("phone")).isEqualTo("***8000");

        @SuppressWarnings("unchecked")
        Map<String, Object> emergency = (Map<String, Object>) out.get("emergencyContact");
        assertThat(emergency.get("phone")).isEqualTo("***2222");
        // 非敏感字段原样保留
        assertThat(out.get("diagnosis")).isEqualTo("hypertension");

        String serialized = out.toString();
        assertThat(serialized)
                .doesNotContain("110101199001011234")
                .doesNotContain("zhangsan@example.com")
                .doesNotContain("13800138000")
                .doesNotContain("San Zhang");
    }

    @Test
    @DisplayName("列表内元素按叶子字段名同样脱敏")
    void listElementsMasked() {
        Map<String, Object> raw = Map.of(
                "patientId", "P-1",
                "relatives", List.of(
                        Map.of("name", "Alice"),
                        Map.of("name", "Bob")));
        MaskingRuleSet rules = new MaskingRuleSet("t",
                Map.of("name", MaskStrategy.PARTIAL), List.of());
        Map<String, Object> out = masking.mask(raw, rules);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> relatives =
                (List<Map<String, Object>>) out.get("relatives");
        assertThat(relatives).extracting(m -> m.get("name")).containsExactly("A***e", "B*b");
    }

    @Test
    @DisplayName("点号路径规则优先于叶子名，可对同名字段差异化处理")
    void dottedPathTakesPrecedence() {
        Map<String, Object> raw = Map.of(
                "name", "TopLevel",
                "contact", Map.of("name", "NestedName"));
        MaskingRuleSet rules = new MaskingRuleSet("t",
                new LinkedHashMap<>(Map.of(
                        "name", MaskStrategy.PARTIAL,
                        "contact.name", MaskStrategy.REDACT)),
                List.of());
        Map<String, Object> out = masking.mask(raw, rules);
        assertThat(out.get("name")).isEqualTo("T******l");
        @SuppressWarnings("unchecked")
        Map<String, Object> contact = (Map<String, Object>) out.get("contact");
        assertThat(contact.get("name")).isEqualTo("[REDACTED]");
    }

    @Test
    @DisplayName("拼接字段缺少成分时抛 MaskingException，绝不回退返回原文")
    void compositeMissingComponentFailsClosed() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("firstName", "San");
        // 故意缺少 lastName
        raw.put("fullName", "San Zhang");
        MaskingRuleSet rules = new MaskingRuleSet("t",
                Map.of("firstName", MaskStrategy.PARTIAL),
                List.of(new CompositeFieldRule("fullName", "{0} {1}",
                        List.of("firstName", "lastName"), MaskStrategy.PARTIAL)));
        assertThatThrownBy(() -> masking.mask(raw, rules))
                .isInstanceOf(MaskingException.class)
                .message().contains("lastName");
    }

    @Test
    @DisplayName("规则缺失（ruleSet 为 null）直接失败而不是裸奔返回原文")
    void nullRuleSetFails() {
        assertThatThrownBy(() -> masking.mask(Map.of("x", "y"), null))
                .isInstanceOf(MaskingException.class);
    }
}
