package com.github.feng111627.healthcareconsentgatewayjava.tests.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.feng111627.healthcareconsentgatewayjava.config.DataInitializer;
import com.github.feng111627.healthcareconsentgatewayjava.model.CompositeFieldRule;
import com.github.feng111627.healthcareconsentgatewayjava.model.MaskingRuleSet;
import com.github.feng111627.healthcareconsentgatewayjava.model.MaskStrategy;
import com.github.feng111627.healthcareconsentgatewayjava.policy.PolicyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端：HTTP 入站 -> 判定 -> 留痕 -> 脱敏 -> 响应。
 * 覆盖撤回 / 过期 / 越权 / 无授权 / 放行脱敏 / 脱敏失败兜底 / 审计链接口。
 */
@SpringBootTest
class GatewayIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(GatewayIntegrationTest.class);

    @Autowired
    private WebApplicationContext wac;
    @Autowired
    private PolicyService policyService;
    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mvc;

    private MockMvc mvc() {
        if (mvc == null) {
            mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        }
        return mvc;
    }

    private JsonNode access(String requester, String purpose) throws Exception {
        Map<String, Object> body = Map.of(
                "requesterId", requester,
                "patientId", DataInitializer.PATIENT_ID,
                "purpose", purpose);
        log.info("[集成测试] 请求输入: {}", body);
        MvcResult result = mvc().perform(post("/api/patients/access")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        log.info("[集成测试] 响应判定依据与结论: reason={}, allowed={}, masked={}, maskingError={}",
                json.path("decision").path("reason").asText(),
                json.path("decision").path("allowed").asBoolean(),
                json.path("masked").asBoolean(),
                json.path("maskingError").asText(null));
        return json;
    }

    @Test
    @DisplayName("治疗用途放行：数据返回且全部敏感字段已脱敏，无原文残留")
    void grantedAndMasked() throws Exception {
        JsonNode res = access(DataInitializer.REQ_CLINIC, "TREATMENT");
        assertThat(res.path("decision").path("reason").asText()).isEqualTo("GRANTED");
        assertThat(res.path("masked").asBoolean()).isTrue();
        JsonNode data = res.path("data");
        assertThat(data.path("idCardNo").asText()).isEqualTo("[REDACTED]");
        assertThat(data.path("contact").path("email").asText()).isEqualTo("z***@example.com");
        assertThat(data.path("contact").path("phone").asText()).isEqualTo("***8000");
        assertThat(data.path("fullName").asText()).doesNotContain("San Zhang");
        // 非敏感诊断保留
        assertThat(data.path("diagnosis").asText()).contains("hypertension");

        String raw = data.toString();
        assertThat(raw).doesNotContain("110101199001011234", "13800138000");
    }

    @Test
    @DisplayName("已撤回授权：CONSENT_REVOKED 且 data 为 null")
    void revokedRejected() throws Exception {
        JsonNode res = access(DataInitializer.REQ_REVOKED, "TREATMENT");
        assertThat(res.path("decision").path("reason").asText()).isEqualTo("CONSENT_REVOKED");
        assertThat(res.path("decision").path("allowed").asBoolean()).isFalse();
        assertThat(res.path("data").isNull()).isTrue();
    }

    @Test
    @DisplayName("已过期授权：CONSENT_EXPIRED")
    void expiredRejected() throws Exception {
        JsonNode res = access(DataInitializer.REQ_EXPIRED, "TREATMENT");
        assertThat(res.path("decision").path("reason").asText()).isEqualTo("CONSENT_EXPIRED");
        assertThat(res.path("data").isNull()).isTrue();
    }

    @Test
    @DisplayName("研究机构以治疗用途访问：OUT_OF_SCOPE")
    void outOfScopeRejected() throws Exception {
        JsonNode res = access(DataInitializer.REQ_RESEARCH, "TREATMENT");
        assertThat(res.path("decision").path("reason").asText()).isEqualTo("OUT_OF_SCOPE");
        assertThat(res.path("data").isNull()).isTrue();
    }

    @Test
    @DisplayName("未知请求方：CONSENT_NOT_FOUND")
    void noConsentRejected() throws Exception {
        JsonNode res = access("unknown-org", "TREATMENT");
        assertThat(res.path("decision").path("reason").asText())
                .isEqualTo("CONSENT_NOT_FOUND");
    }

    @Test
    @DisplayName("四类拒绝原因在接口层同样可区分")
    void rejectionReasonsDistinguishableOverHttp() throws Exception {
        String revoked = access(DataInitializer.REQ_REVOKED, "TREATMENT")
                .path("decision").path("reason").asText();
        String expired = access(DataInitializer.REQ_EXPIRED, "TREATMENT")
                .path("decision").path("reason").asText();
        String scope = access(DataInitializer.REQ_RESEARCH, "TREATMENT")
                .path("decision").path("reason").asText();
        String none = access("unknown-org", "TREATMENT")
                .path("decision").path("reason").asText();
        assertThat(revoked).isEqualTo("CONSENT_REVOKED");
        assertThat(expired).isEqualTo("CONSENT_EXPIRED");
        assertThat(scope).isEqualTo("OUT_OF_SCOPE");
        assertThat(none).isEqualTo("CONSENT_NOT_FOUND");
        assertThat(revoked).isNotEqualTo(expired).isNotEqualTo(scope).isNotEqualTo(none);
    }

    @Test
    @DisplayName("审计校验接口返回 valid=true，且每次判定都产生一条记录")
    void auditChainVerifiable() throws Exception {
        access(DataInitializer.REQ_CLINIC, "TREATMENT");
        access(DataInitializer.REQ_REVOKED, "TREATMENT");

        MvcResult result = mvc().perform(get("/api/audit/verify"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode report = objectMapper.readTree(result.getResponse().getContentAsString());
        log.info("[集成测试] 审计链校验: {}", report);
        assertThat(report.path("valid").asBoolean()).isTrue();
        assertThat(report.path("recordCount").asInt()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("脱敏规则被配置成无法处理（成分缺失）时：不带原文，返回 MASKING_FAILED 并留痕")
    void maskingFailureNeverLeaksRawData() throws Exception {
        // 发布一份“坏规则”：fullName 拼接依赖不存在的 middleName
        MaskingRuleSet broken = new MaskingRuleSet("policy-broken-" + System.nanoTime(),
                new LinkedHashMap<>(Map.of("idCardNo", MaskStrategy.REDACT)),
                List.of(new CompositeFieldRule("fullName", "{0} {1}",
                        List.of("firstName", "middleName"), MaskStrategy.PARTIAL)));
        policyService.publish(broken.version(), broken);

        try {
            JsonNode res = access(DataInitializer.REQ_CLINIC, "TREATMENT");
            log.warn("[集成测试] 脱敏失败响应: {}", res);
            assertThat(res.path("maskingError").asText()).isEqualTo("MASKING_FAILED");
            assertThat(res.path("data").isNull()).isTrue();
            assertThat(res.toString()).doesNotContain("110101199001011234");

            // 失败事实进入审计链，链仍然完整
            JsonNode report = objectMapper.readTree(mvc().perform(get("/api/audit/verify"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(report.path("valid").asBoolean()).isTrue();
        } finally {
            // 恢复当前策略，避免污染共享 Spring 上下文里的其它用例
            policyService.activate(DataInitializer.POLICY_V1);
        }
    }
}
