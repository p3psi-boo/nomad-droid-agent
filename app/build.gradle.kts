import org.gradle.api.tasks.Exec

plugins {
    id("com.android.application")
}

android {
    namespace = "com.nomad.droid"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.nomad.droid"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "android.app.Instrumentation"
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

val buildNomadNative by tasks.registering(Exec::class) {
    val script = rootProject.file("native/build-android.sh")
    val nativeSources = rootProject.fileTree("native/nomadcore") {
        include("**/*.go", "go.mod", "go.sum")
    }
    val output = layout.projectDirectory.file(
        "src/main/jniLibs/arm64-v8a/libnomad_android.so",
    )

    inputs.files(nativeSources)
    inputs.file(script)
    outputs.file(output)

    commandLine("bash", script.absolutePath, output.asFile.absolutePath)
}

tasks.named("preBuild").configure {
    dependsOn(buildNomadNative)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
