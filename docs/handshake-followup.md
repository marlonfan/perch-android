# 0.5.4 后续：握手失败与筛选标签居中

2026-11-01，用户报告手机开启 Clash 后仍提示 TLS_HANDSHAKE，并反馈顶部全部/未读标签未居中、文字视觉拥挤。修正基线 86e8475（v0.5.4），UI修正随0.5.5发布；网络与TLS实现不变。

## 初始排查结果与缺口（截图提供前）

- 已有信息：Clash 运行在手机上；该设备浏览器可访问同一服务；关闭 Clash 后应用可连接。新反馈为 TLS_HANDSHAKE，仍没有 Android 底层异常链与 Clash 对该连接的规则/出站/错误。
- adb devices -l 没有设备，日志 logs/handshake-adb-devices.log。未操作手机、未运行模拟器、未向主题发送消息。
- 开发机使用同款 OkHttp 4.12.0，分别默认、仅 TLS 1.2、仅 TLS 1.3，均通过默认证书和主机名校验，健康接口返回 HTTP 200，协商 HTTP/2；日志 logs/handshake-okhttp-probe.log。它是服务端对照，不能复现或排除手机 Clash 路径的问题。
- 初始 Python 对照中 TLS 1.2 成功；TLS 1.3 的 ValueError 是本地 Python SSL 实现不支持该协议版本，不是服务端拒绝；最终以 Java/OkHttp 对照为准。
- 尚无能在当前环境重现用户手机握手中断的反馈循环，因此不基于猜测强制 TLS 1.2、HTTP/1.1、DNS 或代理配置，也不放宽证书/主机名校验。本轮没有修改网络或 TLS 实现。
- 已请用户提供 Clash 中该目标失败时的命中规则、出站和错误文字；不需要令牌。收到记录后再决定是否需要采集更底层的 Android 异常。

## 筛选标签修正

FilterPill 原先由 Text 直接承接 Surface 的最小高度约束，textAlign.Center 仅设置水平文字对齐，没有定义垂直居中。现改为显式 Box(contentAlignment = Alignment.Center) 承载文字；保留至少48dp触控区域和两个短标签全部/未读，不把数量插入可见标签，移除文字内部偏移 padding。搜索和主题菜单位置、数量读屏描述、全部已读范围均不变。

未执行设备像素或截图验证，不能把代码约束检查当成实际渲染结果；需要后续设备确认字形居中与系统字体缩放效果。本轮不声称 TLS 修复完成，也不据本次修改发布所谓 TLS 修复版。

## 审查结果

Reviewer（Orca Worker）确认UI代码修正通过，无P1/P2阻断；41项JVM测试通过，日志 logs/handshake-ui-review-test.log；报告 docs/review-handshake-ui.md。TLS独立终态为尚未解决，所缺证据为手机Clash规则/出站/错误或应用底层异常链。未将开发机握手成功等同于手机故障修复，该审查完成时尚未提交发布；后续截图与发布决定见下文。

## 用户提供Clash截图后的结论与发布范围

用户后续提供Clash日志截图：目标连接命中RuleSet/DirectForce、走Direct，随后出现dns resolve failed: couldn't find ip和no ip address。能够确认这些连接在Clash直连侧未获得可用的目标IP；它不表示域名不存在，也不能仅凭该行区分解析器不可达、查询分流或结果过滤。原始域名及其他连接信息保留在会话中，不写入公开文档。

应用显示TLS_HANDSHAKE可能是代理无法建立上游连接后中断握手的表象，未获得同次Android异常链前不宣称唯一因果。用户认为应处理Clash DNS配置，并明确要求发布现有UI修正。因此0.5.5只发布标签居中修正，保持系统DNS、代理、TLS协议及证书/主机名校验策略，不称为TLS修复版。尚未修改手机或Clash配置。
