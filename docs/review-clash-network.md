# Clash / TUN 网络修复 — Reviewer 审查报告

2026-10-30。Reviewer（Orca Worker）独立审查工作目录未提交 diff：`NotificationService.kt`、`ReceptionPolicy.kt`、新增 `DefaultNetworkState.kt` / `DefaultNetworkStateTest.kt`、`ReceptionPolicyTest.kt` 扩展、`docs/network-reception.md`、`VERIFICATION.md`。只读源码，未修改、未提交、未发版、未连接设备。

## 结论

**通过（无 P1/P2 阻断）。** 应用侧修复正确、覆盖完整、文档如实标注设备验证边界，可交付用户真机复测。以下是逐项审查结果和少量 P3 建议（不阻断）。

## 审查清单（按 Lead 要求逐项）

### 1. API 26+ DefaultNetworkCallback 对 VPN 的语义 — 正确

`registerDefaultNetworkCallback`（NotificationService.kt:60）在 minSdk 26 可用，无需版本分支。语义要点：

- VPN 建立时系统把 default network 切到 TUN（`onAvailable(vpn)` + 新 `LinkProperties`），本修复恰好消费这两个回调（DefaultNetworkState.kt:22-27、NotificationService.kt:38-44）。
- VPN 断开时 default network 回落物理网（wifi/cell）：表现为 `onAvailable(wifi)`（或 `onLost(vpn)` 后 `onAvailable(wifi)`），`DefaultNetworkState.lost` 只清空（DefaultNetworkState.kt:29-31），随后 `available` 走“先置空、等 LinkProperties”路径，行为正确。
- 一个已知系统特性：VpnService 型 TUN 下 `onLost` 不总是下发（underlying network 变化时 netd 重绑 default net）。本实现不依赖“必然先 lost 再 available”，`available(new)` 本身会先取消旧会话（mutable.value = null），因此该特性不构成缺陷。

### 2. Network.socketFactory / getAllByName 在 VPN/TUN 下遵循 app-default 路由 — 正确

NotificationService.kt:77-85：每会话 `client.newBuilder().connectionPool(ConnectionPool()).socketFactory(network.socketFactory).dns { network.getAllByName(it) }`。要点：

- 在 VPN 为 app-default 时，`network` 即 TUN Network，`socketFactory` 把 socket 绑定到该 Network（通过 netd bound socket），DNS 也走同一 TUN——这正是 Clash TUN 场景下“解析与连接分属两个网络”的正确解法。
- VPN 断开、物理网为 default 时同理，socket/DNS 绑定物理网。
- `Network.getAllByName` 抛出的 `UnknownHostException` 会被 OkHttp 原样抛出，落入 `ReceptionPolicy.failureStatus` 的 DNS 分类——正确。
- 注意（已由代码注释说明）：这是 *app-default* 路由，遵循系统 VPN 路由（Clash 的 Fake-IP/规则由 TUN 内核处理），**不是**绕过 VPN 选物理网。正确方向。

### 3. 等待 LinkProperties 的时序 / 重复 / 迟到回调 — 正确

DefaultNetworkState.kt 是唯一状态源，`@Synchronized` 三个入口：

- **时序**：`available(n)` 先把 `mutable.value = null`（旧会话立即取消，进入“等待网络恢复”），`linkPropertiesChanged(n, …)` 才发布 Snapshot。等待期间 UI 显示“等待网络恢复”，不会用过期路由连接。与 Android 事实一致（`onAvailable` 先于首次 `onLinkPropertiesChanged`）。
- **重复**：`available` 中 `if (current == network) return`（DefaultNetworkState.kt:24）保证重复 `onAvailable` 不重连；Snapshot 是 data class，重复相同 `LinkProperties` 经 StateFlow 语义化（distinct）不重连。DefaultNetworkStateTest `duplicateCallbacksDoNotReconnectAndLossStopsReception` 覆盖。
- **迟到**：`linkPropertiesChanged` / `lost` 都检查 `current == network`（DefaultNetworkState.kt:27、30），旧网络的迟到回调被忽略。`lateCallbacksFromOldNetworkDoNotInterruptVpn` 覆盖。
- **一次会话的参数稳定**：会话启动后捕获 `currentNetwork`（Snapshot），期间即使属性再变，新一轮 collectLatest 取消旧会话并换新池——不存在“半新半旧”混用。

### 4. StateFlow conflation 与 collectLatest 取消 — 正确

- combine + collectLatest：新 Snapshot 到达时旧 block 被取消，`coroutineScope { … }` 等待所有 per-server 子协程结束（含 callbackFlow 的 `awaitClose { activeCall.cancel() }` 同步取消阻塞读），然后才进入 finally。
- finally 中 `sessionClient.connectionPool.evictAll()`（NotificationService.kt:131）在 collectLatest 块内、IO dispatcher 上执行，即“上一个会话的池在流取消之后清理”，注释属实。evictAll 关闭 idle 连接并标记池已驱逐，active call 已被 cancel。
- StateFlow conflation：中间态被合并无妨——中间态本来就该跳过（例如 available→null→linkProperties 快速序列，最终态才开新会话），跳过中间重连正是期望行为。
- 多服务并发：`configurations.forEach { launch { … } }` 共享同一 sessionClient，任一网络变化全部重启——语义一致（同一次 TUN 切换所有服务都应换池）。

