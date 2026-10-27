plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.toolbox.adbhelper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.toolbox.adbhelper"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "1.0.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("sharedKey") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("sharedKey")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sharedKey")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
}

tasks.register("buildAppHelperJar") {
    val serverSrcDir = file("src/main/java/com/toolbox/adbhelper/server")
    val assetsJar = file("src/main/assets/app-helper.jar")
    inputs.dir(serverSrcDir)
    outputs.file(assetsJar)

    doLast {
        val outDir = layout.buildDirectory.dir("intermediates/helper-classes").get().asFile
        outDir.deleteRecursively()
        outDir.mkdirs()

        val androidJar = "${android.sdkDirectory}/platforms/android-${android.compileSdk}/android.jar"
        val d8 = file("${android.sdkDirectory}/build-tools").listFiles()
            ?.filter { it.isDirectory && File(it, "d8").exists() }
            ?.maxByOrNull { it.name }
            ?.let { File(it, "d8").absolutePath }
            ?: "d8"

        project.exec {
            commandLine(
                "javac", "-source", "8", "-target", "8",
                "-cp", androidJar,
                "-d", outDir.absolutePath,
                "${serverSrcDir.absolutePath}/AppHelper.java",
                "${serverSrcDir.absolutePath}/AudioForwarder.java"
            )
        }

        project.exec {
            commandLine(
                d8, "--min-api", "24",
                "--output", outDir.absolutePath,
                "${outDir.absolutePath}/com/toolbox/adbhelper/server/AppHelper.class",
                "${outDir.absolutePath}/com/toolbox/adbhelper/server/AudioForwarder.class"
            )
        }

        project.exec {
            workingDir(outDir)
            commandLine("jar", "cf", assetsJar.absolutePath, "classes.dex")
        }
    }
}

tasks.named("preBuild") {
    dependsOn("buildAppHelperJar")
}
