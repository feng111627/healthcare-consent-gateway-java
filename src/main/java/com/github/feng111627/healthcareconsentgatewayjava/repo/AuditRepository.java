package com.github.feng111627.healthcareconsentgatewayjava.repo;

import com.github.feng111627.healthcareconsentgatewayjava.model.AuditRecord;

import java.util.List;

public interface AuditRepository {
    /** 顺序追加，线程安全；返回已落库的记录（带最终 seq/hash）。 */
    AuditRecord append(AuditRecord record);
    List<AuditRecord> findAll();
}
