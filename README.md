# TRX Safe Payment - 高安全 TRX 能量交易钱包

[中文](./README.md) | [English](./README_EN.md)

TRX Safe Payment 是一款专为 **TRON (TRX) 能量交易场景** 设计的高安全 Android 钱包应用。它通过多层验证、严格的交易限制以及冷热分离机制，为用户的资产提供企业级的防护。

## 🌟 核心亮点

- **极致安全约束**：系统级别限制仅允许 `TransferContract`，防止一切智能合约陷阱和恶意授权。
- **冷热分离架构**：支持热钱包直接签名和冷钱包离线二维码扫描签名逻辑。
- **观察钱包模式**：支持仅导入地址（Watch-Only），实现资产监控的同时保持私钥绝对离线。
- **多层风控校验**：集成单价锁定、倍率控制、白名单比对等多重实时风险识别机制。
- **生物识别防护**：集成 App 锁定、交易确认前的指纹/面部动态验证。

## 🛠️ 功能特性

### 1. 钱包与资产管理
- **多种导入方式**：支持生成新地址、私钥导入以及观察钱包（只读地址）导入。
- **加密存储**：使用 `EncryptedSharedPreferences` 和 Android Keystore 加密私钥数据。
- **资产监控**：多账户切换，清晰展示钱包类型（热钱包/观察钱包）。

### 2. 能量交易配置
- **单价锁定**：支持配置 0.001 - 10 TRX 的单位能量价格，锁定后防止交易金额被篡改。
- **自动化计算**：根据配置的单价和数量倍率，自动计算并预览最终转账金额。
- **收款地址白名单**：内置地址簿，支持白名单标记，非白名单地址转账将触发高风险告警。

### 3. 安全交易流程
- **确认冷静期**：转账确认按钮需长按 3 秒，强制用户核对收款地址及金额。
- **交易分片二维码**：独创 `trxsafe:v1` 分片协议，解决超大数据量交易在离线环节的扫描难题。
- **实时广播监控**：支持主网、Nile、Shasta 多节点切换，可视化展示交易打包及确认状态。

### 4. 深度安全锁定
- **App 自动锁定**：监听系统生命周期，App 进入后台超时后自动触发生物识别锁定。
- **操作审计**：详尽的交易历史详情，包含发送方、接收方、TXID、区块高度、带宽/能量消耗及备注。

## 🏗️ 技术架构

- **核心语言**：Kotlin
- **架构模式**：MVVM (Model-View-ViewModel)
- **底层驱动**：[Trident SDK](https://github.com/tronprotocol/trident) (Java SDK for TRON)
- **数据库**：Room Database (用于地址簿本地存储)
- **加密方案**：Android Jetpack Security (Crypto)
- **UI 组件**：Material Design 3 + ViewBinding

## 🚀 快速开始

### 环境依赖
- Android Studio Iguana 或更高版本
- Android SDK 24 (7.0) +
- Java 17

### 构建步骤
1. 克隆代码仓库。
2. 在 Android Studio 中同步 Gradle。
3. 连接物理设备（建议开启生物识别支持）。
4. 运行 `app` 模块。

## 📄 Documentation / 说明文档

- **[GETTING_STARTED_ZH.md](./GETTING_STARTED_ZH.md)**: **Beginner's guide to Run & Build APK (Chinese)**.
- **[SECURITY.md](./SECURITY.md)**: Deep dive into the four-layer security architecture.
- **[USER_GUIDE_ZH.md](./USER_GUIDE_ZH.md)**: Detailed Chinese operation manual.
- [开发者说明 (DEVELOPER_GUIDE.md)](./DEVELOPER_GUIDE.md) - 模块说明与扩展建议。
- [项目开发总结 (PROJECT_SUMMARY.md)](./PROJECT_SUMMARY.md) - 开发历程与功能清单。
- [信任与验证白皮书 (TRUST_AND_VERIFICATION.md)](./TRUST_AND_VERIFICATION.md) - **必读**：如何验证应用的安全性和透明性。

## 🔍 如何验证？

我们透明化了所有通信协议。专业用户可以通过以下方式验证：
1. **生成未签名交易**后，使用第三方扫码器扫描二维码。
2. 将得到的 JSON 字符串中的 `rawData` 段落取出。
3. 在 [Tronscan 解析器](https://tronscan.org/#/tools/transaction-decoder) 中粘贴该十六进制字符串。
4. **验证结果**：您将看到该交易的收款地址、金额、引用区块等所有细节。这证明了我们的应用没有在后台进行任何篡改。

## ⚖️ 免责声明

本应用旨在提供高度安全的支付工具，但区块链交易具有不可逆性。在使用前请务必确认收款地址和金额。私钥丢失或泄露导致的资产损失由用户自行承担。
