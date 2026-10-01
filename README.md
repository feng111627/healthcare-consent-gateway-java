# Healthcare Consent Gateway（患者数据访问合规网关）

一个基于 Spring Boot 的本地内存演示服务，把患者数据访问的三件事做扎实：

1. **能不能看**——按「授权范围 + 请求用途」做访问判定，授权撤回 / 过期 / 越权 / 不存在
   四类拒绝原因各自独立、可区分；
2. **看到什么**——返回前按可配置、带版本的策略对姓名、证件号、联系方式脱敏，
   递归覆盖嵌套结构与拼接字段；脱敏过程出错时**宁可拒绝返回，也不带出原文**；
3. **看没看过**——每次判定都追加一条哈希链审计记录，任何一条被改动、删除或重排都能被
   检测出来；并发写入不破坏链；策略调整后仍可凭历史快照复现当时的判定依据。

无任何外部依赖：数据全部在内存（`ConcurrentHashMap` / `CopyOnWriteArrayList`），
启动即用，不需要数据库或真实凭据。

---

## 1. 模块与调用链

```
HTTP /api/patients/access
        │
        ▼
DefaultGatewayService        编排：判定 → 留痕 → 取数 → 脱敏 → 返回
        ├── DefaultAuthorizationService   授权判定（用途/撤回/过期/越权）
        ├── DefaultAuditService           SHA-256 哈希链留痕 / 校验 / 复现
        ├── DefaultMaskingService         返回前脱敏（递归 + 拼接字段）
        ├── InMemoryConsentRepository     授权
        ├── InMemoryPatientDataRepository 患者数据
        └── InMemoryPolicyService         多版本访问策略（含脱敏规则）
```

关键包：

| 包 | 职责 |
|---|---|
| `domain` | `Purpose`、`ConsentGrant`、`AccessDecision`、`DecisionReason`、`DecisionBasis` |
| `model` | `PatientRecord`、`MaskingRuleSet/MaskStrategy`、`AuditRecord`、`IntegrityReport` |
| `policy` | 版本化的 `AccessPolicy` 与内存策略服务 |
| `security` | `Hashing`（SHA-256）与 `AuditHasher`（规范化哈希载荷） |
| `service` | 授权、脱敏、审计、网关编排四个服务 |
| `web` | REST 接口与全局异常处理 |
| `config` | 启动时载入演示数据（患者、5 条授权、策略 v1） |

---

## 2. 授权规则

### 2.1 判定输入与依据

每次判定输入：`requesterId`（请求方）、`patientId`（患者）、`purpose`（用途）。

用途枚举：`TREATMENT`（诊疗）、`PAYMENT`（支付）、`RESEARCH`（研究）、`MARKETING`（营销）。

判定时生成不可变快照 `DecisionBasis`，包含：命中授权 ID/版本、撤回标志与撤回时间、
过期时间、允许用途列表、请求用途、判定时刻、**当时的策略版本**。该快照会整体写入审计记录。

### 2.2 拒绝原因（互斥、可区分）

按以下**固定优先级**判定（避免同一条授权同时命中多个问题时返回含糊错误）：

| 顺序 | 结论 `DecisionReason` | HTTP 响应特征 | 含义 |
|---|---|---|---|
| 1 | `CONSENT_NOT_FOUND` | allowed=false | 请求方对该患者没有任何授权 |
| 2 | `CONSENT_REVOKED` | allowed=false | 授权存在但已被撤回（依据里带 `revokedAt`） |
| 3 | `CONSENT_EXPIRED` | allowed=false | 授权已过期（`evaluatedAt >= expiresAt`，**到期时刻即算过期**） |
| 4 | `OUT_OF_SCOPE` | allowed=false | 授权有效，但请求用途不在允许范围内 |
| 5 | `GRANTED` | allowed=true | 放行，随后取数并脱敏 |

撤回状态用 `volatile` 字段发布，保证并发读可见；撤回是一次性动作，保留首次撤回时间。

---

## 3. 脱敏规则

### 3.1 策略（按字段配置，规则集带版本）

