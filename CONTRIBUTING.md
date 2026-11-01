# 参与栖信开发

欢迎通过 Issue 提交问题，通过 Pull Request 贡献代码。请先阅读 [README](README.md) 的功能范围与构建说明；较大的功能先在 Issue 讨论。

## 本地开发

需要 JDK 17 或以上、Android SDK Platform 36 和 Build Tools 36.0.0。设置 `ANDROID_HOME` 或本机的 `local.properties`，使用仓库自带的 Gradle Wrapper：

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintRelease
```

首次构建需要联网下载依赖。Debug APK 在 `app/build/outputs/apk/debug/`；贡献者不需要发布签名或仓库 Secrets。自行签名的 APK 不能覆盖官方签名版本，可使用专用测试设备。

## 提交改动

- 在独立分支提交范围明确的改动，说明问题、行为变化和验证结果。
- 涉及消息接收、去重、持久化、凭证、通知清理或安装包校验时，补充有意义的测试。
- 界面改动考虑 320dp 窄屏、深浅模式、字体缩放和读屏；没有设备验证时请明确说明。
- 自动化 smoke 会改变测试设备的配置，仅在专用测试设备运行，不能对个人手机或生产 ntfy 主题运行。
- 不提交令牌、真实消息、私人服务地址、签名文件、`local.properties`、构建产物或设备日志。复现使用 `example.com`、`.invalid` 和本机 fixture。

提交贡献意味着你有权按本项目的 [Apache License 2.0](LICENSE) 授权该贡献。第三方代码或素材需注明来源并保留兼容的许可与署名。
