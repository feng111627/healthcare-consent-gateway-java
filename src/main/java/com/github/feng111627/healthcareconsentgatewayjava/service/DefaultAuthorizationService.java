package com.github.feng111627.healthcareconsentgatewayjava.service;

import com.github.feng111627.healthcareconsentgatewayjava.domain.AccessDecision;
import com.github.feng111627.healthcareconsentgatewayjava.domain.AccessRequest;
import com.github.feng111627.healthcareconsentgatewayjava.domain.ConsentGrant;
import com.github.feng111627.healthcareconsentgatewayjava.domain.DecisionBasis;
import com.github.feng111627.healthcareconsentgatewayjava.domain.DecisionReason;
import com.github.feng111627.healthcareconsentgatewayjava.policy.PolicyService;
import com.github.feng111627.healthcareconsentgatewayjava.repo.ConsentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
public class DefaultAuthorizationService implements AuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(DefaultAuthorizationService.class);

    private final ConsentRepository consentRepository;
    private final PolicyService policyService;

    public DefaultAuthorizationService(ConsentRepository consentRepository,
                                       PolicyService policyService) {
        this.consentRepository = consentRepository;
        this.policyService = policyService;
    }

    @Override
    public AccessDecision evaluate(AccessRequest request, Instant at) {
        if (request == null) {
            throw new IllegalArgumentException("access request must not be null");
        }
        Instant now = at != null ? at : Instant.now();
        String policyVersion = policyService.current().version();

        Optional<ConsentGrant> found =
                consentRepository.find(request.requesterId(), request.patientId());

        AccessDecision decision = found
                .map(grant -> {
                    DecisionBasis basis =
                            DecisionBasis.of(grant, request.purpose(), now, policyVersion);
                    return decide(basis);
                })
                .orElseGet(() -> decide(
                        DecisionBasis.empty(request.purpose(), now, policyVersion)));

        log.info("授权判定输入: requesterId={}, patientId={}, purpose={}, at={} | "
                        + "依据: consentId={}, revoked={}, revokedAt={}, expiresAt={}, "
                        + "allowedPurposes={}, policyVersion={} | 结论: {} - {}",
                request.requesterId(), request.patientId(), request.purpose(), now,
                decision.basis().consentId(), decision.basis().revoked(),
                decision.basis().revokedAt(), decision.basis().consentExpiresAt(),
                decision.basis().allowedPurposes(), decision.basis().policyVersion(),
                decision.reason(), decision.message());
        return decision;
    }

    @Override
    public AccessDecision replay(DecisionBasis basis, String policyVersion) {
        if (basis == null) {
            throw new IllegalArgumentException("decision basis must not be null");
        }
        // 历史复现只依赖当时留下的快照与当时的策略版本，不读取授权当前状态。
        policyService.version(policyVersion);
        AccessDecision decision = decide(basis);
        log.info("历史判定复现: request purpose={}, evaluatedAt={} | 依据快照: consentId={}, "
                        + "revoked={}, revokedAt={}, expiresAt={}, allowedPurposes={}, "
                        + "policyVersion={} | 复现结论: {} - {}",
                basis.requestedPurpose(), basis.evaluatedAt(), basis.consentId(),
                basis.revoked(), basis.revokedAt(), basis.consentExpiresAt(),
                basis.allowedPurposes(), policyVersion, decision.reason(), decision.message());
        return decision;
    }

    /**
     * 判定优先级（互斥、可区分）：
     * 无授权 {@literal ->} 已撤回 {@literal ->} 已过期 {@literal ->} 用途越权 {@literal ->} 放行。
     */
    private AccessDecision decide(DecisionBasis b) {
        if (b.consentId() == null) {
            return new AccessDecision(false, DecisionReason.CONSENT_NOT_FOUND,
                    "no consent exists for this requester/patient pair", b);
        }
        if (b.revoked()) {
            return new AccessDecision(false, DecisionReason.CONSENT_REVOKED,
                    "consent was revoked at " + b.revokedAt(), b);
        }
        if (b.consentExpiresAt() != null && !b.evaluatedAt().isBefore(b.consentExpiresAt())) {
            return new AccessDecision(false, DecisionReason.CONSENT_EXPIRED,
                    "consent expired at " + b.consentExpiresAt(), b);
        }
        if (b.allowedPurposes() == null || !b.allowedPurposes().contains(b.requestedPurpose())) {
            return new AccessDecision(false, DecisionReason.OUT_OF_SCOPE,
                    "purpose " + b.requestedPurpose() + " is outside the granted scope "
                            + b.allowedPurposes(), b);
        }
        return new AccessDecision(true, DecisionReason.GRANTED,
                "access allowed for purpose " + b.requestedPurpose(), b);
    }
}
