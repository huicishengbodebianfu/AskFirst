import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.kapt")
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.isFile) {
        // 必须用 UTF-8 读：Properties.load(InputStream) 按 ISO-8859-1 解，
        // 中文路径会变成 F:\å®å\...（和 local.properties 那个坑同一个根因）
        keystorePropsFile.reader(Charsets.UTF_8).use { load(it) }
    }
}

/**
 * 个人自用的默认配置（可选的本地文件，**不进版本库**）。
 *
 * 开源仓库里没有 `自用/personal.properties` → 下面四个字段全为空 → App 会要求用户自己在设置页填。
 * 自己用的时候在本地建这个文件写四项即可，值只在编译期进 BuildConfig，源码里不留明文：
 *
 * ```properties
 * base_url=https://your-gateway:3425/v1
 * model=your-model
 * api_key=sk-...
 * github_pat=ghp_...
 * ```
 */
val personalPropsFile = rootProject.file("自用/personal.properties")
val personalProps = Properties().apply {
    if (personalPropsFile.isFile) {
        personalPropsFile.reader(Charsets.UTF_8).use { load(it) }
    }
}

/** 注进 BuildConfig 的字符串要自己转义反斜杠和引号。 */
fun personalField(key: String): String {
    val raw = personalProps.getProperty(key, "").trim()
    return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

android {
    namespace = "com.askfirst"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    // 亲友分发必须用固定 keystore（否则你自己都覆盖安装不了新版本）
    signingConfigs {
        if (keystorePropsFile.isFile) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.askfirst"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // 设备测试（S2 起用：密钥/设置/DB 这类"只有真机能验"的东西）
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 个人自用默认值（来自 自用/personal.properties，没有就是空串）
        buildConfigField("String", "PERSONAL_BASE_URL", personalField("base_url"))
        buildConfigField("String", "PERSONAL_MODEL", personalField("model"))
        buildConfigField("String", "PERSONAL_API_KEY", personalField("api_key"))
        buildConfigField("String", "PERSONAL_GITHUB_PAT", personalField("github_pat"))

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 有 keystore.properties 就用真签名；没有就退回 debug（仅自用临时）
            signingConfig = if (keystorePropsFile.isFile) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

kapt {
    correctErrorTypes = true
    arguments {
        // Room schema 导出：迁移必须可校验，产物入版本库（docs/03 §7）
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")

    // Compose BOM 统一管理版本
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.9.8")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // ---- 数据层（M1 S1）：Room + DataStore ----
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    kapt("androidx.room:room-compiler:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    // 密钥存储（M1 S2）：Keystore + EncryptedSharedPreferences
    implementation("androidx.security:security-crypto:1.1.0")

    // ---- 网络与 JSON（M1 S3/S4） ----
    implementation("com.squareup.okhttp3:okhttp:4.12.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // ---- 纯 JVM 单测（引擎/解析/种子） ----
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")

    // ---- 真机测试（密钥/设置/数据库，JVM 测不了） ----
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}