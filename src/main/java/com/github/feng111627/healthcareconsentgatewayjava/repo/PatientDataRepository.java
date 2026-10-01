package com.github.feng111627.healthcareconsentgatewayjava.repo;

import com.github.feng111627.healthcareconsentgatewayjava.model.PatientRecord;

import java.util.Optional;

public interface PatientDataRepository {
    Optional<PatientRecord> findById(String patientId);
    void save(PatientRecord record);
}
