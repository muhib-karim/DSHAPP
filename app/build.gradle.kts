import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 发行版签名凭据从仓库根的 keystore.properties 读（不入库，见 .gitignore）。
// 文件不存在时不配 signingConfig —— 这样别人 clone 后仍能出 unsigned 包，
// 且 assembleDebug 完全不受影响。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    // ⚠️ 必须是恰好 15 字符，且与 TermuxBootstrap.ELF_ROOT_REPLACEMENT 完全一致。
    // 原因：Termux 官方二进制把 "/data/data/com.termux/files"（27 字节）烤进 ELF 的
    // .dynstr，而 patchElfInPlace() 只能做等长原地覆盖，替换串形如
    // "/data/data/<包名>/" = 12 + N 字节，等于 27 ⇒ N = 15。
    // 改错不会编译报错，只会在真机上以「dpkg Permission denied」的形式炸掉。
    // 改完请跑 termux-kit 的 tools/verify-kit.ps1 校验。
    namespace = "com.dsh.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dsh.android"
        minSdk = 26
        // 🔴 必须是 28，不能升到 29+。Android 10（API 29）起，targetSdk ≥ 29 的
        // 应用不允许 execve() 自身私有目录里的文件（W^X 限制，SELinux
        // untrusted_app 域的 app_data_file 禁止 execute_no_trans）。而 Termux 的
        // 全部包（node / bash / dpkg / python）都装在 filesDir 下的 $PREFIX 里，
        // 一升到 29 就会全线 Permission denied。Termux 官方 App 同样停在 28。
        targetSdk = 28
        versionCode = 15
        versionName = "0.2.8"

        // 只支持 arm64-v8a：内置的引擎资产与 Termux bootstrap 都是 arm64 架构
        ndk {
            abiFilters += "arm64-v8a"
        }

        // Web 内核是不是**随 APK 打包**的（gecko flavor 覆盖成 true）。
        // 用途只有一个但很关键：`EngineManager.ensureWebViewCompat()` 是给**设备上
        // 的系统 WebView 太旧**准备的降级（按 chrome74 重编译前端 bundle）。内置内核
        // 的版本在编译期就定了（GeckoView 140），永远不可能"过旧"，跑降级只会白白把
        // 前端换成语法受限的版本 —— 所以那一整套在 gecko 版必须整个跳过。
        buildConfigField("boolean", "BUILT_IN_WEB_ENGINE", "false")
    }

    // ══ 两个 Web 内核版本共存 ══════════════════════════════════════════
    //
    // system = 系统 WebView 内核（原有版本，行为与 0.2.6 完全一致）
    // gecko  = 内置 GeckoView 内核（不依赖设备上的系统 WebView）
    //
    // ⚠️ **代价**：一旦有 flavor，构建任务就带上 flavor 名了 ——
    //   `:app:assembleDebug` 变成 `:app:assembleSystemDebug` / `:app:assembleGeckoDebug`。
    //
    // ⚠️ **两个 flavor 的 applicationId 必须都恰好 15 字符**，原因见 defaultConfig
    //   里那条注释（Termux ELF 的等长原地重写）。`com.dsh.android` 与
    //   `com.dsh.webview` 都是 15 字符，对应 `/data/data/<包名>/` = 27 字节。
    //   `TermuxBootstrap.ELF_ROOT_REPLACEMENT` 已改为从 `BuildConfig.APPLICATION_ID`
    //   派生，改这里不需要再同步改 Kotlin 常量。
    flavorDimensions += "webengine"
    productFlavors {
        create("system") {
            dimension = "webengine"
            // applicationId 继承 defaultConfig，产物与原有版本逐字节同源。
        }
        create("gecko") {
            dimension = "webengine"
            applicationId = "com.dsh.webview"
            // 两个包会同时存在于设备上（applicationId 不同 ⇒ 可共存），
            // 版本号带后缀才能在系统「应用信息」里分辨出装的是哪一个。
            versionNameSuffix = "-gecko"
            // 内核随 APK 分发 ⇒ 不需要也不应该跑「按 chrome74 降级前端」那一套。
            buildConfigField("boolean", "BUILT_IN_WEB_ENGINE", "true")
        }
    }

    /**
     * 内核实现放在 flavor 源集里，不是 `main`。
     *
     * ⚠️ 这是**必须**的：`GeckoWebHost` 依赖 `org.mozilla.geckoview.*`，而那个
     *   依赖只挂在 `geckoImplementation` 上。放进 `main` 会让 system flavor 也去
     *   编译它 ⇒ `Unresolved reference: geckoview`，两个包一起构建失败。
     *
     * 布局（AGP 按约定自动收集 `src/<flavor>/java` 与 `src/<flavor>/assets`，
     * 不需要显式声明 sourceSets）：
     *
     *   main/java/.../web/   → WebHost.kt、PageBridge.kt（纯接口，无内核依赖）
     *   system/java/.../web/ → SystemWebHost.kt、WebHostFactory.kt
     *   gecko/java/.../web/  → GeckoWebHost.kt、WebHostFactory.kt
     *   gecko/assets/        → dsh-bridge 扩展（只有 gecko 需要）
     */

    signingConfigs {
        // ⚠️ 这把钥匙是发行版的终身身份，与 debug 签名**互相独立**。
        // 一旦发出去就不能换：换签名 = 老用户撞 INSTALL_FAILED_UPDATE_INCOMPATIBLE，
        // 只能卸载重装，而卸载会清掉 filesDir 下的整个 Termux 环境与 $DSH_HOME。
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            packaging {
                resources {
                    excludes += "/META-INF/{AL2.0,LGPL2.1}"
                }
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    lint {
        // targetSdk=28 是本项目的硬约束（原因见 defaultConfig 里的注释），而
        // lintVitalRelease 的 ExpiredTargetSdkVersion 会直接判 release 构建失败。
        // 本 App 只能侧载、不上架 Google Play，该检查在这里无意义，故只关掉这一条，
        // 其余 lint 检查保持生效。
        disable += "ExpiredTargetSdkVersion"
    }

    // 三个资产都用 assets.open() 读取，压缩与否对调用方透明，所以**不需要**
    // noCompress 白名单（加上反而会让 APK 从 ~60MB 涨到 ~108MB）。
    // 唯一要防的是 AGP 按扩展名做格式推断：它会把 .tar.gz 自动解压并改名成 .tar，
    // 于是运行期 openFd 按原名找不到。规避方式是资产名一律用中性后缀
    // （engine.bin / node-runtime.bin / bootstrap-aarch64.zip），不靠白名单。

    // Node 改为 Termux 官方构建（随 assets 分发、解到 app 私有 $PREFIX），
    // 不再往 jniLibs 塞可执行文件，因此无需 useLegacyPackaging / extractNativeLibs。

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        // TermuxBootstrap.ELF_ROOT_REPLACEMENT 从 BuildConfig.APPLICATION_ID 派生 ——
        // 两个 flavor 的包名不同（com.dsh.android / com.dsh.webview），而 ELF 里的
        // 替换串必须**恰好 27 字节**且与真实包名一致，写死一个就会让另一个 flavor
        // 静默跳过路径重写（症状是 dpkg/node 全线 Permission denied）。
        // AGP 8.0 起 buildConfig 默认为 false，必须显式打开。
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 内置内核版：GeckoView（Mozilla 自维护的 Gecko 引擎，随 APK 分发、不依赖
    // 设备上的系统 WebView）。只有 gecko flavor 才需要它。
    //
    // ⚠️ 必须用 `-arm64-v8a` 那个变体而不是 `geckoview`：前者只含 arm64 的
    //   libxul.so（86 MB），后者是全 ABI 的 omni 包（230 MB），而本工程
    //   `abiFilters` 只有 arm64-v8a —— 引 omni 会把用不到的几个 ABI 一起打进 APK。
    // ⚠️ 仓库在 settings.gradle.kts 里单独声明（google()/mavenCentral() 都没有）。
    // ⚠️ 版本选择不是「越新越好」，是**被本工程的构建工具链锁死的**：
    //   157（当前 release）硬依赖 `androidx.core:core:1.19.0`（要求 AGP 9.1.0+）
    //   与 `androidx.media3:*:1.11.0`（要求 compileSdk 36）；146 依赖 core 1.17.0
    //   （要求 AGP 8.9.1+）。本工程是 AGP 8.8.2 + compileSdk 35，三者都装不下：
    //     Dependency 'androidx.core:core:1.17.0' requires Android Gradle plugin 8.9.1 or higher
    //   140 依赖 core 1.16.0（minAGP 8.6.0 / minCompileSdk 35，均满足），是能在
    //   当前工具链下直接构建的最高版本。要再往上就得先升 AGP，那是另一件事。
    "geckoImplementation"("org.mozilla.geckoview:geckoview-arm64-v8a:140.0.20250707120347")
}
