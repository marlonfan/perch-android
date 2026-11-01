# 开源准备 — Reviewer 审查报告

2026-11-01。Reviewer（Orca Worker）审查 f8df7df（0.5.5）→ 71de762 的开源准备提交（仅文档/许可证/忽略规则/署名 assets，+552 行 13 文件），并独立复核公开历史敏感材料扫描。只读审查，未编辑功能、未操作 GitHub。通知/updater 功能由独立 worktree 开发，不在本轮范围。

## 结论

**通过（无 P1/P2 阻断）**，附 2 项发布前待办（P2'，见「待证实边界」——均为发布动作而非本提交缺陷）。Standards 与 Spec 双轴均达标。

## Spec 符合度（docs/priority-update-spec.md「开源和交付」）

- Apache-2.0 LICENSE（201 行标准文本）+ NOTICE ✅；贡献（CONTRIBUTING.md）、安全（SECURITY.md）、隐私（PRIVACY.md）、第三方（THIRD_PARTY_NOTICES.md）齐备 ✅；README 顶部链接五份文档 ✅。
- 第三方许可证与署名保留：commonmark BSD-2-Clause 原文、ntfy Apache-2.0 原文 + 未修改映射（ntfy-emoji.json，Resources.kt:20 运行时加载）、APK 内打包完整 notices 目录 ✅。
- 公开历史敏感材料检查：Lead 启发式扫描 + 本 worker 独立复核（下详）✅。
- 「保留现有筛选/搜索/菜单、不改 TLS/DNS」——本提交零代码改动 ✅。
- 「不运行模拟器、不操作设备、不发测试消息」——遵守 ✅。

## Standards 审查逐项

### 1. 许可证与署名正确性

- `LICENSE` 与 `app/src/main/assets/notices/Apache-2.0.txt` 逐字节一致（diff 验证）；`NOTICE` 与 `Perch-NOTICE.txt` 一致——APK 内外文本同步 ✅。
- commonmark-LICENSE.txt：Atlassian BSD-2-Clause 全文（2015-2016 版权）✅；THIRD_PARTY_NOTICES.md 正确标注 commonmark-java 及 tables/strikethrough 扩展为 BSD-2-Clause（与 build.gradle.kts:38-40 三个坐标一致）。
- ntfy Emoji 映射：THIRD_PARTY_NOTICES 与 NOTICE 均写明来源 v2.34.0、Copyright ntfy contributors、Apache-2.0 选项——ntfy 上游确为 Apache-2.0/GPL 双许可，选 Apache-2.0 合规且声明「未修改」属实（原样打包）✅。「栖信为独立兼容客户端，与 ntfy 官方项目没有隶属关系」的免责声明在 NOTICE/THIRD_PARTY/README 三处一致 ✅。
- JSON-java 20240303：标注「Public Domain（JVM 测试，不入 APK）」——准确：`testImplementation`（build.gradle.kts:43），不进 APK，无需在 APK notices 中署名 ✅。JUnit 4 EPL-1.0 同为 testImplementation，表格注明「测试」✅。
- 表格「完整依赖版本以 Gradle 配置与解析结果为准」的兜底措辞合理（compileSdk/AGP/Kotlin 插件版本在根构建文件，未逐一列出——P3-2）。
- 潜在遗漏核对：APK 实际打包的运行时依赖（Compose BOM 全家、activity-compose、lifecycle、core-ktx、material-icons-extended、OkHttp/Okio）均被「AndroidX / Jetpack Compose / Material 3」「OkHttp / Okio」条目覆盖；Kotlin stdlib 入 APK 有 Kotlin 条目覆盖 ✅。assets 目录仅 notices + ntfy-emoji.json，无其他未署名素材 ✅。

### 2. PRIVACY.md 与代码一致性

逐条对照代码验证：

- 「访问令牌与敏感 action 数据使用 Android Keystore 加密」——与 Store 的 vault::encrypt 持久化路径一致；「消息正文应用私有存储，未另作应用层加密」——与消息明文入 prefs 的实现一致，如实 ✅。
- 「应用关闭 Android 系统备份」——`android:allowBackup="false"`（AndroidManifest.xml:9）✅。
- 「附件与自定义图标连接发送者指定远端地址」「广播按钮需订阅明确允许」——与 Resources 图标加载、广播白名单逻辑相符 ✅。
- 「未集成广告、分析统计或自动崩溃上报 SDK」——依赖表无此类库 ✅。
- 「应用内更新功能启用后……GitHub API / Releases CDN，不携带 ntfy 凭证」——前瞻性描述 updaters worktree 的 spec 要求；当前代码无更新功能，文档用「启用后」措辞隔离，不构成虚假陈述。功能合并后需回头核对该段与实现一致（记入下轮审查项）✅/⚠。
- `usesCleartextTraffic="true"` 与「HTTP 服务会明文传输」一致（自托管 HTTP 需求，明示风险）✅。

