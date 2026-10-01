package com.github.feng111627.healthcareconsentgatewayjava.config;

import com.github.feng111627.healthcareconsentgatewayjava.domain.ConsentGrant;
import com.github.feng111627.healthcareconsentgatewayjava.domain.Purpose;
import com.github.feng111627.healthcareconsentgatewayjava.model.CompositeFieldRule;
import com.github.feng111627.healthcareconsentgatewayjava.model.MaskingRuleSet;
import com.github.feng111627.healthcareconsentgatewayjava.model.MaskStrategy;
import com.github.feng111627.healthcareconsentgatewayjava.model.PatientRecord;
import com.github.feng111627.healthcareconsentgatewayjava.policy.PolicyService;
import com.github.feng111627.healthcareconsentgatewayjava.repo.ConsentRepository;
import com.github.feng111627.healthcareconsentgatewayjava.repo.PatientDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 本地内存演示数据：患者、授权、初始策略（v1）。 */
@Configuration
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    public static final String PATIENT_ID = "P-1001";
    public static final String REQ_CLINIC = "clinic-A";
    public static final String REQ_INSURER = "insurer-B";
    public static final String REQ_RESEARCH = "lab-C";
    public static final String REQ_REVOKED = "partner-D";
    public static final String REQ_EXPIRED = "legacy-E";
    public static final String POLICY_V1 = "policy-v1";

    @Bean
    CommandLineRunner initDemoData(ConsentRepository consents,
                                   PatientDataRepository patients,
                                   PolicyService policies) {
        return args -> {
            policies.publish(POLICY_V1, maskingV1());
            log.info("已发布初始策略 {}", POLICY_V1);

            patients.save(new PatientRecord(PATIENT_ID, patientData()));
            log.info("已载入演示患者 {}", PATIENT_ID);

            Instant now = Instant.now();
            Instant granted = now.minusSeconds(30);

            // 诊所：治疗用途，长期有效
            consents.save(new ConsentGrant("C-1", PATIENT_ID, REQ_CLINIC,
                    List.of(Purpose.TREATMENT, Purpose.PAYMENT),
                    null, granted, false, null));
            // 保险：支付用途，长期有效
            consents.save(new ConsentGrant("C-2", PATIENT_ID, REQ_INSURER,
                    List.of(Purpose.PAYMENT),
                    null, granted, false, null));
            // 实验室：仅研究用途（可用于演示越权：拿 TREATMENT 访问）
            consents.save(new ConsentGrant("C-3", PATIENT_ID, REQ_RESEARCH,
                    List.of(Purpose.RESEARCH),
                    null, granted, false, null));
            // 已撤回：撤回时刻固定为 10 秒前
            consents.save(new ConsentGrant("C-4", PATIENT_ID, REQ_REVOKED,
                    List.of(Purpose.TREATMENT),
                    null, granted.minusSeconds(60), true, now.minusSeconds(10)));
            // 已过期：过期时刻固定为 5 秒前
            consents.save(new ConsentGrant("C-5", PATIENT_ID, REQ_EXPIRED,
                    List.of(Purpose.TREATMENT),
                    now.minusSeconds(5), granted.minusSeconds(600), false, null));

            log.info("已载入 5 条演示授权（含已撤回/已过期/越权样本）");
        };
    }

    private static Map<String, Object> patientData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("patientId", PATIENT_ID);
        data.put("firstName", "San");
        data.put("lastName", "Zhang");
        // 拼接字段：由 first/last 拼出，脱敏规则会用脱敏后的成分重组
        data.put("fullName", "San Zhang");
        data.put("idCardNo", "110101199001011234");

        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("email", "zhangsan@example.com");
        contact.put("phone", "13800138000");
        data.put("contact", contact);

        data.put("diagnosis", "hypertension stage 2");

        Map<String, Object> emergency = new LinkedHashMap<>();
        emergency.put("name", "Si Li");
        emergency.put("phone", "13911112222");
        data.put("emergencyContact", emergency);

        return data;
    }

    /** v1 脱敏规则：姓名部分打码、证件整段遮蔽、邮箱/电话专用策略、拼接字段重组。 */
    public static MaskingRuleSet maskingV1() {
        return new MaskingRuleSet(POLICY_V1,
                new LinkedHashMap<>(Map.of(
                        "firstName", MaskStrategy.PARTIAL,
                        "lastName", MaskStrategy.PARTIAL,
                        "idCardNo", MaskStrategy.REDACT,
                        "email", MaskStrategy.EMAIL,
                        "phone", MaskStrategy.PHONE,
                        "name", MaskStrategy.PARTIAL)),
                List.of(new CompositeFieldRule("fullName", "{0} {1}",
                        List.of("firstName", "lastName"), MaskStrategy.PARTIAL)));
    }
}
