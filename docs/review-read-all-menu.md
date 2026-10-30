# 0.5.4 全部已读收进主题菜单 + 顶部常显搜索 — Reviewer 审查报告

> 终态汇总：第一轮（仅菜单）与第二轮（追加搜索）合并审查，最终 diff（基线 fc7f4ad）整体 **通过，无 P1/P2 阻断**。搜索部分结论见下半部分「追加审查：顶部常显搜索（最终 diff）」。

## 追加审查：顶部常显搜索（最终 diff，含搜索后完整结论）

用户追加要求：缩小全部/未读切换、同一行加常显搜索框。以下针对最终 diff（MainActivity.kt:234-242、275-296 区域）。

### 1. 320dp 宽度预算 — 可用，输入区偏紧（P3）

固定支出：行 padding 16×2 + 筛选组 104 + 间距 6×2 + 主题图标 48 = 196dp → 320dp 下搜索框 = 124dp；360dp+ 设备 ≥164dp。
- 搜索框内部：10+4 padding + 18 图标 + 6 间隔 = 38dp → 空态文本区 86dp；**显示清除按钮时再减 40dp → 约 46dp**（14sp 下拉丁字符 6-7 个、中文 3-4 个）。可输入且单行可横向滚动，不截断数据，但 320dp 非局促机型偏紧——见 P3-1。
- 触控目标：FilterPill `heightIn(min=48dp)`，窄组内每个 pill (104-4)/2=50dp×48dp ✅；主题图标 48dp ✅。

### 2. BasicTextField 焦点 / 语义 / 清除 — 正确

- `semantics { contentDescription = "搜索消息" }`（MainActivity.kt:279）使读屏与 uiautomator 都能发现该字段；字段仍为可编辑语义。P3-4 记 TalkBack 朗读细节待设备确认。
- `ImeAction.Search` + `onSearch = { focus.clearFocus() }`（:283-284）——搜索键收焦正确；`singleLine`、`cursorBrush SolidColor(primary)`、`textStyle 14sp/onSurface` 齐备。
- decorationBox 结构正确：占位符仅在空 query 显示，`inner()` 放在 `Box(weight(1f))` 内受界；query 非空时显示 40dp 清除 IconButton（contentDescription「清除搜索」），`update("")` 清空且保持焦点可续输。
- 占位符「搜索」13sp 与图标同 onSurfaceVariant，视觉层级合理。

### 3. 筛选联动 / 无结果文案 / 多选切换 — 正确

- `visible` predicate 未动（title/body/tags，ignoreCase）✅；搜索与主题筛选、未读筛选自然叠加（同一 and 链）。
- `query` 仍是 `rememberSaveable`（:205），且声明在 Inbox 顶层（selecting 分支之外）：进多选（顶部行替换为选择工具栏）再退出，query 保留、列表仍按关键词过滤——docs/inbox-search.md 所述行为一致 ✅。多选中「全选」沿用 visible（含搜索条件），与旧搜索行行为一致。
- 无结果文案：`query.isNotBlank() → "没有匹配的消息" / "换个关键词，或调整主题与未读筛选。"`（:255），优先于未读/空态分支，正确；清除后回完整列表。
- 旧 `searching` 状态、菜单「搜索消息」项、独立 OutlinedTextField 行全部移除，无死代码（grep 确认）。

### 4. settings_smoke.py 新增断言链 — 可靠（附 1 条运行前提注记）

新链路（:8-19）：assert 搜索字段存在 → tap「全部」复位筛选 → `field(0, 'nas')` → assert 命中「服务器需要你看一眼」且 demo-1 标题消失（小写 'nas' 匹配正文大写 'NAS'，验证 ignoreCase + 正文搜索）→ `field(0, 'perch-no-such-message')` → assert「没有匹配的消息」→ tap「清除搜索」→ keyevent 4 收键盘 → assert demo-1 恢复 → 断言「全部标为已读」不外泄 → 菜单链（含置灰断言，同前）。

- `field(0,…)` 取首个 `android.widget.EditText`：演示收件箱无其他 EditText，Compose 文本字段经语义互操作映射为该 class ✅；helper 内 ctrl+A 后输入，替换旧词可靠。
- `tap_text('全部')` 精确匹配唯一（pill 文案现为纯「全部」，数量已移入 contentDescription，底栏导航标签 alwaysShowLabel=false 不冲突）✅。
- P3-3：`keyevent 4` 依赖 IME 开启时消费 BACK；若 IME 意外已关，BACK 会退出 Activity 使后续断言**响亮失败**（不会静默通过）——smoke 语义可接受，注明即可。

