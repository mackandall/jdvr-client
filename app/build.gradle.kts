import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.plugin.serialization)
}

android {
    namespace = "com.mackandall.jdvr"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.mackandall.jdvr"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // The version is jdvr.version in gradle.properties ("1.2.3"); the code Android compares is
        // made from it (1.2.3 → 10203), so a higher version always installs as an update.
        val version = providers.gradleProperty("jdvr.version").get()
        val (major, minor, patch) = version.split(".").map { it.toInt() }
        versionCode = major * 10000 + minor * 100 + patch
        versionName = version
    }

    // Release key: its location and passwords come from ~/.gradle/gradle.properties (JDVR_KEYSTORE,
    // JDVR_KEYSTORE_PASSWORD, JDVR_KEY_ALIAS, JDVR_KEY_PASSWORD), never from the repo. Every release
    // must be signed with the same key, or it won't install over the one before.
    val releaseKey = providers.gradleProperty("JDVR_KEYSTORE").orNull?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = releaseKey
                storePassword = providers.gradleProperty("JDVR_KEYSTORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("JDVR_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("JDVR_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            // Without the release key on this machine, the local debug key (installs, but not as an update).
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar)
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)
    implementation(libs.media3.ffmpeg.decoder)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.jellyfin.core)
    implementation(libs.jellyfin.api)
    implementation(libs.jellyfin.api.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    implementation(libs.timber)
    testImplementation("junit:junit:4.13.2")
}
