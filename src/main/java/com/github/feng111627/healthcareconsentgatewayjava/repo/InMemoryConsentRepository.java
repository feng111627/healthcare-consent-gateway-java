package com.github.feng111627.healthcareconsentgatewayjava.repo;

import com.github.feng111627.healthcareconsentgatewayjava.domain.ConsentGrant;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
public class InMemoryConsentRepository implements ConsentRepository {

    private final ConcurrentMap<String, ConsentGrant> store = new ConcurrentHashMap<>();

    private static String key(String requesterId, String patientId) {
        return requesterId + "|" + patientId;
    }

    @Override
    public Optional<ConsentGrant> find(String requesterId, String patientId) {
        if (requesterId == null || patientId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.get(key(requesterId, patientId)));
    }

    @Override
    public void save(ConsentGrant grant) {
        store.put(key(grant.requesterId(), grant.patientId()), grant);
    }
}
