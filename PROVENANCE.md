# 实现来源与构建组件

本文记录一隅记账（Yiyu Ledger）v0.3.0 的实现来源，区分公开资料与截图参考、独立实现和官方构建组件。

## 应用实现

一隅记账（Yiyu Ledger）由 AI 协作独立编写，使用 Android 通知监听、无障碍、本地 SQLite 和系统文件选择等公开平台能力。识别规则、界面和原创图标为本项目实现。

最初的功能调查查阅了一木记账的公开手册及隐私说明，了解“读取可访问付款页面文字、在本地提取账单候选”的公开功能思路：

- [一木记账官网](https://www.yimuapp.com/)
- [官方 Android 自动记账手册](https://www.yimuapp.com/doc/bill/auto.html)
- [官方隐私政策](https://www.yimuapp.com/doc/privacyPolicy.html)

REA 只用于观察和记录公开网页。**本版本未取得或反编译一木记账 APK，也未复制其程序代码、图标、界面素材或私有规则。** 公开网页调查不能证明其内部代码如何实现，也不是本项目已完成第三方 APK 逆向的证据。

v0.3.0 的需求讨论参考了用户提供的六张一木记账截图，用于理解账本、日历、统计、个人设置和记账入口等功能与使用流程。实现人员见过这些页面，因此本项目不声称“从未查看第三方界面”。项目据此重新组织导航、分类选择和金额录入，并独立编写布局、图表、颜色、图标与交互；不提取或复用截图中的图像、图标、字体文件和其他界面素材，截图本身不随仓库分发。

此过程没有采用分离调查人员和实现人员的正式隔离程序，因此不称为“清洁室重建”。功能相似不等于代码来源相同，也不保证与任何第三方应用的兼容性。

仓库仅保留必要来源链接及参考过程说明，不公开分发第三方截图、手册全文、网页采集内容、个人浏览会话或调研缓存。第三方名称与资料的权利归其各自权利人；用户提供截图不代表本项目获得第三方素材的再分发许可。

## Gradle Wrapper

Wrapper JAR 从官方 Gradle 8.9 分发包的 `lib/plugins/gradle-wrapper-main-8.9.jar` 中提取内嵌的 `gradle-wrapper.jar`；两个启动脚本来自官方 `v8.9.0` 标签并保留原始版权头。Wrapper 的 Apache-2.0 许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

| 组件 | SHA-256 | 官方校验或来源 |
| --- | --- | --- |
| Gradle 8.9 二进制分发包 | `d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab` | [官方校验值](https://services.gradle.org/distributions/gradle-8.9-bin.zip.sha256) |
| `gradle-wrapper.jar` | `498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17` | [官方校验值](https://services.gradle.org/distributions/gradle-8.9-wrapper.jar.sha256) |
| `gradlew` | `9cbbb4d68ff7fb5211c4d58f598ac9d8664c05fdcd1e5f59b7f2c3ac1ee00af0` | [官方脚本](https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradlew) |
| `gradlew.bat` | `0f3ed8f03b50934cb8c48b15a470d5c20a30a5385825e48b55bcc8ea3d8f8e18` | [官方脚本](https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradlew.bat) |

启动脚本哈希对应下载时的官方原字节；Git 根据 `.gitattributes` 规范换行后，Windows 批处理文件的工作区哈希可能不同。Wrapper JAR 不进行文本转换，分发包校验值写入 `gradle-wrapper.properties`。

## 已知验证边界

本项目仍为实验版本，未完成代表性真实交易样本和多设备验证。设备观察与规则匹配不能证明交易正确，也不能由 APK 可构建推导自动记账准确率。

现有一加 13 样本中，一笔支付宝“交易成功”的外卖账单已核对商户和金额正确，保存为待核对；日期未确认，未取得明确交易号。微信历史付款页面在新版普通节点选项生效后仍只有空根，页面识别未解决，具体限制来源未确定；真实付款通知路径尚未验证。其他来源没有适配成功证据。详细记录见 [设备验证记录](docs/device-validation.md)。

识别规则依据公开 Android 能力、最小页面结构描述及本应用固定类型诊断独立编写，未取得第三方页面源码或私有规则。排错不保存完整第三方账单、通知正文或截图；类型及分支代码也不保存真实字符、字符组数量或财务字段值。是否取得节点、是否提取字段和人工核对结果分别记录，不将它们混称为适配通过。
