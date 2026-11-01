# 0.6.0 最终合并候选 — Reviewer 审查报告

> **初轮后补充（2026-11-01）**：发布后开发 Orca Worker `notification-alerts` 补充终态发现一项初轮遗漏的 P2——`MessageExtras.kt` ServerManager 移除服务时先逐条 `NotificationService.cancel` 再 `store.removeServer`，且忽略返回值：保存失败时服务/订阅/接收仍在，但通知（含 INSISTENT 提醒）已被清且窗口关闭，用户误以为移除成功。初轮审查的「reconcile 统一清理」结论覆盖了 Store 路径，但未发现 ServerManager 这处绕过 Store 的先行 cancel，属审查盲区（同类调用点检查当时未做）。修复已在 0.6.1（commit 62d2f95）完成并经本 reviewer 复核通过：仅在 `store.removeServer()` 成功后才 sync+关窗，失败保留提醒与窗口并显示错误，通知清理由 reconcile 在持久化成功后统一执行。详见 docs/review-0.6.1.md。以下初轮原文保留不改。

2026-11-01。Reviewer（Orca Worker）独立审查 f8df7df（0.5.5）→ 88b82c6 的完整候选（开源准备 71de762 + 功能 88b82c6，34 文件 +1576 行）。只读审查，未改代码、未 push/release、未启动模拟器/操作手机。开源部分已并入（详见 docs/review-open-source.md，本报告只补 0.6.0 相关增量）。

## 结论

**通过，无 P1/P2 阻断，可发布。** 两功能按 spec 完整落地，测试覆盖扎实（62 项 JVM），安全边界干净，文档如实区分设备验证边界。P3 六项列文末。

## Spec 符合度（docs/priority-update-spec.md）

**通知**：✅ 长振动（4 短 0.65s / 5 长 3.5s，`PriorityAlerts.vibration`）；✅ 持续提醒默认关闭、订阅可选（`persistentAlert`）；✅ 文案明确「由系统重复播放、不承诺绕过渠道/勿扰/厂商」（TopicDialog 提示 + docs）；✅ 只对符合条件的新消息提醒、缓存重放与异步图标不重启（`liveAfter` 水位 + `post()` 图标更新短路）；✅ 打开/清除/已读/删除/静音/移除订阅/停用服务停止对应提醒（`reconcile` 在每次持久化后统一清理 + `stopPersistent` 于服务销毁/进程启动/sync 关闭路径）；✅ 多服务隔离（`eligible` 按 server 查 topic/server，跨服务用例有测试）；✅ 旧配置迁移（`vibration_version` 缺省 1 = 保持旧渠道，`persistent_alert` 缺省 false，round-trip 测试）；✅ 显式迁移渠道复制声音/徽标/灯光/锁屏可见性/分组，`importance` 取自 legacy——**用户关闭的渠道不会无提示重置**（关闭的渠道 importance 为 NONE，新渠道继承 NONE，且迁移只在 legacy 存在时复制）。

**Updater**：✅ 设置内主动检查、显示当前/最新版本；✅ 下载进度可取消可重试；✅ 不携带 ntfy 凭证（独立 OkHttpClient、仅 UA/Accept 头）；✅ 来源（精确仓库资产 URL 等值匹配）、校验和（唯一匹配 + 常时比较）、包名、versionName 一致、versionCode 严格大于（拒绝降级）、minSdk、签名兼容（同集合或 P+ 单签名轮换且历史互含）；✅ 系统安装 + 未知来源授权，无自动循环（见下）；✅ 无签名文件暴露——FileProvider `app.perch.updates` 仅授权 `cache/updater/`（update_paths.xml）。

**开源**：见 docs/review-open-source.md——通过；0.6.0 增量核对：PRIVACY.md「应用内更新」段与实现一致（GitHub API/CDN、不携带 ntfy 凭证、系统授权确认）；README 发布节改为「全部单元测试」如实；VERIFICATION 记录 Worker 中断与 Lead 接手，未把中断任务计为完成。TLS 未宣称修复（release-notes 0.6.0 明示「不宣称修复 Clash 路径问题」）✅。

## Standards 深度审查

### 通知链路

