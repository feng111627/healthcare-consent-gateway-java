# Healthcare Consent Gateway

患者数据访问合规网关。核心目标是把三件事做扎实：

- **能不能看**：基于授权范围（consent）的访问判定；
- **看到什么**：返回前按可配置策略脱敏；
- **看没看过**：可追溯、防篡改的访问留痕。

数据全部用本地内存模拟，不依赖任何外部系统或真实凭据。

## 架构

```
api/GatewayController        REST 入口
gateway/PatientDataGateway   编排：判定 -> 留痕 -> 脱敏返回
consent/ConsentEvaluator     授权判定（区分拒绝原因）
masking/MaskingService       递归脱敏（嵌套 Map/List）
audit/AuditLog               哈希链审计日志（并发安全、可校验）
```

## 授权规则（能不能看）

每次读取患者数据，`ConsentEvaluator` 结合请求方用途（purpose）判定，结果明确区分：

| Decision | 含义 |
|---|---|
| `ALLOWED` | 授权有效且用途在范围内 |
| `DENIED_NO_CONSENT` | 找不到该患者-请求方的授权记录 |
| `DENIED_REVOKED` | 授权已被患者撤回 |
| `DENIED_EXPIRED` | 授权已过有效期 |
| `DENIED_PURPOSE_MISMATCH` | 用途超出授权范围 |
| `ERROR_MASKING_FAILED` | 脱敏处理失败，数据被扣留（不带出原文） |

判定优先级：撤回 > 过期 > 用途匹配（撤回是患者主动行为，语义最强）。
判定规则版本由 `gateway.consent.rule-version` 配置，每次判定的版本会写入审计记录。

## 脱敏规则（看到什么）

策略可配置（`application.properties`）：

```properties
gateway.masking.version=masking-v1
gateway.masking.fields.name=PARTIAL      # 保留首尾：张伟 -> 张*
gateway.masking.fields.idNumber=HASH     # SHA-256 摘要：sha256:前12位
gateway.masking.fields.phone=PARTIAL     # 13812345678 -> 1***8
gateway.masking.fields.address=FULL      # 全打码：***
```

- `MaskingService` 对嵌套 Map / List **递归**处理，拼接（toString/序列化）后的响应里也不会漏出原文；
- 处理过程中任何异常都抛出 `MaskingException`，网关捕获后返回 `ERROR_MASKING_FAILED` 且 `data=null`，**绝不返回部分脱敏或原始数据**；
- 历史策略版本保留在注册表中（如内置的 `masking-v0`），可按版本取回复现当时的脱敏结果。

## 审计留痕（看没看过）

每次判定（无论允许还是拒绝）都追加一条 `AuditRecord`：

```
sequence | timestamp | requesterId | patientId | purpose | decision | reason
       | ruleVersion | maskingPolicyVersion | prevHash | hash
```

- **防篡改**：`hash = SHA-256(全部字段 + prevHash)`，构成哈希链；改动任意一条记录的任何字段，都会导致该记录及后续链条校验失败；
- **并发安全**：追加在锁内完成，并发写入不会破坏链的连续性（序号无空洞、prevHash 链接不断）；
- **校验方式**：`AuditLog.verify()` 重放整条链，逐条检查 prevHash 链接并重新计算 hash，返回第一条被篡改记录的序号，全部合法返回 `-1`；
- **可复现**：记录中的 `ruleVersion` 与 `maskingPolicyVersion` 指向判定当时生效的规则与策略版本，策略调整后历史记录仍能复现当时的判定依据。

## 本地验证

### 运行测试

```bash
mvn test
```

覆盖场景（测试日志会打印输入与判定依据）：

- `ConsentEvaluatorTest`：授权撤回 / 过期 / 越权 / 无授权 / 撤回优先于过期；
- `MaskingServiceTest`：嵌套结构递归脱敏、拼接输出无原文泄露、脱敏失败抛异常、历史策略版本复现；
- `AuditLogTest`：16 线程并发写入后链仍可校验、篡改记录被定位、完整链校验为 -1；
- `PatientDataGatewayTest`：端到端拒绝原因可区分、允许时返回脱敏数据、脱敏失败不泄露。

### 启动服务手动验证

```bash
mvn spring-boot:run
```

```bash
# 1. 登记一条有效授权（TREATMENT 用途，30 天后过期）
curl -X POST localhost:8080/api/consents -H 'Content-Type: application/json' -d '{
  "consentId":"C1","patientId":"P001","requesterId":"doctor-A",
  "purposes":["TREATMENT"],"expiresAt":"2026-10-31T00:00:00Z","revokedAt":null}'

# 2. 允许访问：返回脱敏后的数据（嵌套的紧急联系人、就诊记录也会被脱敏）
curl 'localhost:8080/api/patients/P001?requesterId=doctor-A&purpose=TREATMENT'

# 3. 越权访问：用途不在授权范围内 -> 403 DENIED_PURPOSE_MISMATCH
curl 'localhost:8080/api/patients/P001?requesterId=doctor-A&purpose=MARKETING'

# 4. 无授权访问 -> 403 DENIED_NO_CONSENT
curl 'localhost:8080/api/patients/P001?requesterId=stranger&purpose=TREATMENT'

# 5. 查看审计链与校验结果（intact=true 表示未被篡改）
curl localhost:8080/api/audit
curl localhost:8080/api/audit/verify
```

过期与撤回场景可通过登记 `expiresAt` 为过去时间、或 `revokedAt` 非空的授权来复现，
分别得到 `DENIED_EXPIRED` 与 `DENIED_REVOKED`。
