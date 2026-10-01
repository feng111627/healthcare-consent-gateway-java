package com.github.feng111627.healthcareconsentgatewayjava.policy;

import com.github.feng111627.healthcareconsentgatewayjava.model.MaskingRuleSet;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class InMemoryPolicyService implements PolicyService {

    private final ConcurrentMap<String, AccessPolicy> versions = new ConcurrentHashMap<>();
    private final AtomicReference<String> currentVersion = new AtomicReference<>();

    public InMemoryPolicyService() {
    }

    @Override
    public AccessPolicy current() {
        String v = currentVersion.get();
        if (v == null) {
            throw new IllegalStateException("no access policy published");
        }
        return versions.get(v);
    }

    @Override
    public AccessPolicy version(String version) {
        AccessPolicy policy = versions.get(version);
        if (policy == null) {
            throw new IllegalArgumentException("unknown policy version: " + version);
        }
        return policy;
    }

    @Override
    public AccessPolicy publish(String version, MaskingRuleSet ruleSet) {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(ruleSet, "ruleSet");
        AccessPolicy policy = new AccessPolicy(version, ruleSet);
        if (versions.putIfAbsent(version, policy) != null) {
            throw new IllegalArgumentException("policy version already exists: " + version);
        }
        currentVersion.set(version);
        return policy;
    }

    @Override
    public AccessPolicy activate(String version) {
        AccessPolicy policy = versions.get(version);
        if (policy == null) {
            throw new IllegalArgumentException("unknown policy version: " + version);
        }
        currentVersion.set(version);
        return policy;
    }
}
