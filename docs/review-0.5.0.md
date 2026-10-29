# 栖信 0.5.0 Review（Reviewer 只读审查）

- 基线：`2d3cd4b`（0.4.0）；待审：`e600f60c2c05fd58077c66d3c48fe1905d413a6e`
- 范围：`docs/ntfy-compatibility.md` + `release-notes/0.5.0.md` 的全部客户端功能
- 方法：只读审查 `git diff 2d3cd4b..e600f60` 及实现文件；未启动模拟器、未操作手机、未推送/打 tag（按边界要求）。
  引用行号为待审提交当前行，可用 `grep -n` 复核。
- 构建/测试引用 Lead 已跑日志，不重复跑 gate（见 §7）。

## 终态（先说结论）

- 状态：**审查完成，有条件通过，不阻止发布**。
- P1（阻止发布）：**0 项**。未发现真实的功能错误导致的丢消息/错投递、凭证外泄、有副作用请求自动重试、迁移丢数据。
- P2（建议打 tag 前修，或接受为已知问题并在 Release Notes 补充）：**7 项**，见 §3。均为小改，其中 P2-1～P2-4 可在 30 分钟内修完。
- P3（后续优化）：**6 项**，见 §4，可进 0.5.1。
- 是否需要复审：**若修 P2-1～P2-4，建议快速复审 diff（无需全量重审）**；若原样发布，则无需复审，但建议把 §3 剩余项记为已知问题。

## 覆盖度对照（需求 → 实现）

| 需求（ntfy-compatibility.md） | 实现 | 说明 |
|---|---|---|
| 保存 click/actions/attachment/icon/tags/sequence_id/控制事件；旧配置迁移 | ✅ | `Protocol.kt` Message/MessageCodec/SnapshotCodec；`CompatibilityTest` 13 项新测试覆盖 |
| 通知+详情操作按钮；click 遵循；HTTP 默认 POST+method/headers/body；失败明确；仅成功 clear；不自动重试；仅点击执行 | ✅ | `Interactions.kt:37-72` + `NotificationService.kt:155-176` + `MessageExtras.kt`；失败态保留手动“重试”，无自动重试 |
| 附件名/类型/大小/有效期；受限预览/下载/打开/分享；自定义图标/Emoji；凭证隔离；动作敏感数据加密 | ✅ | `Resources.kt:28-98`；`canAuthenticate` 前缀+端口+scheme 校验；`protected_actions` 加密 |
| sequence 按服务+主题隔离；更新/clear/delete；重放/同秒/重启不复活；详情跟随 | ✅ | `Protocol.kt:26-40` + `NotificationService.kt:82-88` + `MainActivity.kt` opened 跟随 |
| 多服务添加/选择/改令牌/独立暂停/移除；后台并发接收；游标/通知/主题/消息隔离 | ✅ | `NotificationService.kt` per-server `launch`；`notificationTag=[server,topic,sequenceId]`；legacy `server:id` 兼容取消 |
| 应用内发送标题/正文/优先级/标签/Markdown/URL 附件；分享文本/文件；文件上传；明确目标、点击才发 | ✅ | `Publishing.kt:43-69` JSON 发布 + PUT 上传；`MainActivity.kt:80` 仅 `content://` 分享 |
| 搜索标题/正文/标签；静音/最低优先级/标签条件/broadcast 许可；渠道入口；ntfy:// 确认订阅 | ✅ | `MainActivity.kt:187-227` 搜索；`Topic.shouldNotify`；`allowBroadcast` 门控 |
| 简洁首页、浅深主题、320dp、电源约束；无轮询/心跳/WakeLock | ✅ | 无新增轮询；Doze 退出重连；Lint 仅 Warning（见 §7） |

遗漏/部分实现（均不阻止发布，归入 P2/P3）：`SEND_MULTIPLE` 未支持（P3-5）；`pri=1` 永不提醒与 UI `minPriority=1` 矛盾（P2-6）；`readAll` 不清通知（P2-2）。

