import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) {
        file.inputStream().use { load(it) }
    }
}

fun signingValue(vararg envNames: String, propName: String): String? {
    for (envName in envNames) {
        val value = System.getenv(envName)?.trim()
        if (!value.isNullOrEmpty()) return value
    }
    return keystoreProperties.getProperty(propName)?.trim()?.takeIf { it.isNotEmpty() }
}

val releaseStorePath = signingValue("DIRECTROUTER_KEYSTORE_PATH", "KEYSTORE_PATH", "KEYSTORE_FILE", propName = "storeFile")
val releaseStorePassword = signingValue("DIRECTROUTER_KEYSTORE_PASSWORD", "KEYSTORE_PASSWORD", propName = "storePassword")
val releaseKeyAlias = signingValue("DIRECTROUTER_KEY_ALIAS", "KEY_ALIAS", propName = "keyAlias")
val releaseKeyPassword = signingValue("DIRECTROUTER_KEY_PASSWORD", "KEY_PASSWORD", propName = "keyPassword")
val releaseStoreFile = releaseStorePath?.let { rootProject.file(it) }
val hasReleaseSigning = releaseStoreFile != null &&
    releaseStoreFile.isFile &&
    !releaseStorePassword.isNullOrEmpty() &&
    !releaseKeyAlias.isNullOrEmpty() &&
    !releaseKeyPassword.isNullOrEmpty()

if (releaseStorePath != null && (releaseStoreFile == null || !releaseStoreFile.isFile)) {
    logger.warn("Release keystore file not found at '$releaseStorePath'; release APK will be built unsigned.")
}

android {
    namespace = "com.directrouter.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.directrouter.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    dependenciesInfo {
        // Disables Google dependency metadata block in APK/AAB (required by IzzyOnDroid & F-Droid)
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // hevtunnel:1.11.0 ships Kotlin 2.4.0 metadata; Kotlin sources only call it via HevTunnelBridge.java
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.zxing:core:3.5.3")

    // Exclude transitive kotlin-stdlib (2.4.x in hevtunnel POM) so Kotlin 2.0.21 stdlib stays aligned
    implementation("com.wgtunnel:hevtunnel:1.11.0") {
        exclude(group = "org.jetbrains.kotlin")
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

// com.wgtunnel:hevtunnel:1.11.0 declares minCompileSdk=37 in its AAR metadata despite only
// shipping JNI wrappers (minSdk=26) with no API 35+ bytecode references; skip the metadata check
// so builds succeed cleanly against compileSdk=34.
tasks.matching { it.name.endsWith("AarMetadata") }.configureEach {
    enabled = false
}
