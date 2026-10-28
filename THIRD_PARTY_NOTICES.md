# 第三方组件与名称说明

## 许可范围

仓库根目录的 [MIT 许可](LICENSE) 适用于一隅记账（Yiyu Ledger）自身代码与原创图标，版权声明为 `Copyright (c) 2026 krst4l and contributors`。第三方组件、商标与公开参考资料不因出现在仓库中而改为 MIT 许可。

## Gradle Wrapper 8.9

仓库包含 `gradlew`、`gradlew.bat` 和 `gradle/wrapper/gradle-wrapper.jar`，来自 Gradle 官方项目，适用 **Apache License 2.0**。完整许可见 [gradle/wrapper/LICENSE](gradle/wrapper/LICENSE)，Wrapper JAR 内亦保留 `META-INF/LICENSE`；启动脚本保留官方版权头。

- 项目：[Gradle](https://github.com/gradle/gradle/tree/v8.9.0)
- Unix 脚本：[官方 gradlew](https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradlew)，版权头为 `Copyright © 2015-2021 the original authors.`
- Windows 脚本：[官方 gradlew.bat](https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradlew.bat)，版权头为 `Copyright 2015 the original author or authors.`
- Wrapper JAR：从[官方 Gradle 8.9 分发包](https://services.gradle.org/distributions/gradle-8.9-bin.zip)内的 Wrapper 组件提取。

构建时下载的完整 Gradle 分发包包含自己的 LICENSE 与 NOTICE；其中的其他组件仍适用各自许可。仓库没有把完整 Gradle、Android SDK 或 JDK 工具链打包进源码。

## Android 构建环境

项目使用 Android 平台 API、Android Gradle Plugin 8.7.3 和所选 JDK 进行构建。Android SDK、构建工具和 JDK 由开发者另行取得并遵守对应许可；本项目的 MIT 许可不授权分发这些工具或它们的全部内容。应用源码没有额外声明第三方 Android 运行时库依赖。

## 第三方应用与公开资料

微信、支付宝、云闪付、银行和电商应用名称及包名用于说明来源筛选和适配目标。相关商标与服务属于其各自权利人，不表示这些服务对本项目授权、推荐或合作。

一木记账仅作为公开功能调查的参考。仓库不分发其 APK、反编译代码、图标、截图或手册全文；参考链接及实现来源见 [PROVENANCE.md](PROVENANCE.md)。
