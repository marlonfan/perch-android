# 栖信 0.5.1 Review（Reviewer 只读审查）

- 基线：`v0.5.0`（`a88b6c82c938b0cc4994e832aa2b457c3f2d327d`）；待审：`d8ce01efb5b64f81c78f04784711595910ef890a`
- 范围：`docs/message-presentation.md` + `release-notes/0.5.1.md`；`git diff v0.5.0...d8ce01e`
- 方法：只读审查 diff 及实现文件；未启动模拟器、未操作手机、未发送 ntfy 消息、未改源码（边界要求）。
  行号为待审 HEAD 当前行，可用 `grep -n` 复核。

## 终态

- 状态：**通过，无发布阻断问题，可打 tag / 发布**。
- P1（阻断）：**0 项**。展示层修改未触及数据、请求执行、凭证、clear 语义；去重与状态逻辑保守正确。
- 建议项：**0 项 P2**；仅 3 条 P3 级纯 cosmetic 观察（见 §4），均不建议在发布前改，可进 0.5.2 或接受现状。

## 需求符合度（逐项对照 message-presentation.md）

| 需求 | 实现 | 结论 |
|---|---|---|
| 已知 Emoji 在标题前内联、同字号；列表/详情/通知一致 | `Resources.kt:24-27 Tags.title`；`MainActivity.kt:283`（列表 17sp）、`:305`（详情 26sp）、`NotificationService.kt:169 setContentTitle` | ✅ 三处同为 `Tags.title`，字号跟随各自标题 |
| 相同 Emoji 别名不重复占位 | `tags.mapNotNull{emoji[it]}.distinct()` | ✅ 按 Emoji 值去重，别名合并 |
| 未知/普通文字标签保留 | `Tags.labels = filterNot{it in emoji}`；列表 `:284`、详情 `MessageExtras.kt:47`、通知 `:168` | ✅ 仅已知 Emoji 移入标题，其余原样保留 |
| 不改写标题/标签；不影响搜索、提醒匹配、通知优先级 | `Tags.title/labels` 均为显示期纯函数；`Message` 原始字段未动；`shouldNotify`/搜索仍用原始 tags | ✅ `grep Tags.display` 已无残留引用，无旁路 |
| 自定义 icon 与 App 小图标不参与 | `MessageIcon`、通知 `largeIcon` 链路未动 | ✅ diff 无涉及 |
| 详情全宽操作行、14dp 圆角、分隔线 | `MessageExtras.kt:51-74` 外层 `Surface(14dp)` + 行间 `HorizontalDivider` | ✅ 胶囊 FlowRow 已移除 |
| 每行图标+原名+简短说明；链接仅主机名 | `MessageActionRow` + `linkHost`（`Uri.host`，天然不含 query/凭证） | ✅ 失败/长文本见下 |
| 点击区 ≥64dp、名称说明可换行、失败完整显示、读屏语义 | `heightIn(min=64dp)`、名称 3 行/说明 2 行（失败不限行）、`semantics{role=Role.Button}` | ✅ |
| 执行中禁用+进度；成功勾选；失败手动重试；空 copy 禁用 | `enabled=state!=running && (copy→value非空)`；`running→CircularProgressIndicator`；`success→Check`；`failed→Refresh+error+点按重试` | ✅ 与 `store.action` 网关双重防重发（既有逻辑未动） |
| click 与相同 view URL 去重，保留显式操作语义 | `showMessageLink = click非空 && actions.none{view && url==click}`（精确相等才隐藏） | ✅ 保守去重；view 行保留自身 state/clear |
| 附件 10dp 圆角、≥48dp、`FlowRow` 换行 | `AttachmentCard` 下载/打开/分享/预览四按钮统一 `10dp + heightIn(48dp)`，打开行改为 `FlowRow` | ✅ |
| HTTP/超时/凭证隔离/广播授权/clear/通知渠道不动；通知按钮模板不动 | diff 未触及 `Interactions.execute/complete/ActionRequests`、`Data/Protocol`、`NotificationService` 除标题两行 | ✅ |
| versionName 0.5.1 / code 6 | `app/build.gradle.kts` | ✅ |

## 回归核查（重点：展示改动是否漏改语义）

- **原始语义保留**：列表、详情、通知标题均由同一函数生成，前缀为空标签时 `title()` 恒等于原标题（`([]+title).joinToString`），无标签消息渲染与 0.5.0 逐字一致。
- **通知优先级/提醒条件**：`notify()` 触发条件、`shouldNotify`、`minPriority>=2` 门限均未动；body 仅把标签段从 `display` 空格连接换成 `labels` 的 `·` 连接，且仅当存在未知标签时出现，不影响空标签消息。
- **动作语义**：执行入口仍是 `Interactions.execute`（同参数、同回调契约）；`finished` 回调由 `it ?: "操作成功"` 改为 `it.orEmpty()`——成功时不再写一行冗余“操作成功”（行内已有 ✓ + “操作成功”），失败文案不变。行为等价且更干净。
- **失败可见性**：旧的底部失败列表改为行内 `failed` 详情（不限行）+ 同文案 feedback 行（现为 error 色）。失败信息出现两处，略冗余但无丢失；成功态无残留文案。
- **click 去重边界**：大小写/尾斜杠差异不会合并（精确字符串相等），宁可重复不错删；`click` 为空时 `showMessageLink=false`，无空行。通知点击的 `InteractionActivity(click=true)` 路径（标记已读）未动。
- **空状态**：无操作且无 click 的消息不渲染“相关操作”区；`labels` 为空不渲染标签行。与 0.5.0 空状态一致。
- **主题/窄屏**：全用 `colorScheme` 角色色，无硬编码；行布局为 `fillMaxWidth + weight(1f)` 文本列 + 固定图标，320dp 下靠 `maxLines+Ellipsis` 收敛；附件行 `FlowRow` 换行。静态可确认，无需真机。
- **导入与 Lint**：新增 import（`BorderStroke/Alignment/ImageVector/Role/semantics/FontWeight/TextOverflow/sp/Uri`）经核对全部被使用；`ExperimentalLayoutApi` OptIn 已正确下移到仍用 `FlowRow` 的 `AttachmentCard`。Lead 的 `lintRelease` 通过佐证。

## P3 观察（不阻断，可延期）

- N1 标题自带 Emoji 时会与标签 Emoji 并列（如标题已含 ⚠️ 且有 warning 标签 → “⚠️ ⚠️ 标题”）。纯展示重复，无语义错误；如需处理可在 `title()` 中去重标题前导相同 Emoji，但属锦上添花，建议 0.5.2 或不改。
- N2 失败时同一错误文案同时出现在行内详情与底部 feedback 行。无信息丢失；可接受为强调。
- N3 `linkHost` 对 `geo:/mailto:/tel:` 等非层级 URI 回退为“在其他应用中打开”，不展示地址原文。安全且合理；用户若想确认号码/地址需点进系统应用，符合最小暴露原则。

## Gate（Lead 日志引用，未重复构建）

- PASS — `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintRelease --offline --console=plain` — 31 tests（0 failure / 0 error）、Debug APK、Release Lint — 日志 `logs/message-ui-build.log`、`logs/message-ui-test-results.log`
- PASS — `git diff --cached --check` — 无空白错误 — 日志 `logs/message-ui-diff-check.log`

## 给 Lead 的结论

**通过，可以推送 `v0.5.1` 并由 Actions 发布。** 本轮为纯展示层变更，需求逐项兑现，无数据/安全/语义回归；3 条 P3 观察均不建议发布前修改。