### 3. 公开历史敏感材料（独立复核）

Lead 扫描 133 blob，3 项 credential-url 命中均为测试 fixture。本 worker 独立执行五类扫描（logs/open-source-review-independent-scan.log）：

- 私钥/常见 token 前缀（BEGIN PRIVATE KEY、ghp_、xox、sk-、pk_、AIza）：**0 命中**。
- 个人域名模式（marlon）：blob 内容 **0 命中**（仅 commit author 邮箱 i@marlon.life，属作者自愿署名，非泄露）。
- 可疑真实 ntfy 主题 URL（源码内 8+ 字符路径，白名单外）：**0 命中**。
- 被跟踪的敏感文件（keystore/jks/p12/pem/local.properties/.env/secrets）：**0 命中**（logs/ 未被跟踪 ✅）。
- 3 项 audit 命中逐一 cat-file 核对：`https://one.example/prefix` + 字面量 "secret"、`https://name:secret@example.com`——全部为 example 域测试数据 ✅。

两次独立扫描交叉印证。边界：启发式（含本 worker 的）都不是安全保证——P3-3 建议发布前再做一轮人工抽查；未扫描 commit message 正文（本 worker 抽查最近提交主题无敏感词，历史 13 提交全为常规发布/修复描述）。

### 4. 忽略规则与 secrets 防护

- .gitignore 新增 `.cindy-worktrees/`（当前存在 amazing-hamilton、jolly-jepsen 两个功能 worktree——不会进入公开仓库 ✅）、`.env`/`.env.*`、`*.p12`、`*.pem` ✅；原有 `*.jks`/`*.keystore`/`local.properties` 保留 ✅。
- 签名密钥经 Actions Secrets（SECURITY.md 声明），签名配置读 `PERCH_KEYSTORE_PATH` 环境变量（build.gradle.kts），仓库内无密钥材料 ✅。
- SECURITY.md 的泄露处置指引（先吊销轮换、删文件不撤销历史）正确 ✅。

### 5. 文档质量

- CONTRIBUTING.md：构建命令真实（Gradle wrapper + 三任务）、JDK17/SDK36 与构建文件一致、测试设备警示（smoke 只限专用设备）、提交规范与 CLA 式授权声明（Apache-2.0）✅。
- README 开源声明行 + 「独立 ntfy 兼容客户端」定位清晰；THIRD_PARTY_NOTICES 的表格注明「完整依赖以 Gradle 解析为准」避免版本漂移失实 ✅。

## P2'/发布前待办（非本提交缺陷，属发布动作）

1. **GitHub private→public 切换时**：确认仓库仅含本仓库历史（无 fork 杂质），Secrets 不随可见性变化暴露（Actions Secrets 本就不公开，但应例行确认）；Security 私密报告入口在 public 后按 SECURITY.md 描述启用。
2. **功能合并后**：updater 交付时回审 PRIVACY.md「应用内更新」段与实现一致性（本轮为前瞻措辞，已如实标注）。

## P3（不阻断）

1. THIRD_PARTY_NOTICES 未列 AGP/Kotlin Gradle 插件版本条目（Gradle 行只覆盖构建工具本身）；「以 Gradle 配置为准」兜底已覆盖，可后续补。
2. CONTRIBUTING 未提 `--offline` 不可用于首次构建——已写「首次构建需要联网」，足够。
3. 历史扫描可在发布前对 13 条 commit message 全文再跑一次关键词（本 worker 抽查无命中）。

## 证实 vs 待证实

**已证实**：许可证/署名文本一致性、依赖表与构建配置对应、PRIVACY 与现有代码逐条一致、历史双扫描零敏感命中（3 项 fixture 确认）、忽略规则覆盖 worktrees/secrets、无密钥文件被跟踪。

**待证实（发布时）**：public 切换后的仓库状态与 Security 入口；updater/通知功能合并后的 PRIVACY/NOTICE 回审（另轮）。

## 审查验证命令

- PASS — git blob 历史独立扫描（token/私钥/域名/主题/敏感文件五类）；0 敏感命中、3 fixture 确认；/Users/admin/Dev/code/java/perch-android/logs/open-source-review-independent-scan.log
（纯文档提交，未运行构建/测试——Lead 已确认 f8df7df 基线上构建通过，本提交无代码改动。）
