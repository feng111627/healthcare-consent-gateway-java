package com.github.feng111627.healthcareconsentgatewayjava.repo;

import com.github.feng111627.healthcareconsentgatewayjava.model.PatientRecord;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
public class InMemoryPatientDataRepository implements PatientDataRepository {

    private final ConcurrentMap<String, PatientRecord> store = new ConcurrentHashMap<>();

    @Override
    public Optional<PatientRecord> findById(String patientId) {
        if (patientId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.get(patientId));
    }

    @Override
    public void save(PatientRecord record) {
        store.put(record.patientId(), record);
    }
}
