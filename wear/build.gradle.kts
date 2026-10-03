import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

/**
 * Fluidify on the watch.
 *
 * Three things here are not choices but requirements of the Wearable Data Layer,
 * which silently drops traffic between two apps that are not "the same app":
 *
 *  - the application id is the phone's, `dev.pampa.fluidify`;
 *  - the signing key is the phone's, read from the same `keystore.properties`;
 *  - every build type signs exactly like its phone counterpart, so a `dev` watch
 *    talks to a `dev` phone.
 *
 * The version is the phone's too, read from `app/build.gradle.kts` rather than
 * written here: one release tag carries both APKs, and the Pampa Store publisher
 * reads the version from that one file.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

/** `versionName` / `versionCode` of the phone app, so the two never drift. */
val phoneBuildScript: String = rootProject.file("app/build.gradle.kts").readText()

fun phoneVersionName(): String =
    Regex("""versionName\s*=\s*"([^"]+)"""").find(phoneBuildScript)?.groupValues?.get(1)
        ?: error("wear: cannot read versionName from app/build.gradle.kts")

fun phoneVersionCode(): Int =
    Regex("""versionCode\s*=\s*(\d+)""").find(phoneBuildScript)?.groupValues?.get(1)?.toInt()
        ?: error("wear: cannot read versionCode from app/build.gradle.kts")

android {
    namespace = "dev.pampa.fluidify.wear"
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        applicationId = "dev.pampa.fluidify"
        // Wear OS 6, the first with Material 3 Expressive across the system and the
        // floor the companion was designed against.
        minSdk = 36
        targetSdk = 36
        versionCode = phoneVersionCode()
        versionName = phoneVersionName()
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                enableV1Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
        // Release without the shrinker, as on the phone: what to install while working.
        create("dev") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += "release"
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                // Real HWUI under Robolectric, so RenderEffect and the AGSL lens draw in the
                // screenshots instead of being skipped.
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                it.maxHeapSize = "3g"
                // Robolectric's Android 16 sandbox reaches into FileDescriptor internals that a
                // modern JDK keeps closed unless asked.
                it.jvmArgs(
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                )
            }
        }
    }
}

dependencies {
    implementation(project(":wear-protocol"))
    implementation(project(":engine-wear"))
    // The same installer the phone updates itself with: download, checks, PackageInstaller.
    implementation(project(":engine-update"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.play.services.wearable)
    implementation(libs.androidx.work.runtime)
    implementation(libs.coil.compose)
    implementation(libs.phosphor)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    debugImplementation(libs.compose.ui.test.manifest)
}

roborazzi {
    // Committed, so the renders can be looked at without building anything.
    outputDir.set(file("screenshots"))
}
