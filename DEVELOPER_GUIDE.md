# 开发者指南 (DEVELOPER_GUIDE)

本文档面向希望深入了解或扩展 TRX Safe Payment 代码库的开发者。

## 1. 项目结构与模块划分

```
com.trxsafe.payment/
├── broadcast/          # 交易广播与持久化
│   ├── TransactionBroadcaster  # RPC 提交与错误处理
│   └── TransactionRecorder     # 历史记录存储 (SharedPreferences/Gson)
├── data/               # 数据库模块 (Room)
│   ├── entity/AddressBook      # 地址簿实体
│   └── dao/AddressBookDao      # CRUD 操作
├── qrcode/             # 二维码架构
│   ├── QRCodec                 # 编解码逻辑 (Base64/JSON)
│   └── QRSplitter              # 分片协议实现 (多帧二维码)
├── risk/               # 风险评估逻辑
│   └── RiskValidator           # 地址与金额校验
├── security/           # 核心安全层
│   ├── SecurityConstraints     # 硬编码的安全边界 (强制 TRX 原生转账)
│   └── BiometricAuthManager    # 系统生物识别包装类
├── settings/           # 配置管理 (MVVM)
│   ├── SettingsViewModel       # 状态同步与验证
│   └── SettingsRepository      # 加密持久化控制
├── transaction/        # 交易构建逻辑
│   └── TransactionBuilder      # 使用 Trident 构建待签名数据
├── ui/                 # 表现层 (ViewBinding)
│   ├── MainActivity            # 首页与钱包状态管理
│   ├── TransferActivity        # 复杂交易流控制项
│   └── AddressBookActivity     # 数据库交互界面
└── wallet/             # 密钥管理
    ├── SecureKeyStore          # Keystore 密钥管理
    └── WalletManager           # 签名与地址生成
```

## 2. 核心逻辑流 (转账流程)

1. **配置加载**：`TransferActivity` 订阅 `SettingsRepository` 获取单价锁定参数。
2. **构建交易**：用户输入后，由 `TransactionBuilder` 生成 `Chain.Transaction` 原生对象。
3. **风控拦截**：调用 `RiskValidator.validate()`，如果不满足白名单或金额限制，中断并提示。
4. **用户交互**：触发 `TransferConfirmDialog`，通过自定义 Listener 监听长按事件。
5. **身份验证**：`BiometricAuthManager` 发起系统级生物验证。
6. **签名过程**：
   - 热钱包：调用 `WalletManager.signTransaction`。
   - 二维码：`QRCodec` 序列化并触发 QRSplitter 显示展示流。
7. **广播结果**：`TransactionBroadcaster` 执行 RPC 之后调用 `TransactionRecorder` 记录本地历史。

## 3. 关键组件扩展建议

### 如何添加新的 RPC 节点？
1. 在 `NodeConfig.kt` 中静态声明新的节点信息。
2. 修改 `NodeConfig.getAllDefaults()` 将其加入列表。
3. `SettingsActivity` 的 `Spinner` 会自动同步新节点。

### 如何对接硬件钱包？
1. 实现 `HardwareWalletProvider` 接口。
2. 在 `TransferActivity` 的签名逻辑处增加 `Hardware` 类型判断。

### 数据库迁移
应用目前使用 Room `version 1`。如果需要修改 `AddressBook` 字段：
1. 更新 `AddressBook.kt` 实体。
2. 在 `AppDatabase.kt` 中增加 version。
3. 实现适当的 `Migration` 逻辑或使用 `fallbackToDestructiveMigration`。

## 4. 离线签名二维码协议 (v1)

为支持大数据量，采用了以下协议：
`trxsafe:v1:{chunk_index}:{total_chunks}:{payload_base64}`
- 每个分片最大数据量建议为 400 字符。
- 扫描端应使用 `QRSplitter.collector` 进行缓冲区重组数据。

## 5. 构建与环境

- **ViewBinding**：所有 Activity 均启用 ViewBinding。
- **协程 (Coroutines)**：所有 IO 操作必须运行在 `Dispatchers.IO`。
- **Kapt**：Room 编译器依赖于 Kapt，请确保 `build.gradle.kts` 已开启相关插件。
