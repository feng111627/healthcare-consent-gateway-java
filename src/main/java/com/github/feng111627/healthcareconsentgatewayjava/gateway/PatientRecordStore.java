package com.github.feng111627.healthcareconsentgatewayjava.gateway;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 患者数据存储（本地内存模拟）。
 * 除常规敏感字段外，还包含「拼接字段」的各成分原始段：
 * - firstName/lastName 合成 fullName（顶层、嵌套对象、列表均有）；
 * - areaCode/contactNumber 合成 contactPhone（不同成分用不同脱敏策略）；
 * - country/city/district/street 合成 fullAddress（含列表项中的多段合并）。
 */
@Component
public class PatientRecordStore {

    private final Map<String, Map<String, Object>> store = new ConcurrentHashMap<>();

    public PatientRecordStore() {
        save("P001", record(
                "P001", "张伟", "110101199003077799", "13812345678",
                "zhangwei@example.com", "北京市朝阳区建国路100号",
                "张", "伟", "0086", "13812345678",
                "中国", "北京市", "朝阳区", "建国路100号",
                Map.of(
                        "name", "张强",
                        "phone", "13987654321",
                        "relation", "兄弟",
                        "firstName", "张",
                        "lastName", "强",
                        "areaCode", "0086",
                        "contactNumber", "13987654321"),
                List.of(
                        Map.of("visitId", "V001", "diagnosis", "高血压",
                                "doctor", Map.of("name", "李医生", "phone", "13711112222",
                                        "firstName", "李", "lastName", "医生",
                                        "areaCode", "0086", "contactNumber", "13711112222"),
                                "country", "中国", "city", "北京市",
                                "district", "朝阳区", "street", "第一医院"),
                        Map.of("visitId", "V002", "diagnosis", "糖尿病",
                                "doctor", Map.of("name", "王医生", "phone", "13733334444",
                                        "firstName", "王", "lastName", "医生",
                                        "areaCode", "0086", "contactNumber", "13733334444"),
                                "country", "中国", "city", "北京市",
                                "district", "海淀区", "street", "第二医院"))));
        save("P002", record(
                "P002", "刘洋", "310104198811223344", "13655556666",
                "liuyang@example.com", "上海市徐汇区漕溪路200号",
                "刘", "洋", "0086", "13655556666",
                "中国", "上海市", "徐汇区", "漕溪路200号",
                Map.of(
                        "name", "刘敏",
                        "phone", "13677778888",
                        "relation", "配偶",
                        "firstName", "刘",
                        "lastName", "敏",
                        "areaCode", "0086",
                        "contactNumber", "13677778888"),
                List.of()));
    }

    private static Map<String, Object> record(
            String patientId, String name, String idNumber, String phone, String email,
            String address, String firstName, String lastName,
            String areaCode, String contactNumber,
            String country, String city, String district, String street,
            Map<String, Object> emergencyContact, List<Map<String, Object>> visits) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("patientId", patientId);
        r.put("name", name);
        r.put("idNumber", idNumber);
        r.put("phone", phone);
        r.put("email", email);
        r.put("address", address);
        r.put("firstName", firstName);
        r.put("lastName", lastName);
        r.put("areaCode", areaCode);
        r.put("contactNumber", contactNumber);
        r.put("country", country);
        r.put("city", city);
        r.put("district", district);
        r.put("street", street);
        r.put("emergencyContact", emergencyContact);
        r.put("visits", visits);
        return r;
    }

    public void save(String patientId, Map<String, Object> record) {
        store.put(patientId, record);
    }

    public Optional<Map<String, Object>> findById(String patientId) {
        return Optional.ofNullable(store.get(patientId));
    }
}
