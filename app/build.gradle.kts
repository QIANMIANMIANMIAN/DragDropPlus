import java.util.Properties

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.compose.compiler)
}

// 版本号从 version.properties 读，便于 CI 递增
val versionProps = Properties().apply {
    val f = rootProject.file("version.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val appVersionCode = versionProps.getProperty("versionCode", "1").toInt()
val appVersionName = versionProps.getProperty("versionName", "1.0.0")

android {
    namespace = "com.vivo.dragdrop.plus"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vivo.dragdrop.plus"
        minSdk = 34
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        // libxposed 模块标识，必须与 META-INF/xposed/* 匹配
        buildConfigField("String", "MODULE_VERSION", "\"$appVersionName\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
        // libxposed service 的 XposedProvider 必须打进 APK
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Xposed：API 由框架注入（compileOnly），service 需打进 APK
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
}
