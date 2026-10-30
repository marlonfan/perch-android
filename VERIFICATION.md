# 验证记录

## 0.5.4 顶部搜索与全部已读菜单

2026-10-30，基线 fc7f4ad（v0.5.3）。用户要求取消独立悬浮框，把全部已读放进主题按钮展开菜单。移除 FAB 及其底部额外留白，菜单保留双勾和完整文字，无未读时禁用；readAll lambda 与当前服务全量范围不变，不修改 TLS 或网络逻辑。versionName 0.5.4 / versionCode 9。本轮不运行模拟器、不操作手机、不向主题发送消息。本地 Debug 构建、41 项 JVM 测试与 Release Lint 通过，日志：logs/release-0.5.4-build.log、logs/release-0.5.4-tests.log。Python 脚本语法与 git diff --check 通过，日志：logs/release-0.5.4-script-check.log、logs/release-0.5.4-diff-check.log。后续按用户要求缩小全部/未读切换并新增同一行常显搜索框，搜索标题、正文及标签，支持清除和无结果提示。前述构建日志只覆盖菜单调整，搜索变更后的 Debug 构建、41 项 JVM 测试与 Release Lint 通过，日志：logs/inbox-search-build.log、logs/inbox-search-tests.log；脚本语法与diff检查通过，日志：logs/inbox-search-script-check.log、logs/inbox-search-diff-check.log。需求与布局见 docs/inbox-search.md，Reviewer（Orca Worker）对菜单与搜索合并的最终 diff 审查通过，无 P1/P2 阻断；独立复跑 41 项 JVM 测试通过，日志：logs/inbox-search-review-test.log。最终报告：docs/review-read-all-menu.md。发布流程由 v0.5.4 标签触发 GitHub Actions 执行签名构建及附件上传，结果以工作流与 Release 附件为准。

## 0.5.3 发布门禁

2026-10-30。versionName 0.5.3 / versionCode 8；网络回退、TLS 诊断与紧凑收件箱经 Reviewer（Orca Worker）独立审查通过，无 P1/P2 阻断。签名构建、APK 签名验证与 GitHub Release 上传由标签 v0.5.3 对应的 Android Release 工作流执行，最终发布结果以该工作流及 Release 附件为准。手机 Clash/TLS 效果仍待设备复测。

- PASS — `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintRelease --offline --console=plain`；版本号更新后的 Debug 构建、41 项 JVM 测试与 Release Lint；日志：logs/release-0.5.3-build.log。

## 0.5.3 TLS 后续排查与紧凑收件箱

2026-10-30，基线 v0.5.2。用户确认 Clash 在手机上运行，浏览器可以打开同一服务。开发机完成目标 HTTPS 系统信任链、主机名校验及健康接口探测（HTTP 200），日志：logs/ntfy-marlon-tls-probe.log、logs/ntfy-marlon-health-*.log。该结果不代表手机 Clash 路径；本轮没有 Android 设备，未复现真机异常链，不宣称已确认其根因或完全修复。细节见 docs/tls-compact-inbox.md。

撤回新增的显式 Network DNS/socket 绑定，恢复 Android 默认联网路径；保留网络变化重连与每会话独立池。细分 TLS 证书链、有效期、身份、协议、握手和 IO 错误，并在设置保留可复制的最近失败摘要；保持原证书与主机名校验，不拼接异常原文。收件箱改为单行全部/未读加主题筛选图标，右下仅在有未读且非多选时显示全部已读悬浮图标；发送消息移入菜单。

- EXPECTED FAIL — `./gradlew :app:testDebugUnitTest --tests app.perch.ReceptionPolicyTest --offline --console=plain`；新增的两项错误分类回归在旧逻辑下失败，证明原提示未区分证书原因与普通握手中断；日志：logs/tls-classification-red.log。这不是手机故障的复现。
- PASS — `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintRelease --offline --console=plain`；Debug APK、41 项 JVM 测试（0 failure / 0 error）与 Release Lint；日志：logs/tls-compact-final-build.log、logs/tls-compact-final-test-results.log。
- PASS — `python3 -m py_compile tools/settings_smoke.py tools/reading_smoke.py`；日志：logs/tls-compact-script-check.log。
- PASS — `git diff --check`；日志：logs/tls-compact-diff-check.log。

