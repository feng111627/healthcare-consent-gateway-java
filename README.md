# Healthcare Consent Gateway

患者数据访问合规网关。核心目标是把三件事做扎实：

- **能不能看**：基于授权范围（consent）的访问判定，可精细到「只放开部分数据维度」；
- **看到什么**：返回前按可配置策略脱敏，拼接字段（由多个成分合成的展示值）同样不泄露成分原文；
- **看没看过**：可追溯、防篡改的访问留痕，且任意一条历史记录都能按当时生效的规则/策略**原样重放**。

数据全部用本地内存模拟，不依赖任何外部系统或真实凭据。

## 架构

```
api/GatewayController        REST 入口（含历史复现端点）
gateway/PatientDataGateway   编排：判定 -> 脱敏 -> 范围裁剪 -> 留痕返回
consent/ConsentRule(V1/V2)   版本化授权规则（v2 支持数据维度级授权）
consent/ConsentRuleRegistry  规则版本注册表（只增不改）
consent/ScopeFilter          范围裁剪：未授权维度替换为 [UNAUTHORIZED]
masking/MaskingService       递归脱敏 + 拼接字段（成分先脱敏再组合）
masking/CompositeField       拼接字段定义（输出字段、成分、分隔符）
audit/AuditLog               哈希链审计日志（并发安全、可校验、含授权快照与当时返回内容）
replay/ReplayService         历史复现：按记录中的版本重放判定结论与脱敏结果
```

## 授权规则（能不能看）

每次读取患者数据，`ConsentEvaluator` 按当前配置的规则版本（`gateway.consent.rule-version`）
委派给对应版本的 `ConsentRule` 判定，结果明确区分：

| Decision | 含义 |
|---|---|
| `ALLOWED` | 授权有效、用途在范围内，且（v2 下）覆盖全部数据维度 |
| `ALLOWED_PARTIAL` | 授权有效但只覆盖部分数据维度：授权部分返回，未授权部分扣留 |
| `DENIED_NO_CONSENT` | 找不到该患者-请求方的授权记录 |
| `DENIED_REVOKED` | 授权已被患者撤回 |
| `DENIED_EXPIRED` | 授权已过有效期 |
| `DENIED_PURPOSE_MISMATCH` | 用途超出授权范围 |
| `DENIED_NO_SCOPE` | 授权有效但未授予任何数据维度（v2，等同于整体拒绝） |
| `ERROR_MASKING_FAILED` | 脱敏处理失败，数据被扣留（不带出原文） |

判定优先级：撤回 > 过期 > 用途匹配 > 维度判定（撤回是患者主动行为，语义最强）。

### 范围授权（部分可见，v2 起）

授权记录可携带 `scopes`（数据维度集合）。v2 规则内置「字段 -> 维度」映射
（随规则版本固化，见 `ConsentRuleV2.FIELD_SCOPES`）：

| 维度 | 字段 |
|---|---|
| `identity` | name, idNumber, lastName, firstName, fullName |
| `contact` | phone, email, areaCode, phoneNumber, contactPhone, emergencyContact |
| `address` | address, province, city, street, fullAddress |
| `medical` | visits |

- 覆盖全部维度 -> `ALLOWED`，返回全部；
- 只覆盖部分维度 -> `ALLOWED_PARTIAL`：授权维度的字段照常（脱敏后）返回，
  未授权维度的字段**整体替换为 `[UNAUTHORIZED]` 标记**（含其子树，如整个 `visits` 列表），
  不是静默省略、更不会带出原文；未纳入维度映射的字段（如 `patientId`）视为非敏感元数据，始终可见；
- 一个维度都没授予 -> `DENIED_NO_SCOPE`，整体拒绝。

`ALLOWED_PARTIAL` 与 `DENIED_*` 在响应和审计记录中都是不同类别，审计里能直接看出
「部分可见」还是「整体拒绝」。

## 脱敏规则（看到什么）

策略可配置（`application.properties`）：

