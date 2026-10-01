package com.github.feng111627.healthcareconsentgatewayjava.consent;

import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryConsentRepository implements ConsentRepository {

    private final Map<String, Consent> store = new ConcurrentHashMap<>();

    private static String key(String patientId, String requesterId) {
        return patientId + "|" + requesterId;
    }

    @Override
    public Optional<Consent> findByPatientAndRequester(String patientId, String requesterId) {
        return Optional.ofNullable(store.get(key(patientId, requesterId)));
    }

    @Override
    public void save(Consent consent) {
        store.put(key(consent.getPatientId(), consent.getRequesterId()), consent);
    }
}
