package com.github.feng111627.healthcareconsentgatewayjava.consent;

import java.util.Optional;

/** 授权存储（本地内存模拟）。 */
public interface ConsentRepository {
    Optional<Consent> findByPatientAndRequester(String patientId, String requesterId);
    void save(Consent consent);
}