## P2（建议修）

### P2-1 InteractionActivity 在异步 HTTP 完成前 finish，失败 Detail 可能弹不出
- 位置：`app/src/main/java/app/perch/Interactions.kt:87-91`（`execute(this, m, action){…}; finish()`），回调 `showDetail:90-92` 用已 finishing 的 Activity `startActivity`。
- 复现：通知栏点 HTTP 按钮 → 服务端返回 500 → 通知已更新为“操作失败”（`complete:68-72` 正常），但 Detail 错误页大概率不出现（OEM 上 `finish()` 后 `startActivity` 被忽略）。
- 影响：失败反馈仍有（通知体 `failed` 文案），但不如预期“明确反馈”完整。
- 建议：`showDetail` 改用 `applicationContext.startActivity(...FLAG_ACTIVITY_NEW_TASK)`，或 Activity 延迟 `finish()` 到 `finished` 回调。对应 `click` 分支 `:82` 同理可用 applicationContext。

### P2-2 “全部标为已读”不清系统通知， shade 残留
- 位置：`MainActivity.kt:166`（`readAll` 仅 `store.readAll()`）、菜单 `:227`；单条 `open` 会 `NotificationService.cancel`，批量不会。
- 复现：收到 2 条通知 → 收件箱菜单 → 全部标为已读 → 消息变已读，通知栏仍有 2 条；点进去是已读消息。
- 建议：`readAll` 后对当前服务可见消息逐条 `cancel`，或新增 `NotificationService.cancelAll(server)`。属 UX 一致性，非数据问题。

### P2-3 发送对话框切换服务后主题名不重置，可能发错主题
- 位置：`Publishing.kt:75-78`（`topic by rememberSaveable(server.url)` 仅首值生效）、`:98` FilterChip 只改 `serverUrl`。
- 复现：A 服务有 `alerts` → 打开发送（默认 A/alerts）→ 点 B 服务 Chip → 主题框仍是 `alerts` → 点发送 → 发到 B/alerts（用户未订阅过的主题）。
- 建议：`LaunchedEffect(serverUrl){ topic = topics.firstOrNull()?.name.orEmpty() }`，或切换时若原 topic 不在新服务 topics 中则清空并提示。

### P2-4 编辑非当前服务的令牌会意外切换收件箱
- 位置：`MainActivity.kt` `ServerManager`→`ServerDialog`→`Data.kt:store.server`（upsert + select）。
- 复现：当前 A → 服务管理 → 编辑 B 令牌保存 → 收件箱跳到 B。
- 建议：新增 `updateServer` 只更新不选中，或保存后恢复原选中。P3 级 UX，但易修，建议同批处理。

### P2-5 Keystore 加密失败无兜底；解密失败静默丢 actions/tokens
- 位置：`Data.kt:58`（`encode` 直接 `vault::encrypt`，抛则 `update` 崩）、`Protocol.kt:100,109`（`runCatching…getOrDefault` 静默）。
- 复现（罕见）：Keystore 失效/损坏后保存服务 → `update` 抛 → 崩溃；或恢复后打开 → token/actions 变空无提示。
- 建议：`encode` 包 `runCatching`，失败弹 Toast/日志并拒绝保存；`decode` 失败时在设置页提示“凭证无法解密，请重新输入令牌”。现有 `decode` 容错方向正确，只差用户可见性。

### P2-6 `priority=1` 永远不弹通知，与“最低提醒优先级=1”矛盾
- 位置：`NotificationService.kt:88`（`m.priority >= 2` 硬门限） vs `Topic.shouldNotify`（允许 1） vs UI 文案“最低提醒优先级：1（低于此级别仍会保存）”。
- 复现：订阅 min=1 → 发 pri=1 → 入库无通知。用户设 1 本意全提醒。
- 建议二选一：去掉 `>=2`（与 0.4.0 行为变更，需在 Release Notes 说明），或 UI 最小值改为 2 并注明“pri=1 仅保存”。倾向后者（保持 0.4.0 静音语义），改动最小。

