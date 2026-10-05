import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

/**
 * ABIs the debug and dev builds package.
 *
 * arm64 covers every phone made in the last decade. x86_64 is here for one
 * reason: without it no emulator on an ordinary PC can run this app at all, and
 * an app whose whole argument is how it moves cannot be developed without ever
 * seeing it move. The Rust core itself is built in :core (see `coreAbis`
 * there), for these and the watch's.
 *
 * Packaged is not the same as shipped — see [shippedAbis]: the release build
 * type packages only the phone's.
 */
val nativeAbis = listOf("arm64-v8a", "x86_64")

/**
 * ABIs the *release* APK carries.
 *
 * Only arm64: the store's APK has no reason to be several megabytes heavier for
 * an architecture no phone that installs it will ever have. `dev` and `debug`
 * take everything in [nativeAbis], which is what puts the app on an emulator.
 */
val shippedAbis = listOf("arm64-v8a")

/** NDK for AGP (Bungee's CMake build); keep in step with :core's Cargo build. */
val ndkVersionForCargo = "28.2.13676358"

/**
 * The release signing details, when there are any.
 *
 * Kept in `keystore.properties` beside the project and never committed: a
 * keystore in a repository is a keystore anyone can sign as you with. When the
 * file is absent — a fresh clone, or anyone building this who is not its
 * author — the release build falls back to the debug key, which is fine for
 * running it and useless for distributing it.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

// Said out loud rather than left to be noticed, because the mistake it heads
// off cannot be taken back. Android refuses an update signed with a key other
// than the installed copy's, so a release published with the debug key can only
// ever be replaced by another debug-signed build — everyone who installed it
// from the Pampa Store has to uninstall by hand, losing the login and the saved
// queue. The store cannot catch this either: its publisher only checks that an
// APK is signed, not by whom. See docs/pampa-store-release.md.
if (keystoreProperties.isEmpty()) {
    logger.warn(
        "Fluidify: keystore.properties is missing, so release builds are signed with the " +
            "debug key and must not be published.",
    )
}

android {
    namespace = "dev.lelonio.square"
    compileSdk = 37
    // The SDK repository only publishes API 37 as the minor-versioned
    // "android-37.0"; without the minor AGP looks for a plain "android-37"
    // that no longer exists as a package.
    compileSdkMinor = 0
    ndkVersion = ndkVersionForCargo

    defaultConfig {
        // Fluidify's own identity, so it installs beside the original Square.
        // The namespace above stays dev.lelonio.square on purpose: the JNI
        // symbols in native/src/ffi.rs are bound to the Java package, not to
        // the application id.
        applicationId = "dev.pampa.fluidify"
        // cpal's Android host is AAudio, which the ndk crate gates at API 26.
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "1.6.4"

        // The shipped set, and only that. AGP takes the **union** of this and
        // whatever a build type adds — clearing the build type's own list does
        // not remove what was named here — so a default of everything is a
        // release that carries everything, whatever the release block says. That
        // is exactly what happened: the store APK came out at 26 MB with an
        // x86_64 library in it that no phone will ever load. The build types
        // that want more say so themselves; see `dev` and `debug` below.
        ndk {
            abiFilters += shippedAbis
        }
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // Also the old JAR signature, which nothing on a modern phone
                // needs: v2 and v3 are what Android verifies, and they live in
                // the zip's trailer rather than inside it. The Pampa Store's
                // publisher decides whether an APK is signed by looking for
                // META-INF/MANIFEST.MF, so without this the release is refused
                // as unsigned before anyone looks at it.
                enableV1Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // The emulator's architecture too; see the note in defaultConfig.
            ndk {
                abiFilters += nativeAbis
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The real key when there is one, the debug key otherwise — see
            // keystoreProperties above. A build signed with the debug key runs
            // and can be measured; it must never be the one handed to anyone,
            // because every machine's debug key is the same well-known one.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }

        /**
         * What to build while working on the app.
         *
         * A profile of a one-line-change build: R8 3m43s of a 4m12s total, then
         * lint-vital at 50s and the Compose mapping at 32s. Compiling the actual
         * Kotlin was ten seconds. Shrinking a build that is going straight onto
         * a test phone buys nothing and costs almost the entire wall clock.
         *
         * Not `debug`, though — a debuggable build turns off the ART optimiser
         * and leaves Compose's own debug instrumentation in, so scrolling and
         * animation measure far worse than they really are, which is the thing
         * this app is mostly being judged on. This is release without the
         * shrinker: same runtime behaviour, none of the wait.
         *
         * Verify on `release` before shipping — R8 is also what would surface a
         * missing keep rule (reflection over the Retrofit and serialization
         * models above all), and that failure only ever appears in a minified
         * build.
         */
        create("dev") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            // Added to the shipped set rather than replacing it: these are
            // unioned, which is the whole trap. Without this the one build type
            // that exists to be run while working on the app is the one an
            // emulator cannot run.
            ndk {
                abiFilters += nativeAbis
            }
            // The engine modules only declare debug/release; without a fallback
            // Gradle cannot pick a variant of them for this build type.
            matchingFallbacks += "release"
            // The real key when there is one: a build signed with a different
            // key than the copy already on the phone cannot replace it, and
            // uninstalling first would take the login and the saved queue with
            // it every time.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    lint {
        // Runs on demand (`./gradlew lint`) rather than inside every release
        // build. It is 50 seconds of a build whose output is a local test
        // install, and it has never been the thing that caught a problem here.
        checkReleaseBuilds = false
    }

    compileOptions {
        // Required by the vendored :innertube module, which reads java.time.
        isCoreLibraryDesugaringEnabled = true
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
        // Off by default since AGP 8; SquareApplication reads BuildConfig.DEBUG.
        buildConfig = true
    }

    // Bungee and its JNI wrapper. The Rust core is not built here — :core
    // cross-compiles it and its jniLibs reach this APK through the dependency.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.maxHeapSize = "3g"
            it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
            if (!it.name.contains("Debug")) it.exclude("**/screenshots/**")
        }
    }

    packaging {
        // The Rust cdylib is already stripped by the release profile; letting
        // Gradle re-strip it with the wrong tool breaks the arm64 build.
        jniLibs.keepDebugSymbols += "**/libsquarecore.so"
    }
}

