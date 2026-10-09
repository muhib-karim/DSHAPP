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
        versionCode = 10
        versionName = "0.2.3"

        // 只支持 arm64-v8a：内置的引擎资产与 Termux bootstrap 都是 arm64 架构
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

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
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