### P2-7 `processed`/`sequences` 无上限，SharedPreferences 长期膨胀
- 位置：`Protocol.kt:30,40,110`（每次 `apply` 追加 `processed + key`，`delete` 不清理；`encode` 全量持久化）。
- 复现（慢性）：日均 50 条 → 一年约 18k keys → data JSON 数百 KB → 启动解析变慢。
- 建议：`delete`/裁剪（`take(1000)`）时同步裁 `processed`（仅保留现存 messages + deleted 的 keys），或 `sequences` 按 LRU 裁剪。0.5.0 可不修，但应在代码加 TODO 并在 0.5.1 处理。

## P3（后续）

- P3-1 缓存命中跳过过期检查（`Resources.kt:37-39`）：`file.isFile` 直接返回，不看 `expires`。现状是“过期后仍可打开本地缓存”，`AttachmentCard` 下载按钮已禁（`MessageExtras.kt`）。建议文档化（README 已写“远端过期后无法重新下载”，再加一句“本地缓存仍可打开”）或统一拦截。
- P3-2 方法/参数未校验（`Protocol.kt:action` + `Interactions.kt:ActionRequests`）：任意 method 透传、`copy` 空 value 允许。建议白名单 `GET/HEAD/POST/PUT/PATCH/DELETE`，`copy` 空值在 UI 禁用按钮。
- P3-3 `ntfy://` 健壮性（`MainActivity.kt:139-145`）：IPv6 host、末段 URL 编码、`display` 超长未截断。建议 `URLDecoder` + 长度裁剪；多段路径仅取末段现状可接受，加注释即可。
- P3-4 `removeServer` 残留游标（`Data.kt:64-68,89-91`）：`cursor:server:topic` 未清理。建议移除时清前缀 keys；低风险泄漏。
- P3-5 仅 `ACTION_SEND`，`SEND_MULTIPLE` 被忽略（`MainActivity.kt:80` + manifest）。建议 manifest 备注或 UI 提示“一次分享一个文件”，避免用户误以为多选已发送。
- P3-6 主线程小开销：`Tags.load` 51KB JSON 解析在 `PerchApp.onCreate`（`Data.kt`），`trim` 全目录遍历每次下载（`Resources.kt:76`）。量级小（ms 级），后续可搬 IO，不阻塞。

## 安全/可维护性专项

- 凭证隔离 ✅：`resourceRequest:74` 仅 `canAuthenticate` 才带 `Authorization`；重定向每跳重检 + 禁降级（`Resources.kt:47-48`）；`ActionRequests` 不继承订阅 token（测试 `actionsUseExplicitHeaders…` 断言无 Authorization）。
- 重定向 ✅：上限 5、`webUrl` 校验、相对 Location `resolve`；HTTP 动作不跟随（`followRedirects=false`），3xx 视为失败，与“不自动重试副作用”一致。
- 大小 ✅：`MAX_FILE 25MB` 三处检查（声明/头/流式），`MAX_IMAGE 2MB` 图标/预览；`bitmap` 采样上限 1200（图标 128）。
- 后台 ✅：无新增轮询/心跳/WakeLock；网络回调 + Doze 退出触发；退避沿用 0.4.0。
- 加密 ✅：token + `protected_actions` AES-GCM；`encode` 无明文密钥（测试覆盖）；`allowBackup=false`。
- 可维护：`Protocol/MessageArchive/SnapshotCodec` 有 13 项 JVM 测试；`NotificationService` 流逻辑、`Interactions` 异步、`Resources` 下载无 JVM 测试（需 Android/网络），靠代码审查 + 后续人工观察，符合本次“无设备”边界，已在 VERIFICATION.md 声明。

## 器件/交互未验证项（按边界要求声明，不视为缺陷）

