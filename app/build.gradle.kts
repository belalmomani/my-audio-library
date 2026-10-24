import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// القيم الحساسة تُقرأ من local.properties (غير مرفوع إلى Git) أو من متغيرات البيئة (CI). انظر .env.example
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(name: String, default: String): String =
    (localProps.getProperty(name) ?: System.getenv(name) ?: default)

android {
    namespace = "com.mylibrary.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mylibrary.audiolibrary"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // القيم التالية عامة بطبيعتها (anon key محمي بـ RLS). لا تضع service_role هنا أبدًا.
        buildConfigField("String", "SUPABASE_URL", "\"${cfg("SUPABASE_URL", "")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${cfg("SUPABASE_ANON_KEY", "")}\"")
        buildConfigField("String", "BACKEND_URL", "\"${cfg("BACKEND_URL", "")}\"")
        buildConfigField("String", "STARTIO_APP_ID", "\"${cfg("STARTIO_APP_ID", "REPLACE_WITH_STARTIO_APP_ID")}\"")
        buildConfigField("String", "PRIVACY_POLICY_URL", "\"${cfg("PRIVACY_POLICY_URL", "")}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val ks = cfg("KEYSTORE_PATH", "")
            if (ks.isNotEmpty()) {
                storeFile = file(ks)
                storePassword = cfg("KEYSTORE_PASSWORD", "")
                keyAlias = cfg("KEY_ALIAS", "")
                keyPassword = cfg("KEY_PASSWORD", "")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (cfg("KEYSTORE_PATH", "").isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].java.srcDir("src/main/kotlin")
    sourceSets["test"].java.srcDir("src/test/kotlin")
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.security.crypto)
    implementation(libs.startio)

    testImplementation(libs.junit)
}