### 5. 文档一致性 — 六处对齐

README（首段 0.5.4 描述 + 功能列表搜索行改为顶部搜索框 + smoke 描述行）、VERIFICATION 0.5.4 条目（两轮 gate 日志分列：release-0.5.4-* 覆盖菜单调整、inbox-search-* 覆盖搜索后最终态）、docs/inbox-search.md（决策与设备复测清单）、release-notes/0.5.4.md（搜索条目）、docs/tls-compact-inbox.md 头注——均与最终实现一致，未宣称 TLS 根因确认。版本仍 0.5.4/code 9，未发布 ✅。

### P3（追加项，均不阻断）

1. **320dp 显示清除按钮时输入区约 46dp**——功能可用但局促；设备实测若打字困难，可把清除按钮收到 36dp 或筛选组收窄到 96dp。
2. **清除按钮显式 `size(40dp)` 低于 48dp 触控建议值**——IconButton 默认 48dp 被压缩；可去显式 size 或改 44dp+最小可点区域。
3. **smoke 的 keyevent 4 依赖 IME 消费 BACK**（见上分析）——失败模式响亮，可保留；如要更稳可改为点收件箱外的固定坐标收焦。
4. **搜索字段 contentDescription 覆盖朗读细节**——TalkBack 是否同时朗读占位符/当前值需设备确认；不影响自动化断言。
5. （沿用第一轮）Box 单子级包装与空行残余；tls-compact 头注未提搜索（该文档主题自洽，inbox-search.md 已覆盖）。

### 追加审查验证命令

- PASS — `./gradlew :app:testDebugUnitTest --offline --console=plain`；41 tests / 0 failure / 0 error；logs/inbox-search-review-test.log
（Lead 的 logs/inbox-search-build.log、inbox-search-tests.log、inbox-search-script-check.log、inbox-search-diff-check.log 已核对存在。以下第一轮记录保留原文。）

---

## 第一轮审查记录（仅菜单调整快照，已被上方最终结论覆盖）

2026-10-30。Reviewer（Orca Worker）审查基线 fc7f4ad（v0.5.3）之上的未提交 diff：`MainActivity.kt`（菜单项 + FAB 移除）、`tools/settings_smoke.py`、`app/build.gradle.kts`（0.5.4/code 9）、`README.md`、`VERIFICATION.md`、`docs/tls-compact-inbox.md` 头注、新增 `release-notes/0.5.4.md`。只读审查，未修改实现、未运行模拟器。

## 结论

**通过（无 P1/P2 阻断）。** 改动小而完整：精确执行用户要求（悬浮框取消、入口收进主题菜单）、readAll 语义零变动、smoke 断言链覆盖新旧两态、版本与文档六处一致。

## 审查项

### 1. 菜单项语义 — 正确（MainActivity.kt:245）

- `DropdownMenuItem(Text("全部标为已读"), leadingIcon DoneAll, enabled = hasUnread, onClick = { menu = false; readAll() })`——位置在主题列表 + Divider 之后、操作组首位（发送/搜索/选择之前），是菜单内最显眼的操作位，可发现性优于 0.5.0 时代（当时埋在搜索/选择之后）。
- `enabled = hasUnread = messages.any { !it.read }`（:206）——按**当前服务全量**判定，不受主题筛选（`filter`）、未读筛选（`onlyUnread`）、搜索（`query`）影响；与执行范围（lambda 全量 readAll）一致，不会出现「筛选下看起来没未读但点了却全标」的错觉。✅
- `menu = false` 先于 `readAll()`——与其他菜单项（发送消息）同模式，菜单立即关闭、状态更新即时可见，顺序合理。
- 双勾图标 + 完整文字（leadingIcon contentDescription=null，文字承载读屏语义）——与 0.5.0–0.5.2 菜单版相同模式。

### 2. readAll 数据作用域 / 通知清理 — 零变动

调用处 lambda（MainActivity.kt:175）未动：demo → `demoRead = Demo.messages.map { it.id }`；真实 → `if (store.readAll()) messages.forEach { cancel }`（仅当前 server、保存成功才清通知）。`Store.readAll` 本轮未改。多服务隔离不变。✅

### 3. demo / 多选语义 — 正确

