package com.github.feng111627.healthcareconsentgatewayjava.repo;

import com.github.feng111627.healthcareconsentgatewayjava.domain.ConsentGrant;

import java.util.Optional;

public interface ConsentRepository {
    Optional<ConsentGrant> find(String requesterId, String patientId);
    void save(ConsentGrant grant);
}
