plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appVersion = "1.0.0"

android {
    namespace = "com.protvirto.capri"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.protvirto.capri"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = appVersion
    }

    signingConfigs {
        val ks = System.getenv("REDLINE_KEYSTORE")
        if (ks != null && file(ks).exists()) {
            create("release") {
                storeFile = file(ks)
                storePassword = System.getenv("REDLINE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("REDLINE_KEY_ALIAS")
                keyPassword = System.getenv("REDLINE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
        compose = true
    }
    androidResources {
        // her art and scripts are served as they are
        noCompress += listOf("webp", "png", "woff2", "js", "css", "html")
    }
}

base {
    archivesName.set("CapriThor-$appVersion")
}

val syncCapri by tasks.registering(Exec::class) {
    description = "Pulls Capri's renderer and art from upstream into assets/capri"
    workingDir = rootDir
    commandLine("python3", "tools/sync_upstream.py")
    onlyIf { !file("src/main/assets/capri/src/renderer/index.html").exists() }
}
tasks.named("preBuild") { dependsOn(syncCapri) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