```properties
gateway.masking.version=masking-v1
gateway.masking.fields.name=PARTIAL      # 保留首尾：张伟 -> 张*
gateway.masking.fields.idNumber=HASH     # SHA-256 摘要：sha256:前12位
gateway.masking.fields.address=FULL      # 全打码：***
```

- `MaskingService` 对嵌套 Map / List **递归**处理，拼接（toString/序列化）后的响应里也不会漏出原文；
- 处理过程中任何异常都抛出 `MaskingException`，网关捕获后返回 `ERROR_MASKING_FAILED` 且 `data=null`，**绝不返回部分脱敏或原始数据**；
- 历史策略版本保留在注册表中（如内置的 `masking-v0`），`publishPolicy` 发布新版本时
  同名版本拒绝覆盖，保证旧版本永远可按版本号取回。

### 拼接字段（composite fields）

有些展示字段由多段内容拼成：姓名 = 姓 + 名、联系方式 = 区号 + 号码、地址 = 多段合并。
这类字段在策略中声明：

```properties
gateway.masking.composites.fullName=lastName,firstName
gateway.masking.composites.contactPhone=areaCode,phoneNumber
gateway.masking.composites.contactPhone.separator=-
gateway.masking.composites.fullAddress=province,city,street
```

处理规则：

1. **成分先各自按自己的策略脱敏，脱敏结果再组合**——绝不先把原文拼起来再处理。
   各成分策略可以不同（如 `province=PARTIAL, city=HASH, street=FULL`），
   组合值形如 `北***市sha256:…***`，任何成分原文都不会出现在组合值里；
2. 在**任意层级**生效：顶层字段、嵌套对象（如 `emergencyContact`）、
   列表元素（如 `visits[].doctor`）中的成分都会先脱敏再组合；
3. 成分不全时**跳过该拼接字段**，不会输出拼了一半的原文；
4. 拼接定义随策略版本固化，历史记录重放时按当时版本的定义复现。

## 审计留痕（看没看过）

每次访问（无论允许、部分允许还是拒绝）都追加一条 `AuditRecord`：

```
sequence | timestamp | requesterId | patientId | purpose | decision | reason
       | ruleVersion | maskingPolicyVersion | consentSnapshot | maskedPayload
       | prevHash | hash
```

- **防篡改**：`hash = SHA-256(全部字段 + prevHash)`，构成哈希链；改动任意一条记录的任何字段，
  都会导致该记录及后续链条校验失败；
- **并发安全**：追加在锁内完成，并发写入不会破坏链的连续性（序号无空洞、prevHash 链接不断）；
- **校验方式**：`AuditLog.verify()` 重放整条链，返回第一条被篡改记录的序号（全部合法返回 `-1`）；
  `AuditLog.verifyRecord(seq)` 校验单条记录（自身哈希 + 与前序的链接）；
- **自包含**：`consentSnapshot` 保存判定所依据的授权快照（规范化字符串），
  `maskedPayload` 保存当时实际返回的脱敏结果（已脱敏，可安全留痕），
  两者都受哈希链保护——重放不依赖当前授权库与当前数据状态。

## 历史复现（重放）

对任意一条审计记录，`ReplayService.replay(sequence)`（或 `GET /api/audit/{sequence}/replay`）
按它**当时生效**的规则与策略版本重放：

1. 先 `verifyRecord` 确认该记录未被篡改；
2. 按记录中的 `ruleVersion` 从注册表取回**当时版本**的规则引擎，
   用记录中的授权快照 + 记录时间戳**重算判定结论**（允许/拒绝及具体类别），与记录比对；
3. 当时返回的脱敏结果（`maskedPayload`）原样取出返回；
4. `consistent=true` 表示重算结论与记录完全一致。

**边界与保证**：

- 规则/策略新版本**只增不改**（`ConsentRuleRegistry.register` 与 `MaskingService.publishPolicy`
  都拒绝覆盖同名版本），所以发布新版本后重放旧记录**不会被新规则带跑**；
  例如同一授权在 v1 下判 `ALLOWED`、在 v2 下判 `DENIED_NO_SCOPE`，
  v1 时代的记录重放出来仍然是 `ALLOWED`；
