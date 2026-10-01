package com.github.feng111627.healthcareconsentgatewayjava.security;

import com.github.feng111627.healthcareconsentgatewayjava.domain.AccessRequest;
import com.github.feng111627.healthcareconsentgatewayjava.domain.DecisionBasis;
import com.github.feng111627.healthcareconsentgatewayjava.domain.DecisionReason;
import com.github.feng111627.healthcareconsentgatewayjava.model.AuditRecord;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 审计记录哈希载荷构造。
 *
 * <p>设计要点：
 * <ul>
 *   <li>载荷用 {@link TreeMap} 排序后做 JSON 序列化，字段顺序确定、跨 JVM 可复算；</li>
 *   <li>所有参与判定的业务字段（含判定依据快照）都入哈希，改动任意一项都会失配；</li>
 *   <li>recordHash 本身不参与自身载荷；</li>
 *   <li>prevHash 入链，删除/重排/替换任意一条都会在链校验中暴露。</li>
 * </ul>
 */
public final class AuditHasher {

    private AuditHasher() {
    }

    public static String hash(AuditRecord r) {
        return Hashing.sha256Hex(canonicalPayload(r));
    }

    /** 仅依赖业务字段与 prevHash 复算 hash，供写入与校验共用。 */
    public static String canonicalPayload(AuditRecord r) {
        TreeMap<String, Object> root = new TreeMap<>();
        root.put("seq", r.seq());
        root.put("requestId", r.requestId());
        root.put("timestamp", r.timestamp() == null ? null : r.timestamp().toString());
        root.put("request", requestMap(r.request()));
        root.put("decision", r.decision() == null ? null : r.decision().name());
        root.put("basis", basisMap(r.basis()));
        root.put("policyVersion", r.policyVersion());
        root.put("masked", r.masked());
        root.put("maskingErrorCode", r.maskingErrorCode());
        root.put("prevHash", r.prevHash());
        return toJson(root);
    }

    private static Map<String, Object> requestMap(AccessRequest req) {
        TreeMap<String, Object> m = new TreeMap<>();
        if (req != null) {
            m.put("requesterId", req.requesterId());
            m.put("patientId", req.patientId());
            m.put("purpose", req.purpose() == null ? null : req.purpose().name());
        }
        return m;
    }

    private static Map<String, Object> basisMap(DecisionBasis b) {
        TreeMap<String, Object> m = new TreeMap<>();
        if (b != null) {
            m.put("consentId", b.consentId());
            m.put("consentVersion", b.consentVersion());
            m.put("revoked", b.revoked());
            m.put("revokedAt", b.revokedAt() == null ? null : b.revokedAt().toString());
            m.put("consentExpiresAt",
                    b.consentExpiresAt() == null ? null : b.consentExpiresAt().toString());
            List<String> purposes = b.allowedPurposes() == null
                    ? List.of()
                    : b.allowedPurposes().stream()
                            .sorted()
                            .map(Enum::name)
                            .collect(Collectors.toList());
            m.put("allowedPurposes", purposes);
            m.put("requestedPurpose",
                    b.requestedPurpose() == null ? null : b.requestedPurpose().name());
            m.put("evaluatedAt", b.evaluatedAt() == null ? null : b.evaluatedAt().toString());
            m.put("policyVersion", b.policyVersion());
        }
        return m;
    }

    /**
     * 极简确定性 JSON 序列化（仅覆盖 String/Boolean/Number/Map/List/null）。
     * 审计载荷只包含这些类型，手写序列化可避免 ObjectMapper 配置漂移。
     */
    @SuppressWarnings("unchecked")
    static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(toJson(String.valueOf(e.getKey()))).append(':')
                        .append(toJson(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(toJson(item));
            }
            return sb.append(']').toString();
        }
        return quote(value.toString());
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
