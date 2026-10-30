# 收件箱「全部标为已读」入口恢复 — Reviewer 审查报告

2026-10-30。Reviewer（Orca Worker）独立审查未提交 diff 中 UI 部分：`MainActivity.kt` Inbox 布局、`tools/settings_smoke.py`、`README.md`、`VERIFICATION.md`。只读源码，未修改实现、未运行模拟器。网络修复部分另行见 docs/review-clash-network.md。

## 结论

**通过（无 P1/P2 阻断）。** 功能恢复正确、语义与原实现一致、smoke 脚本与新入口匹配、文档同步。可交付设备复测。以下为逐项审查与少量 P3 建议。

## 审查清单（按 Lead 要求逐项）

### 1. 入口直接可发现 — 达成

MainActivity.kt:238-243：`TextButton(onClick = readAll, …)` 位于筛选区（全部/未读 pill）下方操作行右侧，`Icons.Outlined.DoneAll`（18dp）+ 6dp 间隔 + 完整文字「全部标为已读」——图标与文字同时在场，可见即可点，无需先开菜单。这正是用户诉求（功能「没了」→ 恢复且更可发现）的解法。

### 2. 窄屏布局与超长主题名 — 设计正确（渲染待设备证实）

- 结构：Column 两行（MainActivity.kt:222-247）——第一行 FilterPill 组，第二行 = 主题菜单 `Box(Modifier.weight(1f))` + read-all 按钮。read-all 按钮宽度为固有宽（约 18+6+6字×12sp+16dp padding ≈ 110-125dp），主题菜单占据剩余全部宽度。
- 超长主题名省略：菜单按钮内 `Text(…, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)`（MainActivity.kt:235）。机制正确：Box 的 weight(1f) 提供有界最大宽，约束传入 TextButton 内部 Row 后 weighted Text 以剩余空间为上限、fill=false 只占所需，超长时 Ellipsis 生效。
- 320dp 估算：行宽 320-48=272dp，按钮 ~120dp + 菜单最小宽（箭头 16dp + padding 16dp + 至少 1-2 字符）≈ 50-60dp，仍有富余；不会挤压掉 read-all 按钮。相比旧布局（菜单按钮与筛选 pill 同行、无省略），反而更抗窄屏。
- 注：两行之间无显式 `Arrangement.spacedBy`，间距依赖 FilterPill 自身 vertical 9dp padding 与 TextButton 40dp 最小高，预期足够但不属于本审查可实测范围（见 P3-2）。

### 3. 按钮语义 / 禁用状态 / 点击作用域 — 与原实现一致

- 禁用条件 `enabled = messages.any { !it.read }`（MainActivity.kt:238）与原 DropdownMenuItem 的 `enabled` 完全相同：按**当前服务全部收件消息**（不受主题筛选/未读筛选影响）判定，无未读时禁用。语义未变，正确。
- onClick 直接 `readAll`（lambda 未动），点击作用域为按钮本身（wrap content），无误触区域扩大。
- 禁用态用 Material 默认 onSurface/38% 处理，无额外 tint hack；图标 contentDescription=null 但按钮内有完整文字，读屏合并语义可读——与菜单项（leadingIcon 无描述 + 文字）同模式。

### 4. 多服务与未读筛选下的行为 — 保持原语义

- `messages` 在调用处已按当前 server 过滤（MainActivity.kt:105）；`Store.readAll` 只标当前 `s.server.url` 的消息（Data.kt:84）。多服务下其他服务不受影响——与修改前一致。
- readAll 后 recompose：`未读 N` pill 归零、按钮自动禁用、底部导航未读徽标清空。未读筛选开启时列表随之清空并显示空态文案——均为现有状态推导，无新逻辑，正确。
- 演示模式：`demoRead = Demo.messages.map { it.id }`（MainActivity.kt:173）未变。

### 5. 保存成功才取消通知 — 原语义完整保留

MainActivity.kt:173：`if (store.readAll()) messages.forEach { NotificationService.cancel(context, it) }`。`Store.update` 加密保存失败返回 false 时跳过通知清理（Data.kt:60-70），与 0.5.0 review P2-2 修复后的行为一致。本次未触碰该 lambda。

### 6. DropdownMenu 剩余项 — 合理

菜单保留 所有主题/各主题 + Divider + 搜索/选择（MainActivity.kt:236-240）。Divider 从「主题列表|全部已读+搜索+选择」变为「主题列表|搜索+选择」，分组仍成立。「选择消息」的 `enabled = visible.isNotEmpty()` 未变。选择模式下整个块被替换为多选操作行，read-all 与菜单同时隐藏——与旧行为一致（旧菜单在选择模式同样不可达）。

### 7. smoke 脚本匹配新入口 — 正确

tools/settings_smoke.py:8-9：先 `assert_text('全部标为已读')`（验证不经菜单即可见，substring 匹配 text/content-desc，5×0.5s 轮询）再 `tap_text`。tap_text 精确匹配取第一个节点——界面上「全部标为已读」文本唯一（菜单项已移除，FilterPill 文案为「全部 N」不冲突）。演示数据含未读 → 按钮可点；点后 `assert_text('未读 0')` 依赖 demoRead 全标已读——链路与旧脚本等价，仅入口步骤被替换。docstring 与 PASS 输出同步更新（:1、:24）。README:106 描述同步。✅

### 8. 文档与验证记录 — 如实

VERIFICATION.md 新增条目说明布局决定、gate 结果与「本轮无设备、未运行 smoke、320dp 待复测」边界；未夸大。logs/read-all-build.log（BUILD SUCCESSFUL）、read-all-diff-check.log（空=无 whitespace 错误）与 Lead 提供的 PASS 一致。

## P3 建议（不阻断）

1. **README:15（0.3.0 历史段落）仍写「『全部标为已读』收进主题菜单」**——作为历史变更记录本身没错，但该段读起来像现状描述。建议发布 0.5.2 时在该节或新版段落补一句「后于 0.5.2 移回收件箱直接显示」，避免读者困惑。
2. **两行间距未显式声明**（MainActivity.kt:223 Column 无 spacedBy）——依赖子元素 padding 拼出视觉间隔。若设备截图显示偏挤，加 `verticalArrangement = Arrangement.spacedBy(4.dp)` 即可，属打磨项。
3. **read-all 与筛选行视觉分组**——read-all 在第二行右侧而 pill 在第一行左侧，扫描动线为 Z 形；若设备实测发现误将「全部标为已读」理解为只作用于当前筛选，可考虑文案改「本服务全部标为已读」。仅提示，当前文案与原版一致，不要求改。

## 证实 vs 待证实（设备边界）

**已证实（代码审查 + Lead gate 日志）：**
- 入口可见性、布局约束机制、禁用条件、readAll/通知清理/demoRead 语义、smoke 脚本与 README/VERIFICATION 同步、构建与 lint 通过。

**待证实（需设备/模拟器）：**
- 320dp 实际渲染（主题名省略效果、两行间距、无未读时禁用态观感）与 smoke 脚本实跑。本轮无设备，未验证。

## 审查验证命令

本次审查未重复构建（Lead 已提供 PASS gate：logs/read-all-build.log、logs/read-all-diff-check.log，核对一致）。