- **open 水位**（NotificationService.kt:91-93、PriorityAlerts.openTime）：仅 `event=="open" && time>0` 提供水位；`persistent()` 要求 `message.time > liveAfter`——open 同秒消息保守只响一次，无 open 时间也只响一次，不依赖时钟同步。缓存重放（time ≤ open 水位）不会触发 INSISTENT ✅。`liveAfter` 是 `var` 在 collect 闭包捕获、单协程内写读，无并发问题 ✅。
- **同步锁防复活**（Data.kt:66-82）：`update()` 改 `commit()`（同步确认写盘）成功后才 `state.value = s` + `reconcile`；`withSnapshot` @Synchronized 与 update 同锁——异步图标回调 `notify()` 在锁内重查快照，已读/删除后 `current == null || !eligible` 直接返回，**不会复活已划掉/已读通知** ✅。commit 失败保留旧快照并报错，reconcile 不运行——「成功 commit 后清理」语义准确。
- **图标更新短路**（post:215-216）：`!alert && (active == null || (largeIcon != null && INSISTENT))` → return——异步图标不重建已划掉通知、不打断持续响铃重播 ✅。普通 notify（active==null 且 alert）正常新建。
- **FLAG_INSISTENT 仅新通知**（post:247）：`active == null && persistent(...)`——已存在的 INSISTENT 通知被后续 post 重发时 flags 由 builder.build() 重建，`notify()` 同 tag/id 更新不会重启 insist 循环（系统对已 insistent 的更新不重触发），且 `alert=false` 路径已短路。✅
- **reconcile 语义**（NotificationService.kt:184-192）：持久化后对每个 active id=2 通知，若消息不存在/不合格/（INSISTENT 且 persistentAlert 已关）则取消——覆盖已读、删除、静音、filters 变化、移除订阅、停用服务全部走 `update()` 的路径 ✅。`stopPersistent` 只杀 INSISTENT 的，不动普通通知 ✅；PerchApp.onCreate 启动清残留（进程重建后 INSISTENT 已随通知存留，需显式清）✅。
- **权限检查**：post 补 `areNotificationsEnabled()` 早期返回（POST_NOTIFICATIONS 33+ 检查保留）✅。

### Updater 链路

- **来源与跳转**（UpdatePolicy.redirect）：LATEST 初始跳转仅允许回到同一 URL（GitHub API 302 → 对象存储场景被 `next == start` 约束……实测 api.github.com/releases/latest 无跳转，此分支为纵深防御）；资产下载跳转白名单=精确 github.com 路径（同 path 无 query）或 `release-assets.githubusercontent.com/github-production-release-asset/` 前缀；强制 https+443+无凭据+无 fragment；重定向上限 5。测试覆盖 7 类恶意跳转（含 userinfo、后缀域、:444、他仓库）✅。
- **大小与校验和**：三级上限（APK 150MiB/元数据 1MiB/清单 64KiB）+ contentLength 与字节双计数上限 + expected 精确匹配（清单/APK）；SHA256SUMS 唯一匹配行、`MessageDigest.isEqual` 常时比较、下载后与安装前**双重**摘要计算（ApkVerifier.install 路径再验一次）✅。
- **签名与轮换**（ApkVerifier）：GET_SIGNING_CERTIFICATES（28+）/GET_SIGNATURES（26-27）；同集合直过；单签名轮换要求 `signingCertificateHistory ⊇ {installed, candidate}`——正确利用系统解析的轮换链，不自建信任；多签名或 8.x 无轮换 ✅。测试覆盖 6 种组合。
- **取消/迟回调**：generation 计数 + job.cancel + 迟结果丢弃（测试 `cancellingCheckPreventsLateResultFromChangingState`）；下载流 `ensureActive()` 分块检查，OkHttp call 经 invokeOnCancellation 同步取消 ✅。进度回调经 `scope.launch` + generation 检查，旧回调不污染新状态 ✅。
- **文件生命周期**：临时 .part 失败即删；验证通过才 rename .apk；close/cancel 删未交接文件；**交接后（installerOpened/handedOff）保留**，2 天后惰性清理——安装器仍可读 URI，权衡正确 ✅。FileProvider 独立 authority `app.perch.updates` 只挂 cache/updater/，CLIP_DATA + FLAG_GRANT_READ_URI_PERMISSION 精确授权 ✅。
- **授权/安装不循环**：Ready→（可装？install : needPermission）；授权返回 Ready 后 LaunchedEffect 重新触发 install（重新验证）→ LaunchInstaller→打开；`installer.launch` 结果回调**空实现**——返回/取消不触发任何再弹（无循环）✅；`permissionReturned(true)` 陈旧回调被状态机忽略（测试）✅；权限在 LaunchInstaller 与打开之间被吊销 → `installPermissionRevoked` 回到提示 ✅。安装前**再验证**（install(): verify 后才 LaunchInstaller，测试断言 verified==2）✅。
- **进程死亡**：controller 为 remember + DisposableEffect close，无持久化 install intent/permission 状态——重启后无重放 ✅（符合注释「Screen-scoped」）。

