# 第三方软件与素材

栖信使用以下主要开源组件。它们保留各自的版权与许可；本项目的 Apache License 2.0 不替代第三方许可。完整依赖版本以 Gradle 配置与解析结果为准。

| 组件 | 上游 | 许可 |
| --- | --- | --- |
| Kotlin | https://github.com/JetBrains/kotlin | Apache-2.0 |
| AndroidX / Jetpack Compose / Material 3 | https://android.googlesource.com/platform/frameworks/support/ | Apache-2.0 |
| OkHttp / Okio | https://github.com/square/okhttp / https://github.com/square/okio | Apache-2.0 |
| kotlinx.coroutines | https://github.com/Kotlin/kotlinx.coroutines | Apache-2.0 |
| commonmark-java 及表格、删除线扩展 | https://github.com/commonmark/commonmark-java | BSD-2-Clause |
| ntfy Emoji 标签映射 | https://github.com/binwiederhier/ntfy/tree/v2.34.0 | Apache-2.0 许可选项 |
| JUnit 4（测试） | https://github.com/junit-team/junit4 | EPL-1.0 |
| JSON-java 20240303（JVM 测试） | https://github.com/stleary/JSON-java/tree/20240303 | Public Domain |
| Gradle（构建） | https://github.com/gradle/gradle | Apache-2.0 |

ntfy 标签映射保持原样，原始许可与署名位于 `app/src/main/assets/notices/`。栖信为独立开发的兼容客户端，与 ntfy 官方项目没有隶属关系。

APK 同时包含 Apache License 2.0、Perch NOTICE 与 commonmark-java 的 BSD-2-Clause 原始许可，位于同一 notices 目录。JSON-java 仅用于 JVM 测试，不包含在 APK 中。
