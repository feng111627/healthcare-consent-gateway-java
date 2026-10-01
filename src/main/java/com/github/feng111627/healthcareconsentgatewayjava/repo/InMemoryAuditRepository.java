package com.github.feng111627.healthcareconsentgatewayjava.repo;

import com.github.feng111627.healthcareconsentgatewayjava.model.AuditRecord;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 顺序存储哈希链记录。append 本身线程安全且保序；
 * “取上一条 hash -> 算当前 hash -> 追加”这一整段原子性由 AuditService 的锁保证。
 */
@Repository
public class InMemoryAuditRepository implements AuditRepository {

    private final List<AuditRecord> chain = new CopyOnWriteArrayList<>();

    @Override
    public AuditRecord append(AuditRecord record) {
        chain.add(record);
        return record;
    }

    @Override
    public List<AuditRecord> findAll() {
        return List.copyOf(chain);
    }

    /** 仅供 AuditService 在串行锁内取最新一条，计算 prevHash。 */
    AuditRecord latest() {
        return chain.isEmpty() ? null : chain.get(chain.size() - 1);
    }
}
