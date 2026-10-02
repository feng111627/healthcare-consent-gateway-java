package com.github.feng111627.healthcareconsentgatewayjava.gateway;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 患者数据存储（本地内存模拟，含嵌套结构，用于验证递归脱敏）。
 * 姓名/联系方式/地址同时以「成分字段」存放（lastName+firstName、areaCode+phoneNumber、
 * province+city+street），由脱敏策略定义为拼接字段，验证组合输出不泄露成分原文。
 */
@Component
public class PatientRecordStore {

    private final Map<String, Map<String, Object>> store = new ConcurrentHashMap<>();

    public PatientRecordStore() {
        save("P001", Map.ofEntries(
                Map.entry("patientId", "P001"),
                Map.entry("name", "张伟"),
                Map.entry("lastName", "张"),
                Map.entry("firstName", "伟"),
                Map.entry("idNumber", "110101199003077799"),
                Map.entry("phone", "13812345678"),
                Map.entry("areaCode", "010"),
                Map.entry("phoneNumber", "67890123"),
                Map.entry("email", "zhangwei@example.com"),
                Map.entry("address", "北京市朝阳区建国路100号"),
                Map.entry("province", "北京市"),
                Map.entry("city", "朝阳区"),
                Map.entry("street", "建国路100号"),
                Map.entry("emergencyContact", Map.ofEntries(
                        Map.entry("name", "张强"),
                        Map.entry("lastName", "张"),
                        Map.entry("firstName", "强"),
                        Map.entry("phone", "13987654321"),
                        Map.entry("relation", "兄弟"))),
                Map.entry("visits", List.of(
                        Map.of("visitId", "V001", "diagnosis", "高血压",
                                "doctor", Map.ofEntries(
                                        Map.entry("name", "李医生"),
                                        Map.entry("lastName", "李"),
                                        Map.entry("firstName", "医生"),
                                        Map.entry("phone", "13711112222"))),
                        Map.of("visitId", "V002", "diagnosis", "糖尿病",
                                "doctor", Map.ofEntries(
                                        Map.entry("name", "王医生"),
                                        Map.entry("lastName", "王"),
                                        Map.entry("firstName", "医生"),
                                        Map.entry("phone", "13733334444")))))));
        save("P002", Map.ofEntries(
                Map.entry("patientId", "P002"),
                Map.entry("name", "刘洋"),
                Map.entry("lastName", "刘"),
                Map.entry("firstName", "洋"),
                Map.entry("idNumber", "310104198811223344"),
                Map.entry("phone", "13655556666"),
                Map.entry("areaCode", "021"),
                Map.entry("phoneNumber", "56789012"),
                Map.entry("email", "liuyang@example.com"),
                Map.entry("address", "上海市徐汇区漕溪路200号"),
                Map.entry("province", "上海市"),
                Map.entry("city", "徐汇区"),
                Map.entry("street", "漕溪路200号"),
                Map.entry("emergencyContact", Map.ofEntries(
                        Map.entry("name", "刘敏"),
                        Map.entry("lastName", "刘"),
                        Map.entry("firstName", "敏"),
                        Map.entry("phone", "13677778888"),
                        Map.entry("relation", "配偶")))));
    }

    public void save(String patientId, Map<String, Object> record) {
        store.put(patientId, record);
    }

    public Optional<Map<String, Object>> findById(String patientId) {
        return Optional.ofNullable(store.get(patientId));
    }
}
