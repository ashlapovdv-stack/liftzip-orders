plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ru.liftzip.orders"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.liftzip.orders"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull
            ?.toIntOrNull()?.coerceAtLeast(4) ?: 4
        versionName = "0.4.20"
        testInstrumentationRunner = if (providers.gradleProperty("ordersReleaseSmoke").orNull == "true") {
            "ru.liftzip.orders.app.ReleaseSmokeInstrumentation"
        } else {
            "androidx.test.runner.AndroidJUnitRunner"
        }
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        getByName("debug") {
            providers.environmentVariable("ORDERS_KEYSTORE_PATH").orNull?.let { path ->
                storeFile = file(path)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
        }
        release {
            if (providers.environmentVariable("ORDERS_KEYSTORE_PATH").isPresent) {
                signingConfig = signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

androidComponents {
    finalizeDsl { androidDsl ->
        val release = androidDsl.buildTypes.getByName("release")
        check(release.isMinifyEnabled && release.isShrinkResources) {
            "Orders release builds require R8 and resource shrinking."
        }
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.documentfile)
    implementation(libs.google.play.auth)
    implementation(libs.zip4j)
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.room.runtime)
    debugImplementation(libs.compose.ui.tooling)
}
