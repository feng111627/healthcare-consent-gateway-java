# Healthcare Consent Gateway

患者数据访问合规网关。核心目标是把四件事做扎实：

- **能不能看**：基于授权（consent）的访问判定，拒绝原因可区分；
- **看到什么**：返回前按可配置策略脱敏，**含由多段成分拼出来的字段**；
- **能看多少**：支持按数据区段（scope）的部分授权——只返回授权部分；
- **看没看过 / 能不能重演**：防篡改留痕，并能按**当时生效的版本**把任意一次历史访问原样重放。

数据全部用本地内存模拟，不依赖任何外部系统或真实凭据。

## 架构

```
api/GatewayController        REST 入口（访问 / 授权登记 / 审计 / 重放 / 版本发布）
gateway/PatientDataGateway   编排：判定 -> 范围裁剪 -> 留痕/快照 -> 脱敏返回
gateway/DataProjector        先按范围剔除未授权区段，再递归脱敏（含拼接字段）
gateway/ResultHasher         返回结果的确定性哈希（访问与重放同一口径）
consent/ConsentRuleSet       授权规则版本接口（判定口径 + 字段->区段映射）
consent/ConsentRulesV1/V2    v1：撤回优先于过期；v2：过期优先于撤回
consent/ConsentRuleRegistry  规则版本注册表（保留全部版本，可发布切换）
masking/MaskingService       递归脱敏 + 拼接字段「各成分先脱敏再组合」
masking/CompositeField       拼接字段定义（输出名、成分字段、分隔符）
history/HistoryStore         访问时刻的授权/原始数据快照（并发安全）
replay/ReplayService         按审计记录中的版本重算判定与脱敏，并与留痕比对
audit/AuditLog               哈希链审计日志（并发安全、可校验）
```

## 授权规则（能不能看）

每次读取患者数据，`ConsentEvaluator` 交给当前生效的 `ConsentRuleSet` 判定，结果明确区分：

| Decision | 含义 | HTTP |
|---|---|---|
| `ALLOWED` | 授权有效、用途在范围内、范围全集 | 200 |
| `PARTIAL_ALLOWED` | 授权有效，但只放开部分数据区段，仅返回授权部分 | 200 |
| `DENIED_NO_CONSENT` | 找不到该患者-请求方的授权记录 | 403 |
| `DENIED_REVOKED` | 授权已被患者撤回 | 403 |
| `DENIED_EXPIRED` | 授权已过有效期 | 403 |
| `DENIED_PURPOSE_MISMATCH` | 用途超出授权范围 | 403 |
| `DENIED_OUT_OF_SCOPE` | 授权的数据区段与本次记录**完全无交集**（整体拒绝） | 403 |
| `ERROR_MASKING_FAILED` | 脱敏处理失败，数据被扣留（不带出原文） | 403 |

- **规则版本**：`consent-rules-v1`（撤回优先于过期）/ `consent-rules-v2`（过期优先于撤回）。
  判定结果携带规则版本写入审计；注册表保留全部版本，发布新版本只影响之后的访问。
- **判定基准时间**：历史重放时以**访问发生时刻**为基准重新判定过期/撤回，不会因今天的时间漂移。

## 范围授权（能看多少）

授权可声明数据区段 `scopes`；缺省/空/含 `"*"` 表示全集授权。内置区段：

- `IDENTITY`：`name`、`firstName/lastName`、`fullName`、`idNumber`
- `CONTACT`：`phone`、`areaCode/contactNumber`、`contactPhone`、`email`、`address`、
  `country/city/district/street`、`fullAddress`、`emergencyContact`
- `MEDICAL`：`visits`
- 未登记字段（如 `patientId`）视为开放字段，始终返回。

表现：

- **部分可见** `PARTIAL_ALLOWED`：授权区段的数据**正常返回（仍脱敏）**，未授权区段的顶层字段
  **直接不返回**（不是打码占位，也不带原文）；响应与审计都带 `deniedSections`。
