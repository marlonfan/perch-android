# 0.6.1 移除服务失败路径补丁 — Reviewer 审查报告

2026-11-01。Reviewer（Orca Worker）审查 v0.6.0（tag 指向 a4e72ad，含发布记录 commit）→ 62d2f95 的补丁：`MessageExtras.kt`（ServerManager 移除服务 guard + 错误 UI）、`app/build.gradle.kts`（0.6.1/code 12）、README/VERIFICATION/release-notes。只读审查，未 push、未操作设备。

## 结论

**通过，无未关闭的 P1/P2，可发布 0.6.1。** 开发 Worker notification-alerts 发现的 P2（移除服务先取消通知、忽略保存失败）已被本补丁正确关闭。

## P2 修复复核（MessageExtras.kt:174-181）

**旧缺陷**（v0.6.0）：confirmButton 内先 `s.messages.filter { server }.forEach { NotificationService.cancel }` 再 `store.removeServer(url)`（返回值被丢弃）——若持久化失败（加密错误/磁盘满），服务器/订阅/接收仍在，但系统通知已被清掉且 INSISTENT 提醒被静默消灭，用户还以为移除成功（窗口关闭）。

**补丁行为**：

- **成功路径**：`store.removeServer(url)` 返回 true（= update() 内 commit() 写盘成功 + `state.value = s` + `NotificationService.reconcile` 已在锁内清理该服务全部通知，Data.kt:73-75）→ `NotificationService.sync(context)`（停接收前台服务或重配）→ 关窗。通知清理由 reconcile 统一负责，无重复 cancel ✅。
- **失败路径**：返回 false（commit 失败，旧快照保留，storageError 已由 update() 写入）→ 窗口保留（`removing` 不变）、服务与所有提醒原样、`removalError = store.storageError.value.ifBlank { "移除失败，请稍后重试。" }` 显示错误并可再点「移除」重试 ✅。`removalError` 在重新点「移除」入口时清空（:166）✅。
- **跨服务隔离**：仅 `filter { it.server == server.url }` 的旧 cancel 已删；reconcile 按 `notificationTag`（含 server）匹配，其他服务的通知与 INSISTENT 不受影响 ✅。
- **同类调用点扫描**：其余 `NotificationService.cancel` 调用均已在 0.6.0 前就带 guard（`store.read()/readAll()/delete` 成功才 cancel——MainActivity.kt:130/142/181、Interactions.kt:71/83），无同类「先清后存、忽略失败」残留 ✅。
- 注：取消的是 id=2 通知（cancel 只 cancel(tag,2)），reconcile 也只管 id=2，与常驻连接通知（id=1）无交集，sync() 独立处理前台服务 ✅。

**错误 UI**：Column + spacedBy(8dp)，removalError 用 error 色；仅非空显示。文案链路：update() 的 storageError（「加密保存失败…」/「保存失败…」）→ 兜底「移除失败，请稍后重试。」，无敏感信息 ✅。

## 版本与文档

- versionCode 12 / versionName 0.6.1 ✅；release-notes/0.6.1.md 描述准确（含「不修改 DNS/TLS」「建议替代 0.6.0」）；README 0.6.1 段 + 下载文件名更新 ✅。
- VERIFICATION 如实记录：P2 来源（开发 Worker 补充终态）、「不能把 reviewer 初轮无阻断结论当作该问题不存在」的自我修正、62 项 JVM **不是**保存失败 UI 的故障注入（无该分支测试，如实标注）✅。
- 基线 SHA 记录（Lead 校正元数据说明）：`v0.6.0` 是 annotated tag，`git rev-parse v0.6.0` 返回 tag 对象 `ab5c584`，`git rev-parse 'v0.6.0^{}'` 才返回目标 commit `a4e72ad`。这不是额外的 docs commit。`git cat-file -t` 与剥离 tag 的核对记录在 `logs/release-0.6.1-tag-metadata.log`；补丁比较基线和审查范围不受影响。

## 测试边界（如实）

62 项 JVM 不覆盖该失败 UI 分支（需故障注入/设备）；本审查以代码路径推证成功/失败两分支行为。设备上「失败保留提醒+窗口」的实测仍属待验证——但这不构成发布阻断：失败分支行为保守（保留一切），最坏情况只是显示错误文案。

## P3（不阻断）

1. 重试期间窗口按钮仍为「移除」——若 storageError 持续（如磁盘满），用户可无限重试无退避；可接受（手动操作）。
2. `removalError` 取 `store.storageError.value` 有微小竞态窗口（另一写操作同时改 storageError）——显示后果仅为文案可能错位，不影响状态。

## 验证命令

- PASS — `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintRelease --offline --console=plain`；62 tests / 0 failure / 0 error + BUILD SUCCESSFUL；/Users/admin/Dev/code/java/perch-android/logs/release-0.6.1-review-gates.log
