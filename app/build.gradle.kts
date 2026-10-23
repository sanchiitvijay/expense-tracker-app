plugins { id("com.android.application") }

android {
    namespace = "com.sanchit.expenses"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sanchit.expenses"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Personal sideload build: signed with the local debug key
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo { includeInApk = false; includeInBundle = false }
    packaging { resources.excludes += listOf("META-INF/**", "kotlin/**", "DebugProbesKt.bin") }
}
