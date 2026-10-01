package com.github.feng111627.healthcareconsentgatewayjava.tests;

import com.github.feng111627.healthcareconsentgatewayjava.consent.Consent;
import com.github.feng111627.healthcareconsentgatewayjava.consent.ConsentEvaluator;
import com.github.feng111627.healthcareconsentgatewayjava.consent.Decision;
import com.github.feng111627.healthcareconsentgatewayjava.consent.DecisionResult;
import com.github.feng111627.healthcareconsentgatewayjava.consent.InMemoryConsentRepository;
import com.github.feng111627.healthcareconsentgatewayjava.consent.Purpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsentEvaluatorTest {

    private static final Logger log = LoggerFactory.getLogger(ConsentEvaluatorTest.class);
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

    private InMemoryConsentRepository repository;
    private ConsentEvaluator evaluator;

    @BeforeEach
    void setUp() {
        repository = new InMemoryConsentRepository();
        evaluator = new ConsentEvaluator(repository, "consent-rules-v1");
    }

    private DecisionResult evaluateAndLog(String patientId, String requesterId, Purpose purpose) {
        DecisionResult result = evaluator.evaluate(patientId, requesterId, purpose, NOW);
        log.info("输入: patientId={}, requesterId={}, purpose={}, now={}", patientId, requesterId, purpose, NOW);
        log.info("判定依据: decision={}, reason={}, ruleVersion={}",
                result.decision(), result.reason(), result.ruleVersion());
        return result;
    }

    @Test
    void deniedWhenConsentRevoked() {
        repository.save(new Consent("C1", "P001", "doctor-A",
                Set.of(Purpose.TREATMENT),
                NOW.plus(30, ChronoUnit.DAYS),
                NOW.minus(1, ChronoUnit.DAYS))); // 已撤回

        DecisionResult result = evaluateAndLog("P001", "doctor-A", Purpose.TREATMENT);

        assertEquals(Decision.DENIED_REVOKED, result.decision());
        assertTrue(result.reason().contains("revoked"));
    }

    @Test
    void deniedWhenConsentExpired() {
        repository.save(new Consent("C2", "P001", "doctor-A",
                Set.of(Purpose.TREATMENT),
                NOW.minus(1, ChronoUnit.HOURS), // 已过期
                null));

        DecisionResult result = evaluateAndLog("P001", "doctor-A", Purpose.TREATMENT);

        assertEquals(Decision.DENIED_EXPIRED, result.decision());
        assertTrue(result.reason().contains("expired"));
    }

    @Test
    void deniedWhenPurposeOutOfScope() {
        repository.save(new Consent("C3", "P001", "marketer-B",
                Set.of(Purpose.BILLING),
                NOW.plus(30, ChronoUnit.DAYS),
                null));

        DecisionResult result = evaluateAndLog("P001", "marketer-B", Purpose.MARKETING);

        assertEquals(Decision.DENIED_PURPOSE_MISMATCH, result.decision());
        assertTrue(result.reason().contains("not covered"));
    }

    @Test
    void deniedWhenNoConsent() {
        DecisionResult result = evaluateAndLog("P001", "stranger-C", Purpose.TREATMENT);

        assertEquals(Decision.DENIED_NO_CONSENT, result.decision());
    }

    @Test
    void allowedWhenConsentValid() {
        repository.save(new Consent("C4", "P001", "doctor-A",
                Set.of(Purpose.TREATMENT, Purpose.BILLING),
                NOW.plus(30, ChronoUnit.DAYS),
                null));

        DecisionResult result = evaluateAndLog("P001", "doctor-A", Purpose.TREATMENT);

        assertEquals(Decision.ALLOWED, result.decision());
        assertTrue(result.allowed());
    }

    @Test
    void revokedTakesPrecedenceOverExpired() {
        // 既撤回又过期时，应报撤回（患者主动行为语义更强）
        repository.save(new Consent("C5", "P001", "doctor-A",
                Set.of(Purpose.TREATMENT),
                NOW.minus(1, ChronoUnit.HOURS),
                NOW.minus(2, ChronoUnit.HOURS)));

        DecisionResult result = evaluateAndLog("P001", "doctor-A", Purpose.TREATMENT);

        assertEquals(Decision.DENIED_REVOKED, result.decision());
    }
}
