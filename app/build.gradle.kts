@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jmailen.kotlinter") version "5.7.0"
}

android {
    namespace = "com.github.gotify"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.github.gotify"
        minSdk = 26
        targetSdk = 36
        versionCode = 37
        versionName = "2.10.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        signingConfig = signingConfigs.getByName("debug")
        resValue("string", "app_name", "Gotify")
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        register("development") {
            applicationIdSuffix = ".dev"
            isDebuggable = true
            resValue("string", "app_name", "Gotify DEV")
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
        resValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources {
            excludes.add("META-INF/DEPENDENCIES")
        }
    }
    lint {
        disable.add("GoogleAppIndexingWarning")
        lintConfig = file("../lint.xml")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

if (project.hasProperty("sign")) {
    android {
        signingConfigs {
            create("release") {
                storeFile = file(System.getenv("RELEASE_STORE_FILE"))
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }
    android.buildTypes.getByName("release").signingConfig = android.signingConfigs.getByName("release")
}

dependencies {
    val coilVersion = "2.7.0"
    val markwonVersion = "4.6.2"
    val tinylogVersion = "2.7.0"
    implementation(project(":client"))
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    implementation("androidx.vectordrawable:vectordrawable:1.2.0")
    implementation("androidx.preference:preference-ktx:1.2.1")

    implementation("com.github.cyb3rko:QuickPermissions-Kotlin:1.1.6")
    implementation("io.coil-kt:coil:$coilVersion")
    implementation("io.coil-kt:coil-svg:$coilVersion")
    implementation("io.noties.markwon:core:$markwonVersion")
    implementation("io.noties.markwon:image-coil:$markwonVersion")
    implementation("io.noties.markwon:image:$markwonVersion")
    implementation("io.noties.markwon:ext-tables:$markwonVersion")
    implementation("io.noties.markwon:ext-strikethrough:$markwonVersion")

    implementation("org.tinylog:tinylog-api-kotlin:$tinylogVersion")
    implementation("org.tinylog:tinylog-impl:$tinylogVersion")

    implementation("com.google.code.gson:gson:2.13.1")
    implementation("com.squareup.retrofit2:retrofit:3.0.0")

}

