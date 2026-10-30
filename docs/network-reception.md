# 0.5.2 Clash / TUN 下的后台接收

本文保留 0.5.2 的实现记录。用户后续反馈仍出现 TLS 错误；0.5.3 撤回显式 Network DNS/socket 绑定，恢复系统默认联网，并补细诊断。最新决策与验证边界见 [TLS 与紧凑收件箱](tls-compact-inbox.md)。

2026-10-30。用户反馈：Clash 开启时无法连接，关闭后恢复；怀疑 TUN 或 DNS 变化。

## 已复现的应用侧问题

旧实现只把 `onAvailable/onLost` 转成 `StateFlow<Network?>`，没有消费 `onLinkPropertiesChanged`。同一个 Network 的 DNS、路由或代理属性变化不会使接收协程重启；`onAvailable` 又早于新网络的 DNS / 路由回调，可能过早启动连接。所有接收会话长期使用同一个 OkHttp 客户端与连接池。

先将原有回调状态处理等价提取成 `DefaultNetworkState`（可用时立即发布 Network、丢失时置空、忽略 link properties），再执行 `:app:testDebugUnitTest --tests app.perch.DefaultNetworkStateTest`：两项测试失败，分别捕获同网络 DNS 变化未使状态失效、以及配置到达前已经连接。日志：`logs/clash-regression-red.log`。这复现了应用侧回调处理缺口，不代表已复现用户手机上的 Clash 故障。

## 修复

- 默认网络变化时先取消旧流，等待系统投递该网络的 LinkProperties 后再开始接收。
- Network 与 LinkProperties 共同构成接收会话标识；同网络 DNS、路由、代理等变化会触发重连。相同属性重复回调不重连，旧网络的延迟回调不能覆盖新网络。
- 每个会话使用独立连接池；DNS 通过该默认 Network 的 `getAllByName`，socket 通过同一 Network 的 `socketFactory`，避免解析和连接分属两个网络。连接池在 IO 协程的 finally 中清理。
- 消息游标补收、去重、鉴权与退避保持原逻辑。无轮询、额外心跳或 WakeLock。没有硬编码 DNS，也没有选择物理网络绕过 VPN。
- 接收失败时分别显示 DNS、超时、服务器连接、TLS 失败。提示不拼接异常文本，避免暴露私有地址或令牌。

## 验证边界

JVM 回归覆盖网络配置变化、Wi-Fi/VPN 切换、回调乱序与重复、失网恢复、旧接收任务先取消再替换，以及错误分类。Android 回调到 LinkProperties、Network 专属 DNS/socket 的实际行为仍需设备联调。

本轮 `adb devices -l` 没有设备；未启动模拟器、未安装手机、未修改 Clash 配置、未向任何 ntfy 主题发送消息。尚无用户设备的错误堆栈、DNS 答复或 Clash 规则日志，不能断言用户故障一定由上述缺口造成。若 Clash 原有分流规则拒绝服务器、Fake-IP 映射异常、证书拦截或底层不可达，应用无法替代理规则修正。

设备复测应覆盖：Clash 已开启时启动接收、接收中开启/关闭 Clash、保持 TUN 开启切换 DNS 模式或 Wi-Fi/移动数据；观察自动重连与缓存补收。若仍失败，记录设置中具体错误类型及 Clash 对该服务器的规则命中，继续定位。不要提供令牌或带凭据的完整 URL。
