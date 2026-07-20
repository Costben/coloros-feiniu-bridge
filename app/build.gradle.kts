plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val xposedCompileApiVersion = "82"

android {
    namespace = "io.github.colorosfeiniu.bridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.colorosfeiniu.bridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.1.3"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Pure legacy Xposed Bridge module. Do not add libxposed entry points here.
    compileOnly("de.robv.android.xposed:api:$xposedCompileApiVersion")
    testImplementation("junit:junit:4.13.2")
}
