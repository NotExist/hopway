import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val coreDir = rootProject.layout.projectDirectory.dir("core")
val coreAar = layout.projectDirectory.file("libs/sshvpn.aar")
val sdkDir: String = Properties().run {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
    getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME").orEmpty()
}
val goNdkVersion = "28.2.13676358"

// Go 資料平面 → AAR(gomobile 版本釘在 core/go.mod 的 tool 區塊)。
val buildGoCore = tasks.register<Exec>("buildGoCore") {
    description = "Builds core/ (Go) into app/libs/sshvpn.aar via gomobile bind"
    inputs.files(fileTree(coreDir) { include("**/*.go", "go.mod", "go.sum"); exclude("**/*_test.go") })
    outputs.file(coreAar)
    workingDir(coreDir)
    val gobin = layout.buildDirectory.dir("gobin").get().asFile
    environment("ANDROID_HOME", sdkDir)
    environment("ANDROID_NDK_HOME", "$sdkDir/ndk/$goNdkVersion")
    environment("PATH", "${gobin.absolutePath}:${System.getenv("PATH")}")
    commandLine(
        "sh", "-c",
        "go build -o '${gobin.absolutePath}/' golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind && " +
            "gomobile bind -target=android/arm64,android/amd64 -androidapi 26 " +
            "-javapkg=io.github.sshtunnelvpn -trimpath -ldflags='-s -w -buildid=' " +
            "-o '${coreAar.asFile.absolutePath}' .",
    )
}
tasks.named("preBuild") { dependsOn(buildGoCore) }

android {
    namespace = "io.github.sshtunnelvpn"
    compileSdk = 37
    ndkVersion = goNdkVersion

    defaultConfig {
        applicationId = "io.github.sshtunnelvpn"
        minSdk = 26
        targetSdk = 36
        // CI 以 -PversionCode=<分鐘級 Unix timestamp> 帶入,每次建置遞增;本地預設 1
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = "0.1.0"
        // 與 gomobile -target 一致;gVisor 不支援 32-bit ARM,不能讓 32-bit 裝置裝了才崩潰
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        // 固定 debug 金鑰(CI 由 secret 解出並以 DEBUG_KEYSTORE 指定路徑):簽章不變才能覆蓋安裝、保留 App 資料
        System.getenv("DEBUG_KEYSTORE")?.let { path ->
            getByName("debug") {
                storeFile = file(path)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        // 若有 keystore.properties 則用正式金鑰,否則 release 退回 debug 簽章以便側載測試
        val ksFile = rootProject.file("keystore.properties")
        if (ksFile.exists()) {
            val p = Properties().apply { ksFile.inputStream().use(::load) }
            create("release") {
                storeFile = rootProject.file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs.useLegacyPackaging = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(files(coreAar).builtBy(buildGoCore))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