- **范围全拒** `DENIED_OUT_OF_SCOPE`：授权区段与记录完全无交集（例如只有 `MEDICAL`
  授权却访问没有任何就诊记录的患者），`data=null`，与部分可见、其他拒绝原因都不同。
- 空集合字段（如 `visits=[]`）表示该类数据本次不存在，不参与区段交集判断。
- 字段→区段映射属于规则版本的一部分，因此范围判定也能被历史复现。

## 脱敏与拼接字段（看到什么）

常规策略可配置（`application.properties`）：`FULL=***`、`PARTIAL=保留首尾`、`HASH=sha256:` 前缀摘要。

**拼接字段**由多段成分合成，演示数据内置三组（顶层、嵌套对象、列表中都有）：

| 输出字段 | 成分 | 分隔符 | 示例（v1） |
|---|---|---|---|
| `fullName` | `firstName` + `lastName` | 空格 | `*** ***` / `欧* ***` |
| `contactPhone` | `areaCode`(HASH) + `contactNumber`(PARTIAL) | `-` | `sha256:e265...-1***1` |
| `fullAddress` | `country`+`city`+`district`+`street` | 无 | 各段 PARTIAL 后相连 |

规则：

1. **先对每个成分按它自己字段名的策略分别脱敏，再拼接**——绝不先把原文拼好再处理；
   不同成分有不同规则时各自生效（如区号 HASH、号码 PARTIAL）。
2. 拼好之后，**任何一个成分的原文与成分键本身都不出现在输出里**；
   顶层、嵌套 `Map`、`List` 里的 `Map` 一视同仁，递归处理，序列化/`toString` 后也不漏。
3. 成分不是标量，或某个成分**没有配置脱敏策略**（可能漏原文）时，**fail-close 抛 `MaskingException`**，
   网关返回 `ERROR_MASKING_FAILED` 且 `data=null`，绝不返回半成品或原文。
4. 拼接定义随脱敏策略版本走：`masking-v0`（全打码）、`masking-v1`（默认）、
   `masking-v2`（预置可发布，调整了若干字段策略）。

## 审计留痕与历史复现（看没看过 / 重演）

每次访问（允许、部分可见、拒绝）都追加一条哈希链记录，除原有字段外新增：

```
... | deniedSections（部分授权下被拒区段）| resultHash（当时返回结果的哈希）| prevHash | hash
```

- **防篡改**：链哈希覆盖包括 `deniedSections`、`resultHash` 在内的全部字段；
  追加在锁内完成，并发写入序号无空洞、链不断；`verify()` 返回首个被篡改序号，完好返回 -1。
- **复现依据**：记录里的 `ruleVersion` + `maskingPolicyVersion` + 访问时刻快照
  （`HistoryStore` 中的授权状态与原始数据深拷贝）。
- **重放语义**：`GET /api/audit/{sequence}/replay` 重新得到与当时**完全一致**的
  判定结论（允许/拒绝/具体类别、原因、被拒区段）与脱敏结果，并给出：
  - `decisionMatches`：判定类别/原因/区段是否与留痕一致；
  - `resultMatches`：重算的结果哈希是否等于记录中的 `resultHash`；
  - `mismatchDetail`：不一致时逐项说明差异。
- **边界**：
  - 发布新版本后重放旧记录仍走旧版本，结果不被新规则带跑；新老记录共存、互不影响；
  - 重放全程只读，不新增审计记录；在记录持续增长与并发访问下同样稳定
    （有 8 线程并发「访问 + 重放」200 次的用例守护）；
  - 整体拒绝类（无授权/撤回/过期/越权/范围全拒）没有原始数据快照，
    重放直接复现并核对其拒绝结论；
  - 若审计记录本身被篡改，`verify()` 先暴露；若缺少对应历史版本，重放会明确报错而不是静默用当前版本。

## 本地验证

### 运行测试

```bash
mvn test
```

共 32 个用例（测试日志会打印每条用例的输入与判定依据）：

