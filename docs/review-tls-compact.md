# TLS 后续排查与紧凑收件箱 — Reviewer 审查报告

2026-10-30。Reviewer（Orca Worker）独立审查基线 v0.5.2（db68ef7）之上的未提交 diff：`NotificationService.kt`（撤回显式 Network 绑定）、`ReceptionPolicy.kt` + 测试（TLS 细分分类）、`Data.kt`/`SettingsPage`（内存诊断）、`MainActivity.kt`（紧凑收件箱 + read-all FAB）、两个 smoke 脚本与文档。只读源码，未修改实现、未连接设备。本报告不含用户域名与证书详情。

## 结论

**通过（无 P1/P2 阻断）。** 三部分改动（网络回退、TLS 分类与诊断、紧凑收件箱）实现正确、测试覆盖到位、隐私边界守住、文档如实区分开发机探测与手机复现。可交付设备复测。P3 建议见文末。

## 一、恢复系统默认联网路径（NotificationService.kt:74-80）

**正确。** 撤回 `socketFactory(network)` 与 `dns { network.getAllByName }` 后，sessionClient 仅保留每会话 `ConnectionPool()`；DNS 走 OkHttp `Dns.SYSTEM`（Android 上由 libnetd 按当前默认网络——含 TUN——解析），socket 走系统默认 socketFactory，路由/VPN/DNS/代理策略全部交还 Android。

- **VPN 下仍走 VPN**：Android 的 per-app/default VPN 由 netd 在内核层实施，应用用默认 socketFactory 建立的连接同样进入 TUN；不需要显式绑定才走 VPN。浏览器走系统栈能打开服务、应用走同样系统栈——该回退正是消除「应用与浏览器行为不一致」这一新增变量的正确方向。
- **保留部分仍成立**：`DefaultNetworkState` LinkProperties 状态机、collectLatest 取消、每会话新池（防止换网后复用旧路由连接）、IO finally evictAll 全部保留（NotificationService.kt:71-80、126-128）。新池现在只承担「换网络换池」，不再承担网络绑定——职责仍然清晰。
- **边界如实**：文档明确这是兼容性回退、不宣称已证实根因（docs/tls-compact-inbox.md「网络调整」）。正确：用户 TLS 错误与 0.5.2 显式绑定的因果尚未在手机上证实，回退是合理的排查第一步而非结论。
- 连接失败时 `store.connectionDiagnostics.update`（NotificationService.kt:111-113）在 IO 协程、catch 块内，与 `report` 同点写入，时序无问题。

## 二、TLS 分类优先级与异常链（ReceptionPolicy.kt:6-33）

**正确。** 逐项核对优先级排序与 Kotlin 运算符结合性：

- **`&&` 优先于逗号内的比较、`||` 最后并联**：每条 `causes.any { it is X || it is Y && cond }` 解析为 `it is X || (it is Y && cond)`——即 `CertificateExpiredException` 直接命中，或 `CertPathValidatorException.reason == EXPIRED` 命中。符合意图。
- **分类顺序即优先级**：过期 → 未生效 → 通用证书链（`CertificateException` 或任意 `CertPathValidatorException`）→ 对端身份 → 协议 → 握手 → TLS IO → DNS → 超时 → 连接 → 兜底。前面规则先匹配：
  - `CertPathValidatorException(EXPIRED)` 命中第 1 条而非第 3 条 ✅（reason 更具体的排前，正确）。
  - **关键的误归因防护**：裸 `SSLHandshakeException`（cause 为 EOF/无证书 cause）落到第 6 条 TLS_HANDSHAKE，不会误报「证书」——正是用户场景「提示 TLS 证书出错」但真实原因可能是代理中断流的问题。有专项测试 `tlsHandshakeWithoutCertificateCauseDoesNotBlameCertificates`。
  - `SSLHandshakeException(cause=CertificateException)` 命中第 1/2/3 条 ✅（Conscrypt 真实抛法：握手异常包装证书异常）。
  - 注意 `SSLPeerUnverifiedException`、`SSLProtocolException` 均为 `SSLException` 子类但排在前面 ✅；`SSLKeyException` 落 TLS_IO——可接受（无专属文案，IO 兜底）。