dependencies {
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.core.ktx)
    // Installs baseline-prof.txt on first run, so the code the bar and the
    // player use is compiled before it is needed rather than while it runs.
    implementation(libs.androidx.profileinstaller)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.compose.ui.test.manifest)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.common)
    implementation(libs.media3.session)
    implementation(libs.media3.exoplayer)
    // DASH, for Spotify's own video: its manifest is a set of segment
    // templates, which is what a DASH source is built to walk.
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.coil.compose)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.backdrop)
    implementation(libs.kyant.shapes)
    implementation(libs.phosphor)

    // The engine: librespot, its bridge and the player, shared with the watch.
    implementation(project(":core"))

    // Fluid Engine: design system (porta con sé Compose e engine-foundation)
    // e aggiornamento in-app via Pampa Store.
    implementation(project(":engine-ui"))
    implementation(project(":engine-update"))

    // The watch companion: what the two apps say to each other, and the Data Layer that carries it.
    implementation(project(":wear-protocol"))
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
    // Installing the watch app from the phone over wireless debugging; see wear/install.
    // Conscrypt for the TLS 1.3 key export that Wear OS's pairing needs.
    implementation(libs.libadb.android)
    implementation(libs.conscrypt.android)
    // Already libadb's own, at runtime; named here because the phone's key and certificate
    // for the pairing are made with it.
    implementation(libs.bouncycastle.prov)

    coreLibraryDesugaring(libs.desugaring)
}

/** Pinned so a build is not at the mercy of upstream's next commit. */
val bungeeTag = "v2.4.24"

/**
 * Clones Bungee, the time-stretching library behind the speed and pitch
 * controls.
 *
 * Fetched rather than vendored: Bungee is MPL-2.0, and keeping the licensed
 * sources exactly as upstream published them — pinned to a tag, never edited —
 * keeps the obligation simple. The build only compiles them; the patched copy
 * of librespot under native/vendor is vendored precisely because it *is*
 * modified, and that difference is the reason for treating the two differently.
 */
val fetchBungee by tasks.registering {
    group = "build"
    description = "Clones the Bungee time-stretch library if it is not present"

    // Everything is resolved here rather than inside the action: a task action
    // that touches the build script gets captured with it, which the
    // configuration cache refuses to serialize.
    val target = rootProject.file("native-dsp/bungee")
    val marker = target.resolve("bungee/Bungee.h")
    val tag = bungeeTag
    outputs.dir(target)

    doLast {
        if (marker.exists()) return@doLast
        target.deleteRecursively()
        target.parentFile.mkdirs()

        val process = ProcessBuilder(
            "git", "clone",
            "--depth", "1",
            "--branch", tag,
            "--recurse-submodules", "--shallow-submodules",
            "https://github.com/bungee-audio-stretch/bungee.git",
            target.absolutePath,
        ).inheritIO().start()

        check(process.waitFor() == 0) { "could not clone Bungee $tag into $target" }
    }
}



// CMake reads Bungee's sources at configure time, so the clone has to have
// happened before any of the native build tasks run.
tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }
    .configureEach { dependsOn(fetchBungee) }

// Native libraries are built by :core now. A leftover app/src/main/jniLibs from before the move is
// ignored by git but not by AGP, which merges it, warns about duplicates and may prefer the stale
// copies to the fresh ones: the build fails here instead, saying what to delete.
val checkNoStaleJniLibs by tasks.registering {
    val stale = layout.projectDirectory.dir("src/main/jniLibs")
    inputs.files(fileTree(stale) { include("**/*.so") }).optional()
    doLast {
        val libraries = stale.asFileTree.matching { include("**/*.so") }.files
        if (libraries.isNotEmpty()) {
            throw GradleException(
                "Old native libraries in app/src/main/jniLibs (${libraries.size} files): delete that folder. " +
                    "The engine is built by :core now; these would shadow it.",
            )
        }
    }
}
tasks.named("preBuild") { dependsOn(checkNoStaleJniLibs) }


// Robolectric uses Conscrypt's desktop native library; the ADB pairing dependency
// carries its Android JNI instead. Exclude that duplicate only from JVM tests.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "org.conscrypt", module = "conscrypt-android")
}