| `MaskStrategy` | 行为 | 示例 |
|---|---|---|
| `REDACT` | 整段替换为 `[REDACTED]` | `110101...1234` → `[REDACTED]` |
| `PARTIAL` | 保留首尾各 1 位，中间打码（≤2 位整体打码） | `Zhang` → `Z***g` |
| `EMAIL` | 本地部分保留首字符 + `***`，域名保留 | `a@b.com` → `a***@b.com` |
| `PHONE` | 仅保留最后 4 位（`+` 前缀保留） | `13800138000` → `***8000` |

规则 key 支持：

- **叶子字段名**：如 `phone`，对任意层级同名字段生效；
- **点号全路径**：如 `contact.name`，优先级高于叶子名，可对同名字段差异化处理；
- **拼接字段**：`CompositeFieldRule` 用模板 `"{0} {1}"` 引用多个成分字段，
  输出值由**各成分脱敏后的值**重组。例如 `fullName = firstName + " " + lastName`
  会得到 `S*n Z***g`，拼出来的字段也不会漏出完整姓名。

遍历递归覆盖 `Map` / `List` / 嵌套数组。

### 3.2 失败即闭合（fail-closed）

- 规则集为 `null`、字段值类型不是字符串、拼接字段的成分缺失等情况，统一抛
  `MaskingException`；
- 网关捕获后返回 `data=null`、`maskingError=MASKING_FAILED`，**绝不返回原文**，
  并把失败事实写入审计（`maskingErrorCode=MASKING_FAILED`）。

### 3.3 演示规则（policy-v1）

`firstName/lastName/name=PARTIAL`，`idCardNo=REDACT`，`email=EMAIL`，`phone=PHONE`，
`fullName` 为拼接规则。

---

## 4. 审计留痕：哈希链与校验方式

### 4.1 链结构

每条 `AuditRecord`（不可变，字段全 `final`）保存：

```
seq, requestId, timestamp, request(请求方/患者/用途),
decision(结论), basis(判定依据完整快照), policyVersion,
masked, maskingErrorCode,
prevHash, recordHash
```

- 第 1 条记录的 `prevHash = "GENESIS"`，之后每条的 `prevHash = 上一条的 recordHash`；
- `recordHash = SHA-256(规范化 JSON 载荷)`；载荷用 **TreeMap 按 key 排序**、枚举用
  `name()`、时间用 ISO-8601，字段顺序确定、跨 JVM 可复算（见 `AuditHasher`）；
- `recordHash` 自身不参与自身载荷。

### 4.2 并发安全

「取链尾 → 算 hash → 追加」整段在 `DefaultAuditService` 的同一把锁内完成，
seq 在并发下保持 `1..N` 连续，prevHash 不会串链。20 线程 × 10 次的并发写入测试
验证了链完整、hash 无重复。

### 4.3 校验能检出什么

`verifyChain()` 逐行重算并比对三类问题，报告 `IntegrityReport{valid, recordCount,
brokenAtSeq, details}`：

1. **内容被改**：业务字段或依据快照被修改 → 该行重算 hash 不匹配；
2. **链被打断**：某条记录被删除/替换/重排 → 后继记录的 `prevHash` 失配；
3. **序号不连续**：插入或删除导致 seq 断号。

测试通过构造「保留原 hash、仅替换请求用途」的篡改副本，验证校验在对应 seq 上报失败。

### 4.4 策略变更后的历史复现

- 策略发布**只增不改**：新版本成为当前版本，旧版本永久保留（`policyService.version(v)`）；
- 复现接口只依赖审计记录里的 `DecisionBasis` 快照 + 当时的 `policyVersion`，
  不读取授权的当前状态，因此撤回/过期/规则调整后，历史结论仍可原样复现；
- 脱敏规则同样按版本绑定（`AccessPolicy.maskingRules()`），未来可基于历史版本
  复现当时的脱敏效果。

---

## 5. HTTP 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/patients/access` | 访问患者数据，body：`requesterId/patientId/purpose` |
| GET | `/api/audit` | 查看全部审计记录 |
| GET | `/api/audit/verify` | 校验哈希链完整性 |
| GET | `/api/audit/{requestId}/replay` | 复现某次判定的依据与结论 |
| GET | `/api/policy` | 查看当前策略 |
| POST | `/api/policy` | 发布新策略版本（body：`version` + `maskingRules`） |

