package com.github.feng111627.healthcareconsentgatewayjava.history;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 访问快照存储（内存模拟）：按审计序号保存访问时刻快照，支持并发读写。 */
@Component
public class HistoryStore {

    private final ConcurrentMap<Long, AccessSnapshot> snapshots = new ConcurrentHashMap<>();

    public void save(AccessSnapshot snapshot) {
        snapshots.putIfAbsent(snapshot.sequence(), snapshot);
    }

    public Optional<AccessSnapshot> findBySequence(long sequence) {
        return Optional.ofNullable(snapshots.get(sequence));
    }

    public int size() {
        return snapshots.size();
    }
}