更新两份模拟器脚本的悬浮图标与主题筛选断言，本轮未运行模拟器或操作手机，未向主题发送消息。UI 渲染、Clash 证书行为与默认联网回退效果待设备验证。Reviewer（Orca Worker）审查通过，无 P1/P2 阻断；独立复跑 41 项 JVM 测试通过，日志：logs/tls-compact-review-test.log。报告：docs/review-tls-compact.md。

## 0.5.2 发布门禁

2026-10-30。versionName 0.5.2 / versionCode 7；两项功能均经现有 Reviewer（Orca Worker）独立审查通过，无 P1/P2 阻断。签名构建、APK 签名验证与 GitHub Release 上传由标签 v0.5.2 对应的 Android Release 工作流执行，最终发布结果以该工作流及 Release 附件为准。

- PASS — `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintRelease --offline --console=plain`；更新版本号后的 Debug 构建、37 项 JVM 测试与 Release Lint；日志：logs/release-0.5.2-build.log。

## 0.5.2 收件箱「全部标为已读」入口恢复

2026-10-30。原入口藏在「所有主题」下拉菜单；现移到收件箱筛选区下方，右侧直接显示带 DoneAll 图标的「全部标为已读」文字按钮，无未读时禁用。主题筛选与操作使用独立一行，长主题名可省略，避免与全部/未读筛选争抢窄屏宽度。沿用当前服务的 Store.readAll、演示状态更新与成功后通知清理，不修改其他服务的已读状态。

- PASS — `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintRelease --offline --console=plain`；Debug APK、37 项 JVM 测试与 Release Lint；日志：logs/read-all-build.log。
- PASS — `git diff --check`；日志：logs/read-all-diff-check.log。

更新 tools/settings_smoke.py 为直接点击可见按钮。本轮没有设备，未运行该模拟器脚本；320 dp 实际渲染及交互仍需设备复测。Reviewer（Orca Worker）审查通过，无 P1/P2 阻断；报告：docs/review-read-all.md。

## 0.5.2 Clash / TUN 网络变化修复

2026-10-30。需求、应用侧回归复现与验证边界见 docs/network-reception.md。未连接 Android 设备、未启动模拟器、未改手机或 Clash 配置、未向 ntfy 主题发送消息。

- EXPECTED FAIL — `./gradlew :app:testDebugUnitTest --tests app.perch.DefaultNetworkStateTest --offline --console=plain`；原有回调状态逻辑的两项回归测试均失败，捕获同网络 DNS 变化未更新接收状态、DNS/路由就绪前开始连接；日志：logs/clash-regression-red.log。
- PASS — `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintRelease --offline --console=plain`；Debug APK、37 项 JVM 测试（0 failure / 0 error）与 Release Lint；日志：logs/clash-fix-final-build.log、logs/clash-fix-test-results.log。
- PASS — `git diff --check`；日志：logs/clash-fix-diff-check.log。

修复默认网络 LinkProperties 变化监听、配置就绪前连接、跨网络复用连接池与 DNS/socket 网络不一致风险；新增 DNS / 超时 / 连接 / TLS 错误分类。需要真机复测 Clash 已开启启动接收，以及运行中切换 TUN、DNS、Wi-Fi/移动网络；当前不宣称已确认用户手机的唯一根因或已完成真机验证。

Reviewer（Orca Worker）审查通过，无 P1/P2 阻断；独立复跑 37 项 JVM 测试通过，日志：logs/clash-review-test.log。报告：docs/review-clash-network.md。真机与 Clash 的实际回调、Fake-IP 解析及用户故障归因仍待设备复测。

## 0.5.1 标题 Emoji 与消息操作区

2026-10-29，基线 v0.5.0。需求与布局决定见 docs/message-presentation.md。按用户要求未运行本地模拟器、未操作实体手机、未向 ntfy 主题发送消息。