- demo：`messages` 含 demoRead 投影（:105），hasUnread 随 demo 已读状态联动；菜单项对 demo 可见可点（发送消息仍 `if (!demo)` 隐藏）——合理，demo 演示的就是已读交互。
- 多选：筛选行整块被多选操作行替换，菜单不可达，read-all 自然不可用——与 0.5.3 FAB 的 `!selecting` 条件等价，无回归。

### 4. FAB 移除与遮挡 — 干净

- `showReadAll` → `hasUnread` 重命名并仅服务菜单禁用态；TooltipBox/SmallFloatingActionButton 块整体删除（MainActivity.kt:261-263 区域），全文件无 TooltipBox/SmallFloatingActionButton 残留（已 grep 确认）。
- `contentPadding` 回落固定 `bottom = 24.dp`（:217）——88dp 额外留白随 FAB 一起移除，无「留白孤儿」。列表底部不再有任何悬浮遮挡物。
- material3 为通配 import（`androidx.compose.material3.*`），无未用 import 问题。
- 残留物仅两处无害结构：Box 包装层只剩单个 LazyColumn 子级；Box 内留一空行。纯外观，编译/lint 均过（见 P3-1）。

### 5. settings_smoke.py 断言链 — 可靠（tools/settings_smoke.py:8-19）

新链路六步：① 初始断言「全部标为已读」不出现在任何 text/content-desc（防入口外泄回标题栏/FAB）→ ② 点「筛选主题」开菜单 → ③ assert 所有主题（菜单已开）→ ④ assert + tap 全部标为已读 → ⑤ assert 未读 0 且该文本从节点树消失（菜单已关）→ ⑥ 重开菜单，收集所有同名节点断言 `enabled == 'false'`（无未读置灰）。

- 置灰断言依赖 uiautomator dump 的 `enabled` 属性（标准属性，Compose 语义树对 disabled 节点导出 enabled="false"）——机制可靠；`all()` 覆盖潜在多实例，比单节点断言稳。
- ⑥ 之后 tap「所有主题」关闭菜单并复位筛选，再进设置流程——链路闭合，无悬挂状态。
- tap_text 精确匹配 + last/first 语义沿用既有 helper；「全部标为已读」在菜单内唯一，无冲突。
- 本轮无设备未实跑，语法已 py_compile（Lead PASS 日志）。

### 6. 版本与文档一致性 — 六处对齐

`app/build.gradle.kts`（versionName 0.5.4 / versionCode 9）、README 首段与 0.5.4 段、README smoke 描述行（:112）、VERIFICATION 0.5.4 条目、docs/tls-compact-inbox.md 头注（标明 0.5.3 记录 + 0.5.4 变更方向）、release-notes/0.5.4.md——全部与实际行为一致。Release note 正确保留「手机 Clash 实际效果仍需复测 + 复制诊断码」的 TLS 边界，未宣称根因已确认；同时写清 readAll 范围与通知清理语义。0.5.3 tag 不重写的处置在 VERIFICATION 有记录。

## P3 建议（不阻断）

1. **MainActivity.kt:263 附近残留空行 + 单子级 Box 包装**——FAB 删除后的结构残余，下次触碰该文件时可顺手收掉（Box 可去、空行可删）。纯外观。
2. **smoke 置灰断言注释**——`enabled=='false'` 依赖 uiautomator 属性导出，未来若 Compose 版本改变语义树导出方式会静默失效（收集到空列表时 `read_all` 为空会先被 `assert read_all` 拦下，已有防护）——可不改。

## 证实 vs 待证实（设备边界）

**已证实（代码审查 + Lead gate 日志核对）：** 菜单项语义/禁用条件/作用域、FAB 与留白移除、readAll 零变动、smoke 断言逻辑、版本文档一致性、41 JVM 测试与 lint 通过。

**待证实（设备）：** 菜单内入口的实际手感与置灰观感、settings_smoke/reading_smoke 实跑；TLS/网络行为沿用 0.5.3，手机 Clash 效果仍未复测（本轮未改网络代码，维持既有边界）。

## 审查验证命令

- PASS — `./gradlew :app:testDebugUnitTest --offline --console=plain`；41 tests / 0 failure / 0 error；logs/release-0.5.4-review-test.log
（未重复 assembleDebug/lint/py_compile/diff-check；Lead 的 logs/release-0.5.4-build.log、release-0.5.4-tests.log、release-0.5.4-script-check.log、release-0.5.4-diff-check.log 已核对。）
