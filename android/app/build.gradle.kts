plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.openkiosk"
    compileSdk = 36

    defaultConfig {
        applicationId = providers.gradleProperty("openkiosk.applicationId").get()
        // Android 8.1: the oldest platform the reference wall panel runs.
        minSdk = 27
        targetSdk = 36
        // Release builds set these from the git tag; see .github/workflows/release.yml.
        versionCode = providers.gradleProperty("openkiosk.versionCode").getOrElse("1").toInt()
        versionName = providers.gradleProperty("openkiosk.versionName").getOrElse("0.1.0")
    }

    // The signing key never lives in the repository. CI passes it in through
    // environment variables backed by GitHub Actions secrets; without them a
    // release build is produced unsigned.
    val keystorePath = System.getenv("OPEN_KIOSK_KEYSTORE")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("OPEN_KIOSK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OPEN_KIOSK_KEY_ALIAS")
                keyPassword = System.getenv("OPEN_KIOSK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = true
        warningsAsErrors = true
        // "A newer version exists" checks change with the calendar, not the
        // code. Report them without failing the build.
        informational += setOf("OldTargetApi", "GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