拒绝访问时 HTTP 状态码仍为 200（请求本身被正常处理），以 `decision.allowed` 与
`decision.reason` 区分；参数错误返回 400。

---

## 6. 本地验证

### 6.1 运行测试（推荐）

```bash
mvn test
```

测试清单（共 26 个）：

- `ConsentAuthorizationTest`（7）：放行 / 撤回 / 过期 / 过期边界 / 越权 / 无授权 /
  四类拒绝原因互不相同；
- `MaskingTest`（6）：四种策略、嵌套与列表脱敏、点号路径优先级、拼接字段缺成分失败、
  规则缺失失败；
- `AuditChainTest`（4）：200 条并发写入后链完整、篡改单条被检出、删除断链被检出、
  策略升级后历史复现一致；
- `GatewayIntegrationTest`（8）：HTTP 端到端覆盖全部拒绝原因、脱敏无原文残留、
  脱敏失败返回 `MASKING_FAILED` 且留痕、审计校验接口；
- `ApplicationTests`（1）：Spring 上下文加载。

> 按要求，授权/审计相关单测通过 SLF4J 在 INFO 级别打印**输入**（requesterId/patientId/
> purpose）与**判定依据**（consentId、revoked、revokedAt、expiresAt、allowedPurposes、
> policyVersion）及最终结论，可在 `mvn test` 控制台或 surefire 输出中查看。

### 6.2 启动服务并用 curl 验证

```bash
mvn spring-boot:run
```

演示数据（启动自动载入）：患者 `P-1001`；请求方 `clinic-A`（诊疗/支付）、
`insurer-B`（支付）、`lab-C`（仅研究）、`partner-D`（已撤回）、`legacy-E`（已过期）。

```bash
# 放行 + 脱敏（注意 idCardNo/email/phone/fullName 的输出）
curl -s -X POST localhost:8080/api/patients/access -H 'Content-Type: application/json' \
  -d '{"requesterId":"clinic-A","patientId":"P-1001","purpose":"TREATMENT"}'

# 已撤回 -> CONSENT_REVOKED
curl -s -X POST localhost:8080/api/patients/access -H 'Content-Type: application/json' \
  -d '{"requesterId":"partner-D","patientId":"P-1001","purpose":"TREATMENT"}'

# 已过期 -> CONSENT_EXPIRED
curl -s -X POST localhost:8080/api/patients/access -H 'Content-Type: application/json' \
  -d '{"requesterId":"legacy-E","patientId":"P-1001","purpose":"TREATMENT"}'

# 越权 -> OUT_OF_SCOPE
curl -s -X POST localhost:8080/api/patients/access -H 'Content-Type: application/json' \
  -d '{"requesterId":"lab-C","patientId":"P-1001","purpose":"TREATMENT"}'

# 无授权 -> CONSENT_NOT_FOUND
curl -s -X POST localhost:8080/api/patients/access -H 'Content-Type: application/json' \
  -d '{"requesterId":"nobody","patientId":"P-1001","purpose":"TREATMENT"}'

# 校验审计链 / 查看审计记录
curl -s localhost:8080/api/audit/verify
curl -s localhost:8080/api/audit
```

### 6.3 发布新策略并验证历史复现

```bash
# 发布 v2（例如 phone 改为整段遮蔽）
curl -s -X POST localhost:8080/api/policy -H 'Content-Type: application/json' -d '{
  "version": "policy-v2",
  "maskingRules": {
    "version": "policy-v2",
    "fieldRules": { "phone": "REDACT", "idCardNo": "REDACT" },
    "compositeRules": []
  }
}'

# 用此前某条审计返回的 requestId 复现（结论与依据快照保持 v1 时的样子）
curl -s localhost:8080/api/audit/<requestId>/replay
```

---

## 7. 设计边界说明

- 数据在内存中，重启后重置；演示用途，不包含认证/鉴权中间件与传输加密；
- 哈希链检测的是**事后篡改**；记录在追加临界区内生成，内存期内不存在合法改写路径；
- 患者数据模型为通用 `Map<String,Object>`，用于演示任意嵌套结构的脱敏。