- **有界遍历**：`generateSequence(error as Throwable) { it.cause }.take(16)`——循环链（A↔B）时 cause 链有限长，take(16) 终止 ✅（有循环链测试 `wrappedAndCyclicFailuresAreBoundedAndKeepTheirClassification`，断言兜底文案完全匹配）。注意 `initCause(self)` 会抛 IllegalArgumentException 被测试回避（构造的是两节点环），实现无碍。
- **隐私**：固定文案 + 诊断码，零字符串插值 ✅；测试覆盖 secret 不出现在任何输出。
- 一个理论边界（非缺陷）：16 层内若证书 cause 被更浅的非 SSL IO 层包裹……实际不受影响——any() 检查整条链，深度包裹的证书 cause 仍会被发现，只有超过 16 层才漏，现实中不存在。

## 三、诊断摘要（Data.kt:52、NotificationService.kt:111-113、SettingsPage）

**正确。**

- 仅内存 `MutableStateFlow<Map<String, String>>`（Data.kt:50-52），不进 `update`/prefs，不落盘，进程重启清空 ✅。
- 按 server.url 分键，多服务各自独立 ✅；值为 failureStatus 产物（固定文案），无地址/令牌 ✅。
- 「不代表当前仍失败」：写入只发生在失败 catch，重试成功后不清除——设置页标题「最近连接错误 · 点击复制」（MainActivity.kt:392-397）措辞准确，文档明示 ✅。
- 复制用 `LocalClipboardManager`（deprecated 警告已存在于该文件其他处，一致）+ Toast 确认。诊断码（如 TLS_CERT_CHAIN）是固定枚举，复制无隐私风险。

## 四、紧凑收件箱与 read-all FAB（MainActivity.kt:202-272）

**布局结构正确：**

- 单行结构：`Row(weight(1f) 筛选组 + FilterList IconButton)`（:227-236）。FilterPill 各 `weight(1f)`（:276-278）平分剩余宽度，`maxLines=1 + Ellipsis + TextAlign.Center` 防超长数字撑爆；320dp 估算：行宽 320-40=280dp，筛选组约 230dp + 图标 48dp，每个 pill 约 110dp，容纳「全部 999+」级文案无压力。
- **FAB 位置**：`Box(fillMaxSize)` 内 `align(BottomEnd).padding(16.dp)`（:262-266），叠加在 LazyColumn 之上、位于 Scaffold 内容区（padding 已含底栏 64+导航栏），即 FAB 悬于列表底部、导航栏上方——不会与 AnimatedContent 其他页或底部导航重叠 ✅。原 Scaffold FAB 已删除（:159 diff），无堆叠 ✅。
- **遮挡防护**：`contentPadding = PaddingValues(bottom = if (showReadAll) 88.dp else 24.dp)`（:219）——FAB(40dp)+16dp padding ≈ 56-60dp，88dp 预留足够滚动到底露出最后一条 ✅；showReadAll 为 false 时回落 24dp，不留死区 ✅。
- **显隐条件** `showReadAll = !selecting && messages.any { !it.read }`（:206）：多选隐藏 ✅、无未读隐藏 ✅（用户「不占空间」诉求达成——无未读时彻底消失，比 0.5.2 disabled 按钮更进一步）、demo 模式有未读同样显示（readAll lambda 处理 demo）✅。
- **可发现性补偿**：无文字后靠 Icon contentDescription「全部标为已读」+ PlainTooltip 长按提示（:262-269）✅。TooltipBox 在 API26+ 可用（material3 纯 Compose 实现）。
- **readAll 语义未动**：lambda 仍是当前服务全量（MainActivity.kt:175），成功保存才逐条 cancel 通知、demoRead 映射完整保留 ✅。FAB enabled 恒真但仅在 showReadAll（=有未读）时组合，等价于原禁用逻辑 ✅。
- **主题菜单**：FilterList 图标 + `stateDescription = currentTopic`（:232-239，读屏播报当前主题）+ `BadgedBox { Badge() }` 圆点表示已选筛选（:237-238，filter 非空时）+ 菜单项 trailingIcon Check 标当前项（:243-244）+ 主题名 maxLines=2 Ellipsis ✅。三层状态表达（badge 视觉、Check 菜单、stateDescription 读屏）冗余得当。
- **发送入口**：菜单「发送消息」leadingIcon Edit，`if (!demo)` 才显示（:246），demo 模式不误露 ✅；publish lambda → composing=true，与原 FAB 等价 ✅。
- **主题失效清理**：`LaunchedEffect(topics.map { it.name })` 在 filter 指向的主题被删后重置 filter=""（:208-210）✅——这是对既有行为缺口的顺手修复，合理。
- **筛选行 horizontal padding 24→20dp**：一行放下 pill+图标，无溢出风险。

