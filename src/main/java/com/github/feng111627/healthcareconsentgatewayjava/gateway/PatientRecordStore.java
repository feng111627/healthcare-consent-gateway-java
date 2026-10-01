package com.github.feng111627.healthcareconsentgatewayjava.gateway;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 患者数据存储（本地内存模拟，含嵌套结构，用于验证递归脱敏）。 */
@Component
public class PatientRecordStore {

    private final Map<String, Map<String, Object>> store = new ConcurrentHashMap<>();

    public PatientRecordStore() {
        save("P001", Map.of(
                "patientId", "P001",
                "name", "张伟",
                "idNumber", "110101199003077799",
                "phone", "13812345678",
                "email", "zhangwei@example.com",
                "address", "北京市朝阳区建国路100号",
                "emergencyContact", Map.of(
                        "name", "张强",
                        "phone", "13987654321",
                        "relation", "兄弟"),
                "visits", List.of(
                        Map.of("visitId", "V001", "diagnosis", "高血压",
                                "doctor", Map.of("name", "李医生", "phone", "13711112222")),
                        Map.of("visitId", "V002", "diagnosis", "糖尿病",
                                "doctor", Map.of("name", "王医生", "phone", "13733334444")))));
        save("P002", Map.of(
                "patientId", "P002",
                "name", "刘洋",
                "idNumber", "310104198811223344",
                "phone", "13655556666",
                "email", "liuyang@example.com",
                "address", "上海市徐汇区漕溪路200号",
                "emergencyContact", Map.of(
                        "name", "刘敏",
                        "phone", "13677778888",
                        "relation", "配偶")));
    }

    public void save(String patientId, Map<String, Object> record) {
        store.put(patientId, record);
    }

    public Optional<Map<String, Object>> findById(String patientId) {
        return Optional.ofNullable(store.get(patientId));
    }
}