- PASS — ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintRelease --offline --console=plain；31 项 JVM 测试（0 failure / 0 error）、Debug APK 与 Release Lint；日志：logs/message-ui-build.log、logs/message-ui-test-results.log。

仅修改消息展示：Emoji 标签前置并跟随标题字号；普通文字标签保留；操作区域使用有按钮语义的全宽操作行、明确状态、失败原因和目标主机提示，附件按钮支持换行。没有改写已存数据或请求执行逻辑。

Reviewer（Orca Worker）审查通过：0 项 P1 / P2，无发布阻断问题；报告见 docs/review-0.5.1.md。3 项 P3 外观观察接受现状（标题原本自带 Emoji 可能重复、失败提示略重复、非网站链接使用通用说明）。发布 APK 由 GitHub Actions 执行单元测试、Lint、签名构建及签名验证。

## 0.5.0 ntfy 兼容性补齐

2026-10-29，基线 2d3cd4b。按用户要求取消本地模拟器验证，未启动模拟器或操作实体手机。

- PASS — ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug，27 项 JVM 测试、Debug APK 与 Lint；日志：logs/compatibility-build.log。
- PASS — ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintRelease --offline，27 项测试、Debug APK 与 Release Lint；日志：logs/compatibility-final-build.log。
- PASS — git diff --cached --check；日志：logs/compatibility-staged-check.log。

新增 13 项协议与模型测试覆盖完整字段、四类操作、动作状态、迁移与加密边界、跨服务/主题序列隔离、更新/清除/撤回、同秒已处理事件重放、旧事件顺序、本地删除后的后续更新、提醒条件、HTTP 默认方法及资源凭证边界。

本轮不声称已做 Android 真机/模拟器交互回归。通知按钮的设备行为、分享文件接收、系统打开附件及厂商后台限制仍需实际使用观察。发布前由现有 Orca Worker reviewer 进行代码审查，完成后补充审查记录；发布 APK 由 GitHub Actions 执行单元测试、Release Lint、签名构建及签名验证。

审查修正：处理 HTTP 操作 Activity 生命周期、全部已读通知清理、发送目标切换、编辑服务不切换收件箱、加解密失败反馈与密文保留、最低提醒优先级语义，补充订阅链接边界与 HTTP 方法校验。审查及逐项取舍见 docs/review-0.5.0.md。

- PASS — ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintRelease --offline，31 项 JVM 测试（0 failure / 0 error）、Debug APK 与 Release Lint；日志：logs/review-fixes-final-build.log。

2026-10-29，Reviewer（Orca Worker）复审通过，无新增发布阻断问题；完整复审结论见 docs/review-0.5.0.md。

未压缩长期防重放元数据：保留处理记录避免服务端缓存复活旧消息；安全压缩需要独立的主题水位和同秒边界设计。本版已获 Reviewer 复审接受延期，作为非阻断的已知限制。

## 0.4.0 删除与关闭接收修复

2026-10-29。实体手机崩溃日志确认：`NotificationService.onDestroy` 在主线程调用 `ConnectionPool.evictAll`，关闭 TLS 连接时触发 `NetworkOnMainThreadException`。改用独立于接收协程的 OkHttp executor 清理连接池。日志：`logs/phone-crash-before-fix.log`。按用户要求停止后续手机调试，未在实体手机安装或回归验证 0.4.0。

- PASS — `./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`，14 项 JVM 测试与 Lint；日志：`logs/delete-build.log`。
- PASS — `python tools/deletion_smoke.py`，独立 `emulator-5556`：详情删除与取消、长按多选、全选切换、批量删除、系统通知清理、订阅保留、进程重启后缓存重放不复活、新消息正常接收；日志：`logs/delete-interaction.log`。
- PASS — 同一脚本中 HTTP 接收连续开关三次，进程稳定、无新增崩溃；HTTPS 根因通过实体手机已有崩溃堆栈定位，此项模拟器检查不代替 HTTPS 真机回归。
- PASS — `./gradlew.bat :app:assembleRelease :app:lintRelease :app:testDebugUnitTest`，签名 Release、Lint 和 14 项测试；日志：`logs/release-build.log`。
- PASS — `python tools/prepare_release.py`、`apksigner verify --verbose --print-certs` 与 `aapt dump badging`：版本 0.4.0 / code 4、不可调试、签名有效且证书与 0.3.0 一致；日志：`logs/release-verification.log`。

