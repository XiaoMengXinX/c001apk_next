import com.android.build.gradle.internal.api.ApkVariantOutputImpl
import org.jetbrains.kotlin.konan.properties.Properties
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.rikka.tools.materialthemebuilder)
    alias(libs.plugins.google.dagger.hilt.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.parcelize)
}

apply(plugin = "kotlin-kapt")

kapt {
    generateStubs = true
    correctErrorTypes = true
}

materialThemeBuilder {
    themes {
        for ((name, color) in listOf(
            "Default" to "6750A4",
            "Red" to "F44336",
            "Pink" to "E91E63",
            "Purple" to "9C27B0",
            "DeepPurple" to "673AB7",
            "Indigo" to "3F51B5",
            "Blue" to "2196F3",
            "LightBlue" to "03A9F4",
            "Cyan" to "00BCD4",
            "Teal" to "009688",
            "Green" to "4FAF50",
            "LightGreen" to "8BC3A4",
            "Lime" to "CDDC39",
            "Yellow" to "FFEB3B",
            "Amber" to "FFC107",
            "Orange" to "FF9800",
            "DeepOrange" to "FF5722",
            "Brown" to "795548",
            "BlueGrey" to "607D8F",
            "Sakura" to "FF9CA8"
        )) {
            create("Material$name") {
                lightThemeFormat = "ThemeOverlay.Light.%s"
                darkThemeFormat = "ThemeOverlay.Dark.%s"
                primaryColor = "#$color"
            }
        }
    }
    // Add Material Design 3 color tokens (such as palettePrimary100) in generated theme
    // rikka.material >= 2.0.0 provides such attributes
    generatePalette = true
}

fun String.execute(currentWorkingDir: File = file("./")): String {
    val byteOut = ByteArrayOutputStream()
    rootProject.exec {
        workingDir = currentWorkingDir
        commandLine = split("\\s".toRegex())
        standardOutput = byteOut
    }
    return String(byteOut.toByteArray()).trim()
}

// ===== 发行版本（唯一真源：仓库根目录 version.properties）=====
// 规则：正式版只在 main 分支发布，每发一次 CI 自动把 patch +1、versionCode +1，构建完再把
//       新号写回 version.properties 提交作基线；其它分支的 CI 构建时从 main 取同一个号
//       （不涨号、不写回），所以测试包与线上正式版的 versionCode 相同，可来回覆盖安装。
//       CI 的做法是把解出来的号直接写进 version.properties 再构建（见 .github/workflows/ci.yml
//       的 Resolve version），因此下面保留的 -PoverrideVersionName / -PoverrideVersionCode
//       只给本地手工跳号用，CI 不再传。
val releaseProps = Properties().also { it.load(rootProject.file("version.properties").inputStream()) }
val fileCode = releaseProps.getProperty("VERSION_CODE").trim().toInt()
val fileTag = releaseProps.getProperty("VERSION_NAME").trim()
// -Poverride* 手传的版本号（本地临时跳号用）；不传就用文件里的值
val verCode = (findProperty("overrideVersionCode") as String?)?.takeIf { it.isNotBlank() }?.trim()?.toInt() ?: fileCode
val verTag = (findProperty("overrideVersionName") as String?)?.takeIf { it.isNotBlank() }?.trim() ?: fileTag
// 发行渠道：CI 按分支传 -Pchannel=release|debug；本地不传默认 release
val channel = (findProperty("channel") as String?)?.takeIf { it.isNotBlank() } ?: "release"
// versionName 统一前缀（与仓库同名）：c001apk_next-V1.0.1-release
val apkPrefix = "c001apk_next"
// 打包时刻，关于页显示用。固定按北京时间打：CI 跑在 UTC，不锁时区的话
// 装机后会看到「编译于 04:12」这种跟本机钟点对不上的值。
// 注意：这里不能写 java.time.*，Gradle Kotlin DSL 里 `java` 会解析成 JavaPluginExtension，
// 把 java 包名整个遮住（Unresolved reference: time），所以走文件顶部的 import。
val buildTime = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm")
    .withZone(ZoneId.of("Asia/Shanghai"))
    .format(Instant.now())