通知点击/按钮、分享接收、附件打开/分享、ntfy:// 跳转、Doze/厂商后台、320dp 真机布局均未在本轮执行；已做静态核查（exported、FileProvider、PendingIntent data 隔离、channel/importance、权限申请链）。发布后需靠真实使用观察，Actions 亦不覆盖。

## Gate（Lead 日志引用，一行一条）

- PASS — `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintRelease --offline` — 27 tests/Debug APK/Release Lint 全过 — 日志 `logs/compatibility-final-build.log`
- PASS — `git diff --cached --check` — 无空白错误 — 日志 `logs/compatibility-staged-check.log`（空文件）
- PASS — `lintRelease` — 0 Error，仅 BatteryLife/DataExtractionRules/UseKtx Warning — 报告 `app/build/reports/lint-results-release.txt`

## 建议修正清单（给 Lead）

1. P2-1：`showDetail` 用 applicationContext（2 行）。
2. P2-2：`readAll` 后清当前服务通知。
3. P2-3：`serverUrl` 切换重置 `topic`。
4. P2-4：编辑服务不切换选中（或恢复选中）。
5. P2-5（可选同批）：`encode` 包错 + 解密失败提示；否则记已知问题。
6. P2-6：UI 最小优先级改为 2 或去掉 `>=2` 二选一 + Release Notes 一句话。
7. 文档：README/Release Notes 补一句“本地缓存过期仍可打开；一次分享一个文件”（覆盖 P3-1/P3-5）。
8. 完成后 `git diff` 快速复审 P2 改动即可，无需全量重审；原样发布亦可，P2-5～P2-7 进 0.5.1。


## Lead 修正与取舍（2026-10-29，待快速复审）

- P2-1 已修：HTTP 通知操作显示等待提示，完成回调后才结束 InteractionActivity；重建不重发请求，失败明确进入详情。
- P2-2 已修：全部已读保存成功后，清除当前服务消息的系统通知。
- P2-3 已加固：服务 Chip 切换时显式将主题重置为该服务首个订阅或空值。原 rememberSaveable(server.url) 的 inputs 本身也会在服务变化时重置，此处显式处理让行为更直接。
- P2-4 已修：updateServer 仅更新服务，不改变当前选中的收件箱；新增服务仍会选中。
- P2-5 已修：加密保存失败返回失败并保留旧状态；接收端不前移游标。解密失败保留原始密文，设置与详情提示，相关服务不带空令牌继续接收、发送或执行丢失凭证的动作。重新输入令牌或恢复密钥后可恢复。测试覆盖密文保留、恢复及旧版令牌迁移。
- P2-6 已修：订阅 UI 最小提醒优先级为 2，明确优先级 1 仅保存，保持 0.4.0 语义。
- P2-7 接受为已知限制，延期安全压缩：processed/sequences 是防重放元数据，不能按当前 1000 条消息简单裁剪，否则会让服务端缓存中的旧消息重新出现。代码已注明未来迁移需按主题回放水位和同秒边界设计；此项为长期存储增长，不阻止本版发布。
- P3-1 已文档化：远端过期后本地已下载缓存仍可打开。
- P3-2 已修：动作 HTTP 方法限制为常用合法方法，空复制值不可执行。
- P3-3 已修：使用纯 JVM 的订阅链接解析，支持 IPv6、端口、子路径、编码主题和 display 长度限制，并测试拒绝恶意或无效链接。
- P3-4 保留游标是有意选择：重新添加服务时按原接收点补收，避免重新拉取全部缓存；游标不含凭证，本地消息也按产品约定保留。
- P3-5 已在说明和发布说明明确单文件分享，未声明支持批量分享。
- P3-6 延后微优化：Emoji 小映射初始化及限额缓存遍历目前不改变电源/网络策略，不阻止本版。

新增 4 项针对审查修正的 JVM 测试，总计 31 项。未执行本地模拟器或实体手机测试。
