# 新手运行与打包指南 (Getting Started for Beginners)

如果您是第一次接触 Android 项目，请按照以下步骤逐步操作，即可将 TRX Safe Payment 运行在您的手机上或生成安装包 (APK)。

## 第一步：准备开发环境

1.  **安装 Java 环境 (JDK 17)**：
    -   前往 [Oracle 官网](https://www.oracle.com/java/technologies/downloads/) 或使用 [Adoptium](https://adoptium.net/) 下载并安装 JDK 17。
2.  **安装 Android Studio**：
    -   前往 [Android Studio 官网](https://developer.android.google.cn/studio) 下载并安装最新版本（建议版本：Iguana 2023.2.1 或更高）。
    -   安装过程中，请勾选安装 **Android SDK**。

## 第二步：导入项目

1.  打开 Android Studio。
2.  选择 **File -> New -> Import Project...** (或在欢迎界面点击 **Open**)。
3.  选择本项目所在的文件夹。
4.  **耐心等待**：Android Studio 会自动下载所需的依赖库（如 Gradle 和波场 SDK）。根据您的网速，这可能需要 5-20 分钟。
    -   *注意：如果下载失败，请检查您的网络设置（建议配置代理环境）。*

## 第三步：运行到手机

1.  **开启手机调试模式**：
    -   进入手机“设置” -> “关于手机”。
    -   连续点击“版本号” 7 次，直到提示已进入开发者模式。
    -   在“开发者选项”中开启 **USB 调试**。
2.  **连接手机**：使用数据线将手机连接到电脑，手机上弹出授权提示时点击“允许”。
3.  **运行项目**：
    -   在 Android Studio 顶部的工具栏选择您的手机型号。
    -   点击绿色的 **运行按钮 (Run)** (形状像播放键 ▶)。
    -   应用将自动构建、安装并启动。

## 第四步：打包 APK (生成安装包)

如果您想把 App 传给其他手机安装，需要生成 APK 文件：

### 1. 生成开发测试版 (Debug APK)
-   点击顶部菜单 **Build -> Build Bundle(s) / APK(s) -> Build APK(s)**。
-   完成后，右下角会弹出提示，点击 **locate** 即可在文件夹中找到 `app-debug.apk`。

### 2. 生成正式版 (Release APK)
-   正式版体积更小、更安全，但需要创建签名文件：
-   点击顶部菜单 **Build -> Generate Signed Bundle / APK...**。
-   选择 **APK**，点击 **Next**。
-   点击 **Create new...** 创建一个新的密钥库 (.jks 文件)，填写密码和别名（需妥善保存）。
-   选择 **release** 构建版本，点击 **Finish**。
-   生成的正式版 APK 将位于 `app/release/` 目录下。

## ⚙️ 常见问题排查

-   **构建报错 (Build Failed)**：通常是网络问题导致依赖包下载不全，请点击顶部蓝色的“小象”图标 (Sync Project with Gradle Files) 重新同步。
-   **找不到手机**：确保数据线正常，并检查驱动程序是否安装。
-   **指纹不能用**：请在手机系统设置中确保已录入至少一个指纹。

---

*如果您在操作过程中遇到困难，欢迎在 GitHub 提交 [Issue](./.github/ISSUE_TEMPLATE/bug_report.md)！*