### 风险点核对（无阻断）

1. `UpdateDialog` LaunchedEffect(state) 中 Ready→自动 install：若 canRequestPackageInstalls 为真，Ready 即自动进入验证+安装页——用户点过「下载并安装」后的连续动作，可接受（非未确认自动安装；安装页仍需系统确认）。
2. `eligible()` 不含 `m.priority >= minPriority` 重复检查——但 `shouldNotify` 已含（Protocol.kt:20），eligible 调用 `topic.shouldNotify(message)` ✅ 一致。
3. reconcile 对 INSISTENT 且 persistentAlert=true 但消息已读的情况：`!eligible` → 取消 ✅（已读停止响铃路径）。
4. `channel()` 迁移只复制可复制项，vibration 用户设置不复制（新渠道 pattern 由应用写入）——文档已声明「应用不能覆盖用户关闭项」，被用户关闭的旧渠道（importance=NONE）迁移后新渠道同样 NONE，通知不响但 INSISTENT 也不会有声——符合「不无提示重置」。
5. VIBRATE 权限新增（渠道振动需要）；REQUEST_INSTALL_PACKAGES 新增（未知来源授权需要）——均为功能必需，PRIVACY/README 已披露更新功能。
6. `UpdateRepository.transfer` 递归 request 在 OkHttp 回调线程——重定向链最多 5 层，无栈风险；continuation 单发（isActive 检查）✅。
7. Store.commit() 在主线程？——update() 调用方多在 IO（NotificationService IO 协程、readAll 从 UI）……read()/readAll() 从 MainActivity 主线程调用 commit() 是同步磁盘写。**潜在 jank**：Data.kt:69 prefs.commit() 在主线程。0.5.x 原为 apply()（异步）。这是为「确认写盘后才清理通知」的刻意取舍，单条小 JSON 写入通常 <10ms，但低端机/忙时可能掉帧。记 P3-5（打磨：主线程写可考虑 Dispatchers.IO 包裹或保留 commit 但移至 IO 调用点）。

## 测试与门禁

62 项 JVM（PriorityAlertsTest 8 + UpdateControllerTest 7 + UpdatePolicyTest 6 新增，既有 41 保留），覆盖 spec 列出的全部策略面（迁移、隔离、水位、迟回调、取消、授权循环、降级、签名）。smoke 脚本未为本轮新增（设备功能），文档如实。

## 证实 vs 待证实

**已证实（代码+JVM）**：上述全部策略逻辑；开源文件与 0.6.0 增量一致性；历史敏感材料双扫描（前报告）；门禁 PASS（本 worker 复跑：tests=62/0/0 + assembleDebug）。

**待证实（设备）**：真机振动/INSISTENT 重播与厂商行为；渠道迁移实际效果；未知来源授权与系统安装器全流程；真实 Release 下载/校验（VERIFICATION 已注明 gh release view 仅验证资产命名）；320dp 设置页新条目。

## P3（不阻断）

1. `eligible()` 可加 `m.priority >= 2` 显式（现由调用方保证）——防御性。
2. UpdateDialog Ready 态若 `canRequestPackageInstalls` 已真，自动进入验证+安装——用户少一次点击但可加确认步；现实现可接受。
3. `release()` 的 `body.take(12000)` 截断说明无「已截断」提示；纯打磨。
4. reconcile 每次持久化都遍历 activeNotifications（一般 ≤ 数条）；性能可忽略。
5. **主线程 prefs.commit()**（Data.kt:69）：read/readAll 从 UI 调用时同步写盘可能 jank；建议后续把写盘挪 IO 或调用点包 withContext。功能正确，非阻断。
6. THIRD_PARTY_NOTICES 可补注 updater 无新第三方（OkHttp 复用）——现表已覆盖。

## 验证命令

- PASS — `./gradlew :app:testDebugUnitTest :app:assembleDebug --offline --console=plain`；62 tests / 0 failure / 0 error + BUILD SUCCESSFUL；/Users/admin/Dev/code/java/perch-android/logs/review-0.6.0-gates.log
（lintRelease 见 Lead logs/features-final-gates.log，纯静态分析本轮未改动其输入已核对。）