- 新老记录共存于同一条哈希链，校验与重放互不影响；
- 重放不读取当前授权库：判定输入全部来自记录中的快照，授权后续变更不影响重放；
- 重放判定使用的是**记录时间戳**，授权过期判定与当时一致；
- `ERROR_MASKING_FAILED` 是运行时事件：重放时验证的是「当时的授权判定确实走到了允许」，
  失败结果本身以记录为准；
- 记录持续增长与并发访问下，重放与校验同样稳定（有并发用例覆盖）；
- 若记录引用的规则/策略版本在注册表中不存在（如部署缺了历史版本），重放会明确报错而不是给出错误结论。

## 本地验证

### 运行测试

```bash
mvn test
```

覆盖场景（测试日志会打印每条用例的输入与判定依据）：

- `ConsentEvaluatorTest`：授权撤回 / 过期 / 越权 / 无授权 / 撤回优先于过期；
- `MaskingServiceTest`：嵌套结构递归脱敏、拼接输出无原文泄露、脱敏失败抛异常、历史策略版本复现；
- `CompositeMaskingTest`：拼接字段成分各自脱敏后再组合、嵌套/列表中的拼接、
  组合值与序列化输出无成分原文、成分缺失时跳过；
- `ScopedAccessTest`：部分维度授权时只返回授权部分、未授权部分 `[UNAUTHORIZED]` 扣留、
  `ALLOWED_PARTIAL` 与 `DENIED_*` 在响应与审计中可区分、零维度授权整体拒绝；
- `ReplayServiceTest`：发布新规则/策略版本后旧记录重放结论与当时一致、新老记录共存、
  并发访问与记录增长下重放与校验稳定；
- `AuditLogTest`：16 线程并发写入后链仍可校验、篡改记录被定位、完整链校验为 -1；
- `PatientDataGatewayTest`：端到端拒绝原因可区分、允许时返回脱敏数据、脱敏失败不泄露。

### 启动服务手动验证

```bash
mvn spring-boot:run
```

```bash
# 1. 登记一条有效授权（TREATMENT 用途 + 全部数据维度，30 天后过期）
curl -X POST localhost:8080/api/consents -H 'Content-Type: application/json' -d '{
  "consentId":"C1","patientId":"P001","requesterId":"doctor-A",
  "purposes":["TREATMENT"],"scopes":["identity","contact","address","medical"],
  "expiresAt":"2026-10-31T00:00:00Z","revokedAt":null}'

# 2. 全量访问：返回脱敏后的数据，含拼接字段 fullName/contactPhone/fullAddress
curl 'localhost:8080/api/patients/P001?requesterId=doctor-A&purpose=TREATMENT'

# 3. 登记只开放 identity 维度的授权 -> ALLOWED_PARTIAL，其余维度 [UNAUTHORIZED]
curl -X POST localhost:8080/api/consents -H 'Content-Type: application/json' -d '{
  "consentId":"C2","patientId":"P001","requesterId":"analyst-B",
  "purposes":["RESEARCH"],"scopes":["identity"],
  "expiresAt":"2026-10-31T00:00:00Z","revokedAt":null}'
curl 'localhost:8080/api/patients/P001?requesterId=analyst-B&purpose=RESEARCH'

# 4. 越权/无授权访问 -> 403，拒绝原因可区分
curl 'localhost:8080/api/patients/P001?requesterId=doctor-A&purpose=MARKETING'
curl 'localhost:8080/api/patients/P001?requesterId=stranger&purpose=TREATMENT'

# 5. 查看审计链与校验结果（intact=true 表示未被篡改）
curl localhost:8080/api/audit
curl localhost:8080/api/audit/verify

# 6. 历史复现：重放第 0 条记录，consistent=true 表示与当时结论一致
curl localhost:8080/api/audit/0/replay
```

过期与撤回场景可通过登记 `expiresAt` 为过去时间、或 `revokedAt` 非空的授权来复现，
分别得到 `DENIED_EXPIRED` 与 `DENIED_REVOKED`。
