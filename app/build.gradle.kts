import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningPropertiesFile = rootProject.file("keystore.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.isFile) {
        releaseSigningPropertiesFile.inputStream().use { load(it) }
    }
}
val releaseKeystorePath = releaseSigningProperties.getProperty("storeFile")?.takeIf(String::isNotBlank)
val releaseKeystoreFile = releaseKeystorePath?.let(rootProject::file)
val releaseStorePassword = releaseSigningProperties.getProperty("storePassword")?.takeIf(String::isNotBlank)
val releaseKeyAlias = releaseSigningProperties.getProperty("keyAlias")?.takeIf(String::isNotBlank)
val releaseKeyPassword = releaseSigningProperties.getProperty("keyPassword")?.takeIf(String::isNotBlank)
val releaseSigningReady = releaseKeystoreFile?.isFile == true &&
    releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null

android {
    namespace = "com.nekoplayer.app"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.nekoplayer.app"
        minSdk = 23
        targetSdk = 36
        versionCode = 12
        versionName = "0.5.2"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    group = "verification"
    doLast {
        if (!releaseSigningReady) {
            val expectedKeystore = releaseKeystoreFile?.absolutePath ?: "<storeFile is missing>"
            throw GradleException(
                "Release signing is not ready (keystore not found or signing properties are incomplete: " +
                    "$expectedKeystore). Fill in ${releaseSigningPropertiesFile.absolutePath} and make sure its keystore exists."
            )
        }
    }
}

val copyReleaseApkToReleaseDirectory = tasks.register<Copy>("copyReleaseApkToReleaseDirectory") {
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.layout.projectDirectory.dir("app/release"))
}

tasks.configureEach {
    when (name) {
        "packageRelease" -> dependsOn(validateReleaseSigning)
        "assembleRelease" -> finalizedBy(copyReleaseApkToReleaseDirectory)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.media3:media3-exoplayer:1.9.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.9.0")
    implementation("androidx.media3:media3-exoplayer-dash:1.9.0")
    implementation("androidx.media3:media3-ui:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.github.ankidroid:Anki-Android:api-v1.1.0")

    // Runtime ABI dependencies provided to Aniyomi-compatible video extensions.
    implementation("io.reactivex:rxjava:1.3.8")
    implementation("com.github.null2264.injekt:injekt-core:4135455a2a")
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("com.squareup.okhttp3:okhttp-brotli:5.4.0")
    implementation("com.squareup.okhttp3:okhttp-zstd:5.4.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:5.4.0")
    implementation("com.squareup.okhttp3:logging-interceptor:5.4.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json-okio:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.8.1")
    debugImplementation("com.android.tools.studio.leakcanary:leakcanary:1.0.0")
}
