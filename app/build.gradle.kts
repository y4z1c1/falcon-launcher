plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.yusuf.falconlauncher"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.yusuf.falconlauncher"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so it installs over debug builds without uninstalling.
            signingConfig = signingConfigs.getByName("debug")
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
}

// Personal data (your own floor plan, default weather spot) lives in the git-ignored src/personal/
// folder and overrides the committed samples in every build. See README › "Your own home".
val personalRes = file("src/personal/res")
if (personalRes.exists()) {
    android.sourceSets.getByName("debug").res.srcDir(personalRes)
    android.sourceSets.getByName("release").res.srcDir(personalRes)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.palette)
}