删除标记按服务地址和消息 ID 区分，与消息一起持久化，兼容旧版本本地数据。详情有单条删除确认，长按或主题菜单可进入选择模式；默认收件箱不增加常驻工具栏。

## 0.3.0 精简界面与后台接收

2026-10-29，独立 `PerchReview` / `emulator-5556`，Android 15 / API 35。已有真实 ntfy 配置的 `emulator-5554` 未被操作。

- PASS — `./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`，11 项 JVM 测试及 Lint；日志：`logs/quiet-build-final.log`。新增退避上限、鉴权慢重试、429 / Retry-After 和大数边界测试。
- PASS — `apksigner verify --verbose --print-certs`；当前 APK 签名有效，证书 SHA-256 与 0.2.0 相同，可覆盖安装；日志：`logs/quiet-signature.log`、`logs/quiet-previous-signature.log`。
- PASS — `python tools/android_smoke.py`：无首页标题、纯图标导航、浅深主题、消息详情、代码复制、订阅和设置；日志：`logs/quiet-preview.log`。
- PASS — `python tools/reading_smoke.py`：嵌套 / 有序列表、标题层级、行内代码、宽表格横滑与 320 dp 收件箱 / 详情；日志：`logs/quiet-reading.log`。截图使用 `artifacts/quiet-` 前缀，保留前版截图。
- PASS — `python tools/settings_smoke.py`：纯图标导航、菜单内全部已读、原生 Android 电池豁免确认可打开并取消，取消后系统白名单未改变；日志：`logs/quiet-settings.log`。

界面：底部 64 dp 纯图标导航保留无障碍名称；首页移除「收件箱」标题、正常连接条和重复页尾。「全部标为已读」移入主题菜单。演示提示缩为单行，测试通知移除宣传语。连接完整状态与锁屏接收入口位于设置。

接收：默认网络回调驱动，无网络时不定时重试；配置流去重，消息入库、已读、外观更新和重复启动不重建 HTTP 流；网络切换 / 恢复、退出 Doze 时取消旧 socket 并重连补收。错误退避为 2 秒至 5 分钟，附加正向随机延时；非临时 4xx 至少 5 分钟，尊重服务端 Retry-After 秒数。未增加客户端心跳、轮询任务或常驻 WakeLock。

锁屏设置只在用户点击「去设置」时打开 Android 电池优化豁免确认；界面显示当前豁免及省电状态，不自动修改系统白名单。

`PERCH_DEVICE=emulator-5556 python tools/power_smoke.py` 通过，日志：`logs/quiet-power.log`，本机 8788 端口 fixture，没有向外部主题发消息：

- 普通接收：0.68 秒入库并可见系统通知；锁屏 + 省电模式：0.14 秒；获电池优化豁免的强制深度 Doze：0.56 秒。计时包含 ADB 观测延迟，是本机单次样本，不是公网送达 SLA。
- 已读更新与重复启动复用同一连接，未增加流请求。
- 60.2 秒锁屏空闲观察：0 次重连、应用进程增加 3 个 CPU scheduler ticks。fixture 心跳间隔 45 秒。原始系统记录：`logs/quiet-batterystats.txt`、`logs/quiet-power-state.txt`；记录确认处于未充电、完整省电模式。
- 断网 8 秒服务端未收到流请求，恢复网络后 0.89 秒补收缓存消息。
- HTTP 401 后 8 秒未快速重试；5 分钟退避值由 JVM 测试验证。
- 退出测试后恢复独立模拟器的演示偏好、网络、电源设置及原豁免状态。

测量边界：本轮使用本地协议 fixture 与模拟器强制电源状态；它不能模拟真实无线电和硬件休眠，CPU ticks / batterystats 不代表实体手机 mAh 或一夜耗电。未获电池优化豁免时仍可能被 Doze 暂停联网；补收依赖 ntfy 缓存。厂商后台限制及长期真机续航没有被本轮短时测试覆盖。

