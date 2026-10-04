plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.okanetsolutions.briareus.quest"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.okanetsolutions.briareus.quest"
        // Horizon OS on Quest 2, 3, 3S and Pro is Android 12L (API 32) or later.
        minSdk = 32
        targetSdk = 34
        versionCode = providers.gradleProperty("versionCode").map(String::toInt).getOrElse(1)
        versionName = providers.gradleProperty("versionName").getOrElse("0.1.0")
    }

    signingConfigs {
        // A release is signed with the key CI is given; without one it stays unsigned.
        create("release") {
            val store = System.getenv("BRIAREUS_KEYSTORE")
            if (store != null) {
                storeFile = file(store)
                storePassword = System.getenv("BRIAREUS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BRIAREUS_KEY_ALIAS")
                keyPassword = System.getenv("BRIAREUS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("BRIAREUS_KEYSTORE") != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    lint {
        abortOnError = true
        warningsAsErrors = true
        // Horizon OS is Android 12L to 14; targeting 34 is deliberate until Meta's newest OS is the floor.
        disable += setOf("OldTargetApi", "GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

// Kotlin is built into AGP 9; its JVM target follows compileOptions.
kotlin {
    compilerOptions { allWarningsAsErrors.set(providers.gradleProperty("werror").isPresent) }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    // The lint tool AGP runs resolves its own old copies of these; the same patched versions as the plugin classpath.
    constraints {
        add("androidLintTool", libs.bouncycastle.bcprov)
        add("androidLintTool", libs.bouncycastle.bcpkix)
        add("androidLintTool", libs.bouncycastle.bcutil)
        add("androidLintTool", libs.commons.lang3)
        add("androidLintTool", libs.httpclient)
    }
}
