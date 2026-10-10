import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- Signing: keystore.properties (git-ignored) or environment variables. ----
// Without them the release build is signed with the debug key so it still installs for testing.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
fun secret(key: String, env: String): String? = keystoreProps.getProperty(key) ?: System.getenv(env)
val releaseStoreFile = secret("storeFile", "GHPATCHES_KEYSTORE")
val hasReleaseSigning = releaseStoreFile != null && rootProject.file(releaseStoreFile).exists()

android {
    namespace = "dev.mokouliszt.githubpatches"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.mokouliszt.githubpatches"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = secret("storePassword", "GHPATCHES_STORE_PW")
                keyAlias = secret("keyAlias", "GHPATCHES_KEY_ALIAS")
                keyPassword = secret("keyPassword", "GHPATCHES_KEY_PW")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// The tests drive the git CLI with non-ASCII paths and names; give the test JVM a UTF-8 locale.
tasks.withType<Test>().configureEach { environment("LC_ALL", "C.UTF-8") }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