## 0.2.0 排版更新

2026-10-29：构建与 8 项单元测试、Android Lint 通过。命令：`./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`；日志：`logs/redesign-build-final.log`。

标题栏改为 56 dp 单行布局，字号 22 sp；移除顶部 Logo 与装饰性副标题，设置底部仅保留版本信息。消息卡片标题 17 sp、摘要 14 sp，正文 16 sp / 28 sp 行高。Markdown 使用 CommonMark 解析与原生 Compose 块布局，不再使用 TextView 的默认块样式。

页面与阅读检查在独立的 Android 15 / API 35 `PerchReview` 模拟器运行。已有模拟器的 ntfy 配置被保留，没有为了截图清除其数据。阅读样张是关闭网络接收的本地测试数据，仅写入独立测试设备，检查结束后恢复其演示配置。

- PASS — `python tools/android_smoke.py`：收件箱、详情、订阅、设置、浅色与深色主题、代码复制；日志：`logs/redesign-preview-final.log`。
- PASS — 嵌套列表、有序列表起始编号、标题层级、行内代码、宽表格横向滚动与 Python 代码显示；日志：`logs/redesign-reading.log`。
- PASS — `python tools/reading_smoke.py`：320 dp 收件箱、筛选、消息详情可操作，页头无 Logo 行；检查后恢复模拟器原始尺寸与演示配置；日志：`logs/redesign-reading.log`。
- PASS — `apksigner verify --verbose`：当前 debug APK 签名验证；日志：`logs/redesign-signature.log`。

本轮不修改 ntfy 接收与存储协议。0.1.0 的通知联调结果保留在下面的历史记录中。

## 0.1.0 历史记录

验证日期：2026-10-29。执行对象：此目录构建的 `app.perch` debug APK。

## 构建与静态检查

- PASS — `./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`；日志：`logs/build-final.log`。
- 5 项 JVM 单元测试通过；Android Lint 通过。Lint 的非阻断建议仍保留在本机构建报告中。

## 页面与排版

Android 15 / API 35，x86_64，Medium Phone 模拟器，1080 × 2400、420 dpi。

- PASS — 收件箱、Markdown 消息详情、订阅、设置可进入。
- PASS — 标题、列表、引用、代码块、普通表格实际渲染。
- PASS — 浅色 / 深色主题切换，状态栏图标同步切换。
- PASS — 320 dp 窄屏下收件箱筛选、底部导航、消息详情可见可操作；截图为 `11-inbox-320dp.png`、`12-detail-320dp.png`，日志：`logs/compact.log`。
- 自动化命令：`python tools/android_smoke.py`；日志：`logs/preview-final.log`。

## 通知与持久化

使用绑定到 `127.0.0.1:8787` 的本地 ntfy 协议 fixture，通过模拟器的 `10.0.2.2` 访问。所有消息均在本机产生，没有向公共主题发布。

- PASS — 从 UI 填写服务地址、令牌、主题并建立 JSON 消息流。
- PASS — 访问令牌未以明文出现在应用 SharedPreferences 中。
- PASS — App 返回系统桌面后，仍接收并保存新消息，产生系统通知。
- PASS — 点击系统通知打开正确消息，并持久化已读状态。
- PASS — 重放同一消息 ID，不重复入库。
- PASS — 进程停止期间产生的消息，在用户重新打开应用后通过历史游标补收。
- PASS — 关闭接收、重启应用、关闭模拟器网络后，仍能打开本地消息正文。
- PASS — 暂停接收的偏好在进程重启后保留。
- 自动化命令：`python tools/android_smoke.py integration`；日志：`logs/integration-final.log`。

## 尚未验证

尚未连接用户自己的 ntfy 服务，也未在实体手机上验证厂商后台限制、长时间锁屏 / Doze、开机恢复、通知延迟与耗电。当前不据此承诺所有手机都能即时送达。

`artifacts/` 中的截图均来自本次模拟器运行；不是设计稿。
