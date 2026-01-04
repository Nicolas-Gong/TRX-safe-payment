plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.trxsafe.payment"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.trxsafe.payment"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 安全配置：禁用备份、调试日志
        manifestPlaceholders["allowBackup"] = false
    }

    // 解决META-INF文件重复冲突问题
    packaging {
        resources {
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions {
        jvmTarget = "17"
    }
    
    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }
}

dependencies {
    // Android 核心库
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    
    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.6")
    implementation("androidx.compose.runtime:runtime-livedata:1.6.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // TRON SDK
    implementation("io.github.tronprotocol:trident:0.9.2")

    // 加密和安全
    implementation("com.google.crypto.tink:tink-android:1.10.0")
    implementation("androidx.security:security-crypto:1.0.0")

    // Reown AppKit - 完整的钱包连接解决方案
    // 配置说明：
    // 1. 在 https://cloud.reown.com 创建项目并获取PROJECT_ID
    // 2. 将PROJECT_ID添加到WalletConnectManager.kt中
    // 3. 确保网络权限和相关配置
    // 4. 添加相应的ProGuard规则防止混淆问题
    implementation(platform("com.reown:android-bom:1.3.0"))
    implementation("com.reown:android-core")
    implementation("com.reown:appkit")

    // Web3j 用于区块链交互（暂时保留用于未来的扩展）
    // implementation("org.web3j:core:4.9.8")
    // implementation("org.web3j:utils:4.9.8")

    // 网络请求
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // JSON 处理
    implementation("com.google.code.gson:gson:2.10.1")
    
    // BouncyCastle 用于加密签名
    implementation("org.bouncycastle:bcprov-jdk18on:1.78")
    
    // 二维码生成和扫描
    implementation("com.google.zxing:core:3.5.2")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    
    // 测试
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    
    // 生物识别
    implementation("androidx.biometric:biometric:1.1.0")
    
    // Room 数据库
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
}

// 代码审查任务配置
tasks.register("codeReview") {
    group = "Code Review"
    description = "执行代码审查并生成报告"
    doLast {
        println("请运行: ./gradlew :app:dependencies 或直接在IDE中执行CodeReviewTask")
    }
}

tasks.register("codeQualityCheck") {
    group = "Code Review"
    description = "执行代码质量检查"
    doLast {
        println("请确保CodeReviewTasks.kt已正确编译")
    }
}

tasks.register("generateCodeMetrics") {
    group = "Code Review"
    description = "生成代码指标报告"
    doLast {
        println("请确保GenerateCodeMetricsTask已正确编译")
    }
}

tasks.register("codeQualityReport") {
    group = "Code Review"
    description = "生成代码质量综合报告"
    doLast {
        println("请确保CodeQualityReportTask已正确编译")
    }
}

// 注意: 完整的代码审查任务需要使用 Gradle Worker API 或 buildSrc 模块
// 当前配置为占位符，实际任务类已定义在 analysis/ 目录下