## 五、smoke 脚本与文档同步 — 正确

- settings_smoke.py：`assert_text('全部标为已读')`（匹配 FAB content-desc）→ 断言无同名**文本**节点（FAB 是图标非文字）→ tap → 未读 0 → 断言 FAB **消失**（content-desc 不存在）→ 打开筛选菜单验证「所有主题」。链路完整覆盖新 UI 的关键行为（出现/点击/隐藏/菜单），断言顺序合理。
- reading_smoke.py:89：`所有主题` → `筛选主题`（新图标 content-desc），320dp 截图步骤保留。
- README（工作区后续修正段 + smoke 描述）、VERIFICATION（EXPECTED FAIL 红测 + 三个 PASS gate）、docs/tls-compact-inbox.md、docs/network-reception.md 互链——全部同步且边界措辞准确。

## 证实 vs 待证实（必须区分）

**已证实（代码审查 + JVM 测试）：**
- 分类逻辑对 12 类异常形态（含 wrapped、cyclic、CertPath reason 枚举）的输出正确且无隐私泄漏；红测证明旧提示确实混淆证书原因与普通握手失败。
- 回退后联网路径恢复系统默认；LinkProperties 重连、独立池、IO 清理保留。
- 布局约束/显隐/读屏语义/菜单状态在代码层正确；41 JVM 测试、lint 通过。

**未证实（设备边界，不得宣称）：**
- 开发机 TLS/健康探测（HTTP 200）**不代表手机 Clash 路径**：未取到手机上的实际 SSL 异常链，用户真机 TLS 故障未复现，根因未定。候选方向（分流规则、节点行为、信任库差异）在文档中已列为排查优先级。
- 回退显式绑定是否解决用户的 TLS 提示——待手机复测。
- 320dp 实际渲染、Tooltip/FAB 手感、smoke 实跑。

**手机复测指引（准确描述）**：开启 Clash 后打开接收；若仍失败，设置 → 「最近连接错误 · 点击复制」，把诊断码（TLS_CERT_CHAIN / TLS_PEER_UNVERIFIED / TLS_HANDSHAKE 等）与 Clash 对该域名的规则命中一起反馈；对照浏览器同域名行为。若诊断码是 TLS_PEER_UNVERIFIED 或 TLS_CERT_CHAIN，重点查 Clash 分流/节点的证书处理；TLS_HANDSHAKE/TLS_IO 则偏节点连通性。

## P3 建议（不阻断）

1. **MainActivity.kt:206 `showReadAll` 未纳入 searching 状态**——搜索中 FAB 仍显示。点击执行的是当前服务全量已读（正确语义），但视觉上搜索态+全局已读按钮略突兀；可考虑 `&& !searching`。行为无害，纯打磨。
2. **诊断 Map 无容量上限**（Data.kt:52）——按 server.url 分键，服务器数量有限，实际不会膨胀；仅在动态增删大量服务器场景有意义。可不改。
3. **ReceptionPolicy.kt:14-16 的复合条件较长**——可读性尚可但接近密集；后续可提取 `private fun Throwable.isCertExpired()` 小函数。纯重构建议。
4. **settings_smoke.py:9 断言「无文本节点」依赖 FAB content-desc 与菜单项已删**——当前成立；若未来菜单再引入同名项会误报。测试脚本注释可标注此依赖。

## 审查验证命令

- PASS — `./gradlew :app:testDebugUnitTest --offline --console=plain`；41 tests / 0 failure / 0 error；logs/tls-compact-review-test.log
（未重复 assembleDebug/lintRelease；Lead 提供的 PASS 日志 logs/tls-compact-final-build.log、tls-compact-final-test-results.log、tls-compact-script-check.log、tls-compact-diff-check.log 已核对存在且结论一致。）