android {
    // 注意：namespace 决定 R / ViewBinding / DataBinding 生成类的包名，
    // 源码里全是 import com.example.c001apk.R / com.example.c001apk.databinding.*，不能跟着改名
    namespace = "com.example.c001apk"
    // compileSdk 35：material 1.14 传递依赖 androidx.core 1.16，其 AAR metadata 要求编译目标 >= 35
    // targetSdk 仍留在 34，避免 Android 15 强制 edge-to-edge 改变既有窗口行为（实验分支先只对齐编译目标）
    compileSdk = 35

    defaultConfig {
        // 包名同样保持不变：改 applicationId 等于换一个 App，老用户无法覆盖安装
        applicationId = "com.example.c001apk"
        minSdk = 24
        targetSdk = 34
        versionCode = verCode
        // 完整 versionName = 前缀-版本号-渠道，渠道后缀由 buildTypes.versionNameSuffix 追加
        versionName = "$apkPrefix-$verTag"

        // 关于页显示「编译于 …」；纯展示字段，不参与逻辑
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val localProperties = Properties().also {
        val properties = rootProject.file("local.properties")
        if (properties.exists())
            it.load(properties.inputStream())
    }
    val config = localProperties.getProperty("KEYSTORE_PATH")?.let {
        signingConfigs.create("release") {
            storeFile = file(it)
            storePassword = localProperties.getProperty("KEYSTORE_PASSWORD")
            keyAlias = localProperties.getProperty("KEY_ALIAS")
            keyPassword = localProperties.getProperty("KEY_PASSWORD")
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        all {
            signingConfig = config ?: signingConfigs["debug"]
            // debug 频道的包 CI 是用 release 变体打的（BuildConfig.DEBUG=false），
            // 但排障需要看云端报文，所以这里单独开一个开关
            buildConfigField("boolean", "HTTP_LOG", (channel == "debug" || name == "debug").toString())
        }
        release {
            // 拼出完整版本名：c001apk_next-V1.0.2-release
            versionNameSuffix = "-$channel"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // 调试包恒为 c001apk_next-V1.0.1-debug
            versionNameSuffix = "-debug"
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
        dataBinding = true
        buildConfig = true
    }
    defaultConfig {
        ndk {
            abiFilters.add("arm64-v8a")
            abiFilters.add("armeabi-v7a")
//            abiFilters.add("armeabi")
//            abiFilters.add("x86")
            abiFilters.add("x86_64")
        }
    }
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
    applicationVariants.configureEach {
        // APK 文件名与 versionName 严格一致：c001apk_next-V1.0.1-release(10000).apk
        val apkFileName = "$versionName($versionCode)"
        outputs.configureEach {
            (this as? ApkVariantOutputImpl)?.outputFileName = "$apkFileName.apk"
        }
    }
}

configurations.configureEach {
    exclude("androidx.appcompat", "appcompat")
}

dependencies {
    androidTestImplementation(libs.androidx.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.leakcanary.android)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.extensions)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.core.ktx)
    implementation(libs.google.android.flexbox)
    implementation(libs.google.android.material)
    implementation(libs.google.dagger.hilt.android)
    ksp(libs.google.dagger.hilt.android.compiler)
    implementation(libs.rikkax.borderview)
    implementation(libs.rikkax.material.preference)
    implementation(libs.rikkax.material)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp3.logging.interceptor)
    implementation(libs.glide)
    ksp(libs.glide.ksp)
    implementation(libs.glide.okhttp3.integration)
    implementation(libs.glide.transformations)
    implementation(project(":mojito"))
    implementation(project(":SketchImageViewLoader"))
    implementation(project(":GlideImageLoader"))
    implementation(libs.appcenter.analytics)
    implementation(libs.appcenter.crashes)
    implementation(libs.jbcrypt)
    implementation(libs.jsoup)
    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.strikethrough)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.image.glide)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    testImplementation(libs.junit)
    implementation(libs.oss.android.sdk)
    implementation(libs.utilcode)

}