### 5. DNS 变化后是否仍可能缓存旧解析 — 正确（应用侧已尽力）

- 每会话新池 + `Network.getAllByName` 使用该 Network 专属的解析上下文，避免继续使用旧默认网络的解析上下文。Lead 补充缓存边界：这不保证绕过 Android / netd 的 DNS 缓存；网络专属解析仍可能命中以 network ID 区分的缓存，不能理解为每次强制无缓存查询。
- 会话内 DNS 变化靠 LinkProperties 重启会话处理；同一 LinkProperties 下的 DNS TTL 缓存由系统 resolver 负责，应用无需也无法干预。
- 残余风险（设备上待证实，非缺陷）：Clash Fake-IP 表项陈旧或 resolver 层缓存与 ntfy 服务器 TTL 不一致时，应用侧无法感知。这属于代理配置问题，应用侧已到能力边界，docs/network-reception.md 已如实说明。

### 6. 恢复 / 补收 / 退避 / 多服务 — 保持原语义

- 补收：`since` 游标计算逻辑未动（NotificationService.kt:137），重连后从游标-1 补收。
- 退避：`attempt` 重置条件（30s 稳定连接即重置）、`retryMillis` 分类（4xx 300s、Retry-After 上限）、jitter 均未变。
- 多服务：结构与原来一致，仅缩进层级变化 + finally。
- 唯一行为变化是错误文案分类（ReceptionPolicy.kt:5-11），纯字符串映射，不拼接异常消息（有测试防泄漏）。原“网络不可用，稍后自动重连”兜底保留。

### 7. 回归测试覆盖 — 充分

DefaultNetworkStateTest（5 项）覆盖：link 变化先停旧会话再开新会话（含时序断言）、同网络 DNS 变化失效、新网络等待 DNS/路由、迟到回调忽略、重复回调不重连 + 失网恢复。ReceptionPolicyTest 新增错误分类不泄漏。红色阶段日志（logs/clash-regression-red.log）证明两项测试对旧逻辑确实失败——先红后绿成立。

### 8. 验证记录与文档 — 如实

VERIFICATION.md 与 docs/network-reception.md 明确标注：未连接设备、未模拟 Clash、不宣称已复现/修复用户真机故障、复测路径具体。符合“不夸大验证边界”的要求。

## P3 建议（不阻断，供后续迭代）

1. **onCapabilitiesChanged 未消费（低优先级）**：`NET_CAPABILITY_VALIDATED` 变化（如 captive portal 验证完成）不会触发重启，理论上存在“LinkProperties 相同但 validated 状态变化”的窗口。当前依赖“连接失败→退避重试”兜底，可接受；若真机复测发现此窗口，再补 Capabilities 作为 Snapshot 的一部分。
2. **DefaultNetworkState 泛型形参 L 未做语义约束**：正确性依赖 `LinkProperties.equals`（Android 实现 equals 比较全部字段，含 DNS 列表）。若未来换成不含 DNS 的自定义类型会静默退化为“网络变化才重连”。可在 KDoc 中注明该契约。现状无碍。
3. **池仅 evictAll 未显式清理 dispatcher**：sessionClient 与 base client 共享 dispatcher executor，无需关闭；`ConnectionPool()` 线程为懒创建 daemon，evictAll 后闲置线程会自行退出。无泄漏。
4. **docs 可补一句**：Clash “增强模式/TUN” 下 `onBlockedStatusChanged` 不适用（default network 不会报 blocked），无需处理——避免后人误加。

## 证实 vs 待证实（设备边界）

**已证实（JVM 测试 + 代码审查）：**
- 旧逻辑对同网络 DNS/路由变化零反应、对 onAvailable 早于 LinkProperties 的窗口直接连接——两项红测复现。
- 修复后时序、去重、迟到回调、失网恢复、会话隔离、错误分类全部按预期工作。

**待证实（需真机 + Clash）：**
- Clash TUN 开启/关闭/切 DNS 模式时，系统实际回调序列与本实现的会话状态机吻合。
- `getAllByName` 在用户设备 Clash 配置（Fake-IP/redir-host）下解析结果可用。
- 用户报告的故障是否即上述应用侧缺口所致（也可能是 Clash 规则拒绝/证书拦截，应用无法替代理修正）。

## 审查验证命令

- PASS — `./gradlew :app:testDebugUnitTest --offline --console=plain`；37 tests / 0 failure / 0 error；logs/clash-review-test.log
