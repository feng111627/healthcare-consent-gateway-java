package com.github.feng111627.healthcareconsentgatewayjava.service;

import com.github.feng111627.healthcareconsentgatewayjava.domain.AccessDecision;
import com.github.feng111627.healthcareconsentgatewayjava.domain.AccessRequest;
import com.github.feng111627.healthcareconsentgatewayjava.model.AuditRecord;
import com.github.feng111627.healthcareconsentgatewayjava.model.IntegrityReport;
import com.github.feng111627.healthcareconsentgatewayjava.policy.PolicyService;
import com.github.feng111627.healthcareconsentgatewayjava.repo.AuditRepository;
import com.github.feng111627.healthcareconsentgatewayjava.security.AuditHasher;
import com.github.feng111627.healthcareconsentgatewayjava.security.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class DefaultAuditService implements AuditService {

    private static final Logger log = LoggerFactory.getLogger(DefaultAuditService.class);

    private final AuditRepository auditRepository;
    private final AuthorizationService authorizationService;
    private final PolicyService policyService;

    /**
     * 追加锁：把“取链尾 -> 计算 hash -> 落库”收成一个原子临界区。
     * 并发写入时 seq 连续、prevHash 不串链，链的可检测性不被并发破坏。
     */
    private final Object appendLock = new Object();

    public DefaultAuditService(AuditRepository auditRepository,
                               AuthorizationService authorizationService,
                               PolicyService policyService) {
        this.auditRepository = auditRepository;
        this.authorizationService = authorizationService;
        this.policyService = policyService;
    }

    @Override
    public AuditRecord record(AccessRequest request, AccessDecision decision,
                              boolean masked, String maskingErrorCode) {
        synchronized (appendLock) {
            List<AuditRecord> all = auditRepository.findAll();
            long seq = all.size() + 1;
            AuditRecord previous = all.isEmpty() ? null : all.get(all.size() - 1);
            String prevHash = previous == null ? Hashing.GENESIS_HASH : previous.recordHash();

            AuditRecord unsigned = new AuditRecord(
                    seq,
                    UUID.randomUUID().toString(),
                    Instant.now(),
                    request,
                    decision.reason(),
                    decision.basis(),
                    decision.basis() == null ? null : decision.basis().policyVersion(),
                    masked,
                    maskingErrorCode,
                    prevHash,
                    null);
            String recordHash = AuditHasher.hash(unsigned);
            AuditRecord stored = new AuditRecord(
                    unsigned.seq(), unsigned.requestId(), unsigned.timestamp(),
                    unsigned.request(), unsigned.decision(), unsigned.basis(),
                    unsigned.policyVersion(), unsigned.masked(), unsigned.maskingErrorCode(),
                    unsigned.prevHash(), recordHash);
            auditRepository.append(stored);

            log.info("审计留痕: seq={}, requestId={}, requesterId={}, patientId={}, "
                            + "purpose={}, decision={}, policyVersion={}, masked={}, "
                            + "maskingErrorCode={}, prevHash={}, recordHash={}",
                    stored.seq(), stored.requestId(),
                    request.requesterId(), request.patientId(), request.purpose(),
                    stored.decision(), stored.policyVersion(), stored.masked(),
                    stored.maskingErrorCode(), stored.prevHash(), stored.recordHash());
            return stored;
        }
    }

    @Override
    public List<AuditRecord> all() {
        return auditRepository.findAll();
    }

    @Override
    public IntegrityReport verifyChain() {
        return verifyChain(auditRepository.findAll());
    }

    @Override
    public IntegrityReport verifyChain(List<AuditRecord> records) {
        List<Long> broken = new ArrayList<>();
        List<String> details = new ArrayList<>();
        String expectedPrev = Hashing.GENESIS_HASH;
        long expectedSeq = 1;

        for (AuditRecord r : records) {
            boolean bad = false;
            if (r.seq() != expectedSeq) {
                details.add("seq=" + r.seq() + " expected seq=" + expectedSeq
                        + " (record inserted/removed/reordered)");
                bad = true;
            }
            if (!expectedPrev.equals(r.prevHash())) {
                details.add("seq=" + r.seq() + " prevHash mismatch (chain broken)");
                bad = true;
            }
            String recomputed = AuditHasher.hash(r);
            if (!recomputed.equals(r.recordHash())) {
                details.add("seq=" + r.seq()
                        + " recordHash mismatch (content tampered or hash recomputed)");
                bad = true;
            }
            if (bad) {
                broken.add(r.seq());
            }
            expectedPrev = r.recordHash();
            expectedSeq++;
        }

        boolean valid = broken.isEmpty();
        IntegrityReport report = new IntegrityReport(valid, records.size(),
                List.copyOf(broken), List.copyOf(details));
        log.info("审计链校验: valid={}, recordCount={}, brokenAt={}",
                valid, report.recordCount(), report.brokenAtSeq());
        return report;
    }

    @Override
    public ReplayResult replay(String requestId) {
        AuditRecord found = auditRepository.findAll().stream()
                .filter(r -> r.requestId().equals(requestId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "no audit record for requestId: " + requestId));
        // 用记录中的依据快照 + 当时策略版本复现；历史策略不可变，因此结论与当时一致。
        policyService.version(found.policyVersion());
        AccessDecision reproduced =
                authorizationService.replay(found.basis(), found.policyVersion());
        return new ReplayResult(found, reproduced);
    }
}