- `ConsentEvaluatorTest`（6）：撤回 / 过期 / 越权 / 无授权 / 撤回优先；
- `MaskingServiceTest`（4）：嵌套递归脱敏、序列化无原文、失败抛异常、历史策略复现；
- **`CompositeMaskingTest`（4，新增）**：各成分先脱敏再拼、顶层/嵌套/列表无成分原文泄露、
  缺策略 fail-close、v0 历史拼接复现；
- **`ReplayServiceTest`（4，新增）**：规则升级后旧拒绝记录结论不变、脱敏策略升级后旧数据一致、
  新老记录共存链可校验、并发增长下重放稳定；
- **`ScopeAuthorizationTest`（5，新增）**：部分授权只返回授权区段、范围全拒可区分、
  全集/部分不同、部分授权可重放、审计区分 PARTIAL_ALLOWED 与 DENIED_OUT_OF_SCOPE；
- `AuditLogTest`（3）：16 线程并发写入、篡改定位、完整链 -1；
- `PatientDataGatewayTest`（5）/ `ApplicationTests`（1）：端到端与 Spring 上下文（无回归）。

### 启动服务手动验证

```bash
mvn spring-boot:run
```

```bash
# 1. 全集授权
curl -X POST localhost:8080/api/consents -H 'Content-Type: application/json' -d '{
  "consentId":"C-FULL","patientId":"P001","requesterId":"doctor-A",
  "purposes":["TREATMENT"],"expiresAt":"2026-11-30T00:00:00Z"}'
curl 'localhost:8080/api/patients/P001?requesterId=doctor-A&purpose=TREATMENT'
# -> ALLOWED；fullName/contactPhone/fullAddress 都是成分脱敏后的拼接值，无成分原文

# 2. 部分授权（仅 IDENTITY）：200 PARTIAL_ALLOWED，data 只有身份字段
curl -X POST localhost:8080/api/consents -H 'Content-Type: application/json' -d '{
  "consentId":"C-PART","patientId":"P001","requesterId":"nurse-A",
  "purposes":["TREATMENT"],"expiresAt":"2026-11-30T00:00:00Z","scopes":["IDENTITY"]}'
curl 'localhost:8080/api/patients/P001?requesterId=nurse-A&purpose=TREATMENT'

# 3. 范围全拒：403 DENIED_OUT_OF_SCOPE（只有 MEDICAL 授权，却访问无就诊记录的 P002）
curl -X POST localhost:8080/api/consents -H 'Content-Type: application/json' -d '{
  "consentId":"C-OOS","patientId":"P002","requesterId":"billing-B",
  "purposes":["TREATMENT"],"expiresAt":"2026-11-30T00:00:00Z","scopes":["MEDICAL"]}'
curl -i 'localhost:8080/api/patients/P002?requesterId=billing-B&purpose=TREATMENT'

# 4. 发布新版本后重放旧记录（seq 换成上面 ALLOWED 记录的序号，通常为 0）
curl -X POST 'localhost:8080/api/rules/active?version=consent-rules-v2'
curl -X POST 'localhost:8080/api/masking-policies/active?version=masking-v2'
curl 'localhost:8080/api/patients/P001?requesterId=doctor-A&purpose=TREATMENT'   # 走新版本
curl localhost:8080/api/audit/0/replay    # 仍是 v1 的结论与 masking-v1 的脱敏结果

# 5. 审计链与当前版本
curl localhost:8080/api/audit/verify      # {"intact":true,...}
curl localhost:8080/api/audit             # 新老记录各自携带版本与 deniedSections
curl localhost:8080/api/versions
```

过期与撤回可通过 `expiresAt` 为过去时间、或 `revokedAt` 非空复现；
对一条**既过期又撤回**的授权，v1 判 `DENIED_REVOKED`、v2 判 `DENIED_EXPIRED`，
升级后重放 v1 时代的该记录，结论仍为撤回，可直观看到版本隔离。
