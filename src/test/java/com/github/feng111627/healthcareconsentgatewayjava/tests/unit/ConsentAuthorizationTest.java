package com.github.feng111627.healthcareconsentgatewayjava.tests.unit;

import com.github.feng111627.healthcareconsentgatewayjava.config.DataInitializer;
import com.github.feng111627.healthcareconsentgatewayjava.domain.AccessDecision;
import com.github.feng111627.healthcareconsentgatewayjava.domain.AccessRequest;
import com.github.feng111627.healthcareconsentgatewayjava.domain.ConsentGrant;
import com.github.feng111627.healthcareconsentgatewayjava.domain.DecisionReason;
import com.github.feng111627.healthcareconsentgatewayjava.domain.Purpose;
import com.github.feng111627.healthcareconsentgatewayjava.policy.InMemoryPolicyService;
import com.github.feng111627.healthcareconsentgatewayjava.repo.InMemoryConsentRepository;
import com.github.feng111627.healthcareconsentgatewayjava.service.AuthorizationService;
import com.github.feng111627.healthcareconsentgatewayjava.service.DefaultAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 授权判定：撤回 / 过期 / 越权 / 无授权 / 放行 五类结论可区分。 */
class ConsentAuthorizationTest {

    private static final Logger log = LoggerFactory.getLogger(ConsentAuthorizationTest.class);

    private static final String PATIENT = "P-1";
    private InMemoryConsentRepository consents;
    private AuthorizationService auth;
    private Instant now;

    @BeforeEach
    void setUp() {
        consents = new InMemoryConsentRepository();
        InMemoryPolicyService policies = new InMemoryPolicyService();
        policies.publish(DataInitializer.POLICY_V1, DataInitializer.maskingV1());
        auth = new DefaultAuthorizationService(consents, policies);
        now = Instant.parse("2026-10-01T08:00:00Z");
    }

    private void save(String consentId, String requester, List<Purpose> purposes,
                      Instant expiresAt, boolean revoked, Instant revokedAt) {
        consents.save(new ConsentGrant(consentId, PATIENT, requester, purposes,
                expiresAt, now.minusSeconds(3600), revoked, revokedAt));
    }

    private AccessDecision evaluate(String requester, Purpose purpose) {
        AccessRequest req = new AccessRequest(requester, PATIENT, purpose);
        AccessDecision d = auth.evaluate(req, now);
        log.info("[测试断言] 输入 requester={}, patient={}, purpose={} => 判定依据 consentId={}, "
                        + "revoked={}, expiresAt={}, allowedPurposes={}, policyVersion={} => "
                        + "reason={}, allowed={}",
                requester, PATIENT, purpose, d.basis().consentId(), d.basis().revoked(),
                d.basis().consentExpiresAt(), d.basis().allowedPurposes(),
                d.basis().policyVersion(), d.reason(), d.allowed());
        return d;
    }

    @Test
    @DisplayName("用途在授权范围内 -> GRANTED")
    void granted() {
        save("C-ok", "clinic", List.of(Purpose.TREATMENT, Purpose.PAYMENT), null, false, null);
        AccessDecision d = evaluate("clinic", Purpose.TREATMENT);
        assertThat(d.allowed()).isTrue();
        assertThat(d.reason()).isEqualTo(DecisionReason.GRANTED);
    }

    @Test
    @DisplayName("授权已撤回 -> CONSENT_REVOKED")
    void revoked() {
        save("C-revoked", "partner", List.of(Purpose.TREATMENT), null, true,
                now.minusSeconds(100));
        AccessDecision d = evaluate("partner", Purpose.TREATMENT);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo(DecisionReason.CONSENT_REVOKED);
        assertThat(d.basis().revoked()).isTrue();
        assertThat(d.basis().revokedAt()).isEqualTo(now.minusSeconds(100));
    }

    @Test
    @DisplayName("授权已过期 -> CONSENT_EXPIRED")
    void expired() {
        save("C-expired", "legacy", List.of(Purpose.TREATMENT),
                now.minusSeconds(1), false, null);
        AccessDecision d = evaluate("legacy", Purpose.TREATMENT);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo(DecisionReason.CONSENT_EXPIRED);
    }

    @Test
    @DisplayName("恰好在过期时刻判定也算过期（边界）")
    void expiredBoundary() {
        save("C-boundary", "legacy", List.of(Purpose.TREATMENT), now, false, null);
        AccessDecision d = evaluate("legacy", Purpose.TREATMENT);
        assertThat(d.reason()).isEqualTo(DecisionReason.CONSENT_EXPIRED);
    }

    @Test
    @DisplayName("用途超出授权范围 -> OUT_OF_SCOPE，与其它拒绝原因区分")
    void outOfScope() {
        save("C-scope", "lab", List.of(Purpose.RESEARCH), null, false, null);
        AccessDecision d = evaluate("lab", Purpose.TREATMENT);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo(DecisionReason.OUT_OF_SCOPE);
        assertThat(d.message()).contains("TREATMENT");
    }

    @Test
    @DisplayName("不存在任何授权 -> CONSENT_NOT_FOUND")
    void noConsent() {
        AccessDecision d = evaluate("stranger", Purpose.TREATMENT);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo(DecisionReason.CONSENT_NOT_FOUND);
        assertThat(d.basis().consentId()).isNull();
    }

    @Test
    @DisplayName("四类拒绝原因两两不同，拒绝信息可区分")
    void reasonsAreDistinct() {
        save("C-revoked", "partner", List.of(Purpose.TREATMENT), null, true,
                now.minusSeconds(10));
        save("C-expired", "legacy", List.of(Purpose.TREATMENT),
                now.minusSeconds(10), false, null);
        save("C-scope", "lab", List.of(Purpose.RESEARCH), null, false, null);

        DecisionReason revoked = evaluate("partner", Purpose.TREATMENT).reason();
        DecisionReason expired = evaluate("legacy", Purpose.TREATMENT).reason();
        DecisionReason scope = evaluate("lab", Purpose.TREATMENT).reason();
        DecisionReason none = evaluate("stranger", Purpose.TREATMENT).reason();

        assertThat(revoked).isEqualTo(DecisionReason.CONSENT_REVOKED);
        assertThat(expired).isEqualTo(DecisionReason.CONSENT_EXPIRED);
        assertThat(scope).isEqualTo(DecisionReason.OUT_OF_SCOPE);
        assertThat(none).isEqualTo(DecisionReason.CONSENT_NOT_FOUND);
        assertThat(List.of(revoked, expired, scope, none)).doesNotHaveDuplicates();
    }
}
