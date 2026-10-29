# 组件与素材来源

粥粥记账使用 Android 原生控件、通知监听、无障碍服务和 SQLite。识别规则、界面、图表及通用图标为项目代码。

## 角色与物品

- 三位伙伴为参考指定造型重绘的本地 PNG，文件与参考说明见 [伙伴素材](docs/character-art.md)。
- 角色插图涉及第三方角色或设计，不纳入项目 MIT 许可，未声明取得商业使用或再分发授权。
- 战利品名称与历史数值来自[游侠网红色物品图鉴](https://gl.ali213.net/html/2025-12/1726225.html)（2025-12-12）：78 项名称，其中 57 项有历史均价、21 项未提供数值。
- [物品参考表](docs/loot-reference.md)保留全部条目和趣味金额的计算方式；应用不联网更新价格。
- 仓库不包含参考截图、原文章配图或游戏官方美术。通用物品示意图由项目绘制。

## Gradle Wrapper 8.9

Wrapper JAR 提取自官方 Gradle 8.9 分发包；启动脚本来自官方 `v8.9.0` 标签并保留版权头。许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

| 组件 | SHA-256 | 官方来源 |
| --- | --- | --- |
| Gradle 8.9 分发包 | `d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab` | [校验值](https://services.gradle.org/distributions/gradle-8.9-bin.zip.sha256) |
| `gradle-wrapper.jar` | `498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17` | [校验值](https://services.gradle.org/distributions/gradle-8.9-wrapper.jar.sha256) |
| `gradlew` | `9cbbb4d68ff7fb5211c4d58f598ac9d8664c05fdcd1e5f59b7f2c3ac1ee00af0` | [脚本](https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradlew) |
| `gradlew.bat` | `0f3ed8f03b50934cb8c48b15a470d5c20a30a5385825e48b55bcc8ea3d8f8e18` | [脚本](https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradlew.bat) |

脚本校验值对应官方原始字节，工作区换行转换可能改变文本文件的哈希。Wrapper JAR 不做文本转换，分发包校验值已写入 `gradle-wrapper.properties`。
