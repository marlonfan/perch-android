# 0.5.4 后续：FilterPill 居中修正 + TLS 排查边界 — Reviewer 审查报告

2026-11-01。Reviewer（Orca Worker）审查基线 86e8475（v0.5.4）之上的未提交 diff：`MainActivity.kt`（仅 FilterPill）、`VERIFICATION.md`、新增 `docs/handshake-followup.md`。只读审查，未修改实现、未连接设备。本报告不含用户域名。

## 结论（两部分独立）

1. **UI 代码审查：通过，无 P1/P2 阻断**（P3 见文末；居中是否如用户所感需设备确认）。
2. **TLS：尚未解决，无修复可报**——本轮零网络代码改动，开发机对照不覆盖手机 Clash 路径；缺手机侧证据（见「TLS 缺口」）。

## 一、FilterPill 垂直居中修正（MainActivity.kt:269-276）

**诊断正确**：旧实现 Text 直接承接 Surface 的 `heightIn(min=48dp)`，Text 自身高度只有内容高（18sp 行高 + vertical 8dp padding = ~34dp），在 48dp Surface 内默认 top-start 对齐——文字偏上且 padding 造成视觉不对称，与用户「没居中、拥挤」反馈吻合。`textAlign = TextAlign.Center` 只作用于水平行内对齐，与垂直无关。诊断成立。

**修法正确**：

- `Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center)` —— Box 撑满 Surface 内容区（min 48dp），文字在两轴真正居中；`fillMaxWidth` 保证水平方向也由 Center 对齐（不再依赖 textAlign）。
- 移除 Text 的 `padding(horizontal=4, vertical=8)`（旧偏移源）+ `softWrap = false` + `overflow = Clip` + 保留 `maxLines = 1` —— 两字短标签（全部/未读）在任何字体缩放下单行；超宽时 Clip 而非省略（两字标签不会触发；若未来换长标签，Clip 比 Ellipsis 在 pill 语境更干净，但见 P3-1）。
- **约束不影响周边**：对外 API（modifier、onClick、heightIn 位置）不变——调用处 `Modifier.weight(1f).semantics { contentDescription = "全部 N" }`（:236-237）不变，104dp 筛选组内两个 pill 各 ~50dp 宽度分配不变，搜索框 weight(1f) 与主题图标预算不变。
- **点击范围**：Surface(onClick) 的可点区域 = Surface 尺寸（weight 分配的 ~50dp × 48dp），内层 Box 只是内容排布，不缩小命中区。✅
- **读屏**：数量仍在 pill 的 contentDescription（未动），可见标签仍「全部/未读」两字——与 0.5.4 决策一致。✅

**边界措辞核对**：docs/handshake-followup.md 明确「未执行设备像素或截图验证，不能把代码约束检查当成实际渲染结果」——准确。居中由布局约束推导成立（Alignment.Center 是确定性语义，非启发式），但用户感知的「拥挤」是否完全缓解、系统字体缩放（sp 随缩放、48dp 固定）下的观感确实只能设备确认。措辞不越界。✅

## 二、TLS 排查边界（docs/handshake-followup.md + VERIFICATION.md）

**文档如实区分开发机对照与手机路径**：

- 开发机 OkHttp 4.12.0（同款）三种模式（默认/TLS1.2/TLS1.3）证书+主机名校验通过、HTTP 200、协商 h2——日志核对属实（logs/handshake-okhttp-probe.log）。文档正确将其定位为**服务端对照**，明确「不能复现或排除手机 Clash 路径的问题」。✅
- Python TLS1.3 `ValueError` 解释为本地 SSL 实现不支持、以 Java 对照为准——与日志一致，避免了「服务端拒绝 TLS1.3」的误读。✅
- 「不基于猜测强制 TLS 1.2/HTTP1.1/DNS/代理、不放宽证书校验」——正确立场；对照已证明服务端三种模式均可握手，问题在手机路径未知环节，任何单边强制都是盲改。✅
- VERIFICATION 措辞「TLS 问题尚未解决」「不据本次修改发布所谓 TLS 修复版」——与事实一致。✅
- adb 无设备（logs/handshake-adb-devices.log，仅 List 头）、未操作手机/模拟器/发消息——与边界一致。✅

**TLS 缺口（缺什么证据）**：

1. **手机 Clash 侧**：该目标连接的命中规则、出站节点、失败日志文字（已异步请求用户提供，不需要令牌）——判定分流/节点层面问题的直接证据。
2. **应用侧原始异常链**：设置页「最近连接错误」只有分类后的诊断码 TLS_HANDSHAKE（固定文案）；当前诊断 Map 不含底层异常类型。若用户能复现时连 adb 取 logcat 中 OkHttp 抛出的原始 SSLHandshakeException cause（如 `SSLException: Read error` / 握手阶段断开 vs 证书路径异常），可把 TLS_HANDSHAKE 细分为「连接中断型」与「协商失败型」。**建议（非本轮）**：在不泄露原文的前提下，把异常链顶层类型名（如 javax.net.ssl.SSLHandshakeException + cause 简名）并进 connectionDiagnostics 摘要——类型名不含用户数据，复制回报即可替代部分 logcat。
3. **对照组**：同手机、同 Clash、另一 TLS 应用或 `adb shell` openssl/浏览器直连同域名的行为——已有「浏览器可访问」，但浏览器走系统 WebView 栈 + 可能不同的 Clash 规则（per-app 分流），仍非同路径对照。

在拿到 1/2 之一前，任何进一步代码改动都缺乏依据——当前「不动网络代码」是正确决策。

## P3 建议（不阻断）

1. **FilterPill overflow=Clip + softWrap=false**：对两字标签无实际影响，但该组件若复用于长标签会静默截断（Ellipsis 至少可见截断迹象）。可加 KDoc 注明「短标签专用」。
2. **字体缩放极端值**（>1.5x）：sp 文字高接近 48dp 的 min 高度时 Box 仍居中（Center 语义不变），但两 pill 视觉可能贴边；属设备确认项，非代码缺陷。
3. **诊断摘要可含异常类型名**（见上文建议 2，下轮 TLS 排查一并考虑）。

## 证实 vs 待证实

**已证实**：FilterPill 布局约束推导（居中、48dp、点击区、宽度预算）；TLS 代码零改动；开发机对照日志结论；文档边界措辞准确；41 JVM 测试/lint 通过（Lead + 本 worker 复跑）。

**待证实（设备）**：居中的实际字形效果与用户主观感受；字体缩放观感；TLS 手机路径行为（根因未明）。

## 审查验证命令

- PASS — `./gradlew :app:testDebugUnitTest --offline --console=plain`；41 tests / 0 failure / 0 error；logs/handshake-ui-review-test.log
（Lead 的 logs/handshake-ui-build.log、handshake-ui-tests.log、handshake-ui-diff-check.log、handshake-okhttp-probe.log、handshake-adb-devices.log 已核对存在且内容与文档描述一致。）
