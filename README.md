# 栖信 Perch

一个用 **Kotlin · Jetpack Compose · Material 3** 编写的 Android ntfy 客户端。支持消息接收、交互按钮、附件、多服务器与发送分享。

## 体验首版

当前版本 **0.5.2**，Android 8.0 及以上可用。在 [GitHub Releases](https://github.com/marlonfan/perch-android/releases/latest) 下载 `Perch-0.5.2.apk`，直接覆盖安装已有体验版，保留连接配置和本地消息。首次打开有明确标记的演示消息，不会订阅或发布到任何远程主题。

0.5.2 将「全部标为已读」从主题菜单移回收件箱筛选区，直接显示文字按钮；后台接收在 VPN/TUN、DNS 或路由配置变化时重建连接，并分别提示 DNS、超时、连接及 TLS 错误。自动检查与 Reviewer 审查已通过，Clash 真机效果仍待复测，详见 VERIFICATION.md。

0.5.1 将 Emoji 标签移到消息标题前，与标题同字号；普通文字标签继续保留。消息详情的链接、回执等操作合并为圆角矩形操作区，显示动作图标、目标网站和执行状态，同一链接不再重复显示入口。附件操作也使用统一的小圆角按钮并可自动换行。

0.5.0 补齐 ntfy 消息交互：通知点击跳转，view/http/copy/broadcast 按钮，标签与图标，附件下载、图片预览与系统打开/分享，消息更新、清除与撤回。支持同时连接多个服务器、按服务切换收件箱、独立暂停；新增应用内发送、Android 文本/单文件分享、搜索及订阅提醒条件。旧版配置和消息自动迁移。按用户要求本轮不做模拟器测试，审查与自动化构建结果见 VERIFICATION.md。

0.4.0 修复关闭后台接收时 HTTPS 连接清理引起的闪退。详情右上角可删除单条消息；列表长按进入多选，可批量删除。删除前确认，只删除本机记录并清除对应系统通知，保留订阅和服务端消息；重连补收不会让已删除记录重新出现。

0.3.0 首页只保留筛选与消息列表：移除「收件箱」标题和常驻「已连接」条，断线或暂停时显示状态；底部是 64 dp 的纯图标导航，保留读屏名称和未读徽标。「全部标为已读」收进主题菜单，移除装饰性宣传语。沿用 0.2.0 的原生 Compose Markdown 排版。

后台接收改为监听默认网络：离线时不定时重试，网络恢复和退出 Doze 时重新连接补收；重复启动、已读与外观更新不会重建流。临时错误采用 2 秒至 5 分钟退避和随机延时，鉴权等非临时客户端错误慢重试，尊重服务端 `Retry-After` 秒数。没有轮询定时器、客户端额外心跳或常驻 WakeLock。

1. 点击首页「连接 ntfy」，或点底部右侧设置图标进入「消息服务」。
2. 输入已有 ntfy 的服务地址；如需鉴权，填入 ntfy **访问令牌**。支持 HTTPS、自托管 HTTP 和部署在子路径的服务。当前不提供用户名 / 密码表单。
3. 添加真实主题名和可选的显示名称。可为每个主题设置静音。
4. 允许系统通知。到「设置 → 发送测试通知」向第一个订阅主题发送一条消息；此操作需要该主题的发布权限。
5. 在收件箱查看消息；系统通知会直接打开对应的详情。
6. 如果需要长时间锁屏时接收，进入「设置 → 锁屏接收」，阅读耗电说明后自行选择是否允许电池优化豁免。应用只在点击「去设置」时打开 Android 系统确认，不会在启动时弹出请求。

公共服务的主题名应足够随机。带访问令牌的服务建议使用 HTTPS，HTTP 会明文传输。凭证只在应用内输入，不需要交给开发者或 AI。

## 已实现

- 暖白 / 墨绿视觉设计，完整浅色、深色、跟随系统，状态栏随应用主题切换。
- 收件箱：按日期分组、未读筛选、按主题筛选、单条已读、全部已读。
- 消息详情删除、长按多选、批量删除，持久化删除标记，防止缓存重放后重新出现。
- Markdown 详情：原生 Compose 标题、嵌套 / 有序列表、引用、链接、等宽代码块、删除线、表格，正文选择与复制。
- 按 ntfy 的 `markdown` 或 `content_type: text/markdown` 标记渲染；普通文本保留原文。
- 订阅添加、编辑名称、静音、移除确认，支持多个服务下各自的多个主题。
- ntfy HTTP JSON 长连接，自动重试，每个主题独立保存接收时间游标，重连时补收服务端仍保留的历史消息。
- 消息 ID 去重，最近 1000 条本地持久化，离线阅读。首次拉取的早期历史消息不批量提醒。
- 后台前台服务、常驻连接通知、开机恢复已开启的订阅。
- 主题与优先级对应系统通知渠道；优先级 1 只入收件箱，2 安静提醒，3 标准，4–5 高优先级。
- 点击通知打开具体消息并标记已读；锁屏通知默认采用 private 可见性。
- 访问令牌使用 Android Keystore AES-GCM 加密，关闭系统备份，不写日志。

## 消息交互和发送

- 消息自带 click 时，点击系统通知打开指定网站或 App；收件箱点击仍查看详情，详情也有“打开消息链接”。
- action 支持 view（打开链接）、http（回执/接口操作）、copy（复制指定内容）和 broadcast（Android 自动化）。消息详情显示执行状态与失败原因。
- 附件卡片显示名称、类型、大小及有效期；点“下载附件”后可预览、打开或分享。
- 在设置的“消息服务”管理多个服务器；当前选中的服务决定收件箱和订阅页，所有启用服务都在后台接收。
- 收件箱右下角按钮可发送消息，选择目标服务、主题、标题、正文、优先级、标签、Markdown、附件 URL 或系统文件。也可从其他 App 分享文字或单个文件至栖信，确认目标后点击发送；一次分享一个文件。
- 在主题菜单选择“搜索消息”搜索标题、正文和标签。编辑订阅可设置最低提醒优先级及标签条件；筛掉的提醒仍保存在收件箱。声音和振动由 Android 系统通知渠道设置。
- ntfy:// 主题链接需要确认后订阅，支持 display 名称与 secure=false（HTTP）。

## 当前边界

- 不含 AI 对话、远程任务、Gotify、FCM/厂商推送及 UnifiedPush 分发器；邮件、电话与定时投递由 ntfy 服务端负责。
- 附件下载到应用缓存（单文件最多 25 MB），下载后可预览图片、离线打开及分享；缓存总量约 100 MB，系统也可能回收。远端附件过期后无法重新下载，已下载的本地缓存仍可打开。自定义图标自动加载（最多 2 MB）。
- HTTP 按钮仅在点击后执行，不自动重试；成功后才按 clear 参数清除通知。超时结果可能未知，重试前请确认服务端状态。广播按钮需在对应订阅设置中明确开启。
- 系统通知最多显示三个操作按钮，消息详情保留全部有效按钮。HTTP 操作不继承订阅令牌；需要的 headers 由发送方在 action 中指定。服务令牌与 action 数据通过 Android Keystore 加密保存。
- 多服务器各自接收与暂停；设置中的后台接收开关作用于当前服务。切换服务不会清空消息，移除服务会移除其订阅而保留本地消息。
- 更新及控制事件依赖服务端支持 sequence_id/message_clear/message_delete；本地删除仍只影响本机。
- 使用常驻连接，不含 FCM 或厂商推送。未获电池优化豁免时，Doze 可能暂停网络；退出后自动重连补收。有豁免也仍受厂商后台策略和网络影响。设置中可检查豁免与省电状态；授权由 Android 系统确认，应用不会自行修改系统白名单。
- 系统「强行停止」后，需要再次打开应用才能恢复接收。开机自动恢复也受系统策略影响。
- 断线补收依赖 ntfy 服务的缓存保留时间，不能恢复服务端已过期的消息。
- 表格和代码块支持局部横滑。代码着色是轻量词法规则，不是语言服务或完整语法解析。
- 当前是中文首版，未做应用商店发布与长期真机耗电测试。

## 构建

用 Android Studio 打开本目录，安装 Android SDK 36，选择 JDK 17 或以上。SDK 路径由 Android Studio 生成的 `local.properties` 或 `ANDROID_HOME` 环境变量提供。

```powershell
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`。

## 自动发布

`.github/workflows/android-release.yml` 在推送 `v*` 标签时运行 37 项单元测试、Release Lint、签名构建与 APK 签名验证，然后创建 GitHub Release，附 APK 和 `SHA256SUMS.txt`。标签必须与 `app/build.gradle.kts` 中的版本相符，并提供 `release-notes/<版本>.md`。手动运行工作流只生成构建产物。

签名材料保存在仓库 Actions Secrets：`ANDROID_SIGNING_KEYSTORE`（Base64 keystore）、`ANDROID_SIGNING_STORE_PASSWORD`、`ANDROID_SIGNING_KEY_ALIAS`、`ANDROID_SIGNING_KEY_PASSWORD`。构建机临时解码，结束后清理；源码和构建产物不包含 keystore。当前沿用首版体验签名以支持覆盖安装。

本地签名 Release 使用环境变量 `PERCH_KEYSTORE_PATH`、`PERCH_STORE_PASSWORD`、`PERCH_KEY_ALIAS`、`PERCH_KEY_PASSWORD`，然后运行 `:app:assembleRelease` 和 `python tools/prepare_release.py`，产物在 `dist/`。没有签名环境变量时只生成 unsigned Release，打包脚本会拒绝发布。

## 验证

37 项单元测试覆盖服务地址 / 主题校验、ntfy 事件解析、Markdown 结构与摘要、错误退避、服务端限流、删除持久化与缓存重放，以及网络配置变化、接收任务替换和连接错误分类。

`tools/ntfy_fixture.py` 是仅绑定本机的 ntfy 协议测试服务，不是生产服务端。`tools/android_smoke.py` 在显式选择的 Android 模拟器上操作真实 APK，覆盖页面、主题切换、服务接入、令牌加密、后台接收、系统通知跳转、事件去重、重启补收、离线阅读和暂停偏好。它会改变测试设备上栖信的配置，因此应只用于专用模拟器。

```powershell
python tools/ntfy_fixture.py
# 另一个终端，先在干净的模拟器上安装并打开 APK
$env:PERCH_ADB = '你的 adb 路径'
$env:PERCH_DEVICE = 'emulator-5556'
python tools/android_smoke.py
python tools/android_smoke.py integration
```

具体执行结果见 `VERIFICATION.md`。界面截图在 `artifacts/`。

`tools/reading_smoke.py` 补充检查原生 Markdown 标题、嵌套列表、宽表格横滑、代码块与 320 dp 窄屏。只允许在**专用模拟器的演示模式**运行；它会临时写入本地排版样张，随后恢复原偏好。已有真实 ntfy 配置的设备会被拒绝。

`tools/power_smoke.py` 只允许独立的 `emulator-5556`，使用本机 8788 端口协议 fixture，检查连接复用、锁屏省电、获豁免的强制深度 Doze、空闲重连次数、断网等待、恢复补收与 HTTP 401 重试。脚本退出时恢复原演示配置、网络与电源设置。模拟器 CPU / batterystats 是逻辑观察，不能换算为实体手机的每日耗电。

`tools/settings_smoke.py` 在同一专用演示设备验证纯图标导航、收件箱直接显示的「全部标为已读」，以及 Android 原生电池豁免确认的打开与取消。

`tools/deletion_smoke.py` 在专用模拟器检查单条删除、确认取消、多选全选、批量删除、系统通知清理、重启缓存重放及新消息接收，并恢复原演示偏好。
