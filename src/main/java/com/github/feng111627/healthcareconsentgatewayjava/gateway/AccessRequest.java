package com.github.feng111627.healthcareconsentgatewayjava.gateway;

import com.github.feng111627.healthcareconsentgatewayjava.consent.Purpose;

public record AccessRequest(String requesterId, String patientId, Purpose purpose) {
}
