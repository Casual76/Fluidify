plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Fluidify's engine, shared by the phone and the watch.
 *
 * The librespot core (Rust, cross-compiled below), its JNI bridge, the Media3
 * player around it and the few pieces those need. Moved out of :app with
 * `git mv`, packages untouched: the JNI symbols in native/src/ffi.rs are bound
 * to `dev.lelonio.square.nativecore.NativeBridge` by name, and every import in
 * the phone app kept working without a change.
 *
 * Nothing here knows which app it runs in; what it needs to ask, it asks
 * through [dev.lelonio.square.playback.CoreHost].
 */

/**
 * ABIs the native core is built for: every one either app ships or runs on.
 *
 * arm64 for phones and current watches, armeabi-v7a for watches whose Wear OS
 * still runs 32-bit apps, x86_64 for the emulator. Each app packages only its
 * own with `abiFilters`; building all three costs a cold build a few minutes
 * and an unchanged crate nothing, because Gradle skips the task. A machine that
 * only ever builds the phone can narrow it with
 * `-Pfluidify.coreAbis=arm64-v8a,x86_64`.
 */
val coreAbis: List<String> = (findProperty("fluidify.coreAbis") as String?)
    ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
    ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")

/** NDK used for the Cargo cross-build; the same as the apps'. */
val ndkVersionForCargo = "28.2.13676358"

/** Maps Android ABI names to Rust target triples. */
val rustTargets = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "armeabi-v7a" to "armv7-linux-androideabi",
    "x86_64" to "x86_64-linux-android",
)

android {
    namespace = "dev.lelonio.square.core"
    compileSdk = 37
    compileSdkMinor = 0
    ndkVersion = ndkVersionForCargo

    defaultConfig {
        // The phone's floor: cpal's Android host is AAudio, gated at API 26.
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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

    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // The player is a Media3 SimpleBasePlayer; its type is part of this module's API.
    api(libs.media3.common)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.serialization.json)
}

/**
 * Cross-compiles the Rust core and drops the resulting `.so` files straight into
 * `jniLibs`.
 *
 * This shells out to `cargo-ndk` instead of using externalNativeBuild because
 * the crate is Cargo-driven, not CMake-driven. Install it once with
 * `cargo install cargo-ndk` and add the targets with `rustup target add`.
 */
val cargoBuild by tasks.registering(Exec::class) {
    group = "build"
    description = "Cross-compiles the librespot core for all configured ABIs"

    val nativeDir = rootProject.file("native")
    workingDir = nativeDir

    // Gradle can skip this entirely when nothing in the crate changed.
    inputs.dir(nativeDir.resolve("src"))
    // The patched copies of librespot's own crates, which are compiled from
    // here rather than fetched. Left out, a change to one of them was not a
    // change to this task, and the build quietly shipped the previous library.
    inputs.dir(nativeDir.resolve("vendor"))
    inputs.file(nativeDir.resolve("Cargo.toml"))
    inputs.file(nativeDir.resolve("Cargo.lock"))
    outputs.dir(layout.projectDirectory.dir("src/main/jniLibs"))

    val outputDir = layout.projectDirectory.dir("src/main/jniLibs").asFile.absolutePath
    val abiArgs = coreAbis.flatMap { listOf("-t", it) }
    // -P 26 must match minSdk: libaaudio.so only exists in the sysroot from API
    // 26 up, and cargo-ndk otherwise links against API 21 and fails with
    // "unable to find library -laaudio".
    // The rustup cargo by absolute path, not whatever `cargo` resolves to first.
    // A Homebrew rust earlier on PATH has neither the Android targets nor a new
    // enough rustc for the dependency tree, and the failure it produces
    // ("requires rustc 1.88") points at the crates rather than at the toolchain.
    // cargo vs cargo.exe: same rustup layout, two spellings of the binary.
    val cargo = sequenceOf("cargo", "cargo.exe")
        .map { File(System.getProperty("user.home"), ".cargo/bin/$it") }
        .firstOrNull { it.canExecute() }?.absolutePath ?: "cargo"
    commandLine(
        listOf(cargo, "ndk") + abiArgs +
            listOf("-P", "26", "-o", outputDir, "build", "--release"),
    )

    // Everything is resolved here rather than in a doFirst block: a task action
    // closure would capture the build script instance, which the configuration
    // cache refuses to serialize.
    val ndkDir = File(android.sdkDirectory, "ndk/$ndkVersionForCargo")
    val unmappedAbis = coreAbis.filterNot { rustTargets.containsKey(it) }
    require(unmappedAbis.isEmpty()) { "No Rust target mapped for ABI(s): $unmappedAbis" }
    require(ndkDir.isDirectory) {
        "NDK $ndkVersionForCargo not found at $ndkDir — install it with " +
            "sdkmanager --install \"ndk;$ndkVersionForCargo\""
    }

    // cargo-ndk finds the toolchain through this; AGP's own ndkVersion is not
    // visible to an external process.
    environment("ANDROID_NDK_HOME", ndkDir.absolutePath)

    // dlltool (used by host build scripts on the GNU toolchain) cannot create
    // import libraries when the .def path contains a space, and this checkout
    // may live under one. Cargo's scratch moves somewhere unspaced; the .so
    // still lands in jniLibs via -o above.
    if (nativeDir.absolutePath.contains(' ')) {
        environment(
            "CARGO_TARGET_DIR",
            File(System.getProperty("user.home"), ".cargo-target/fluidify").absolutePath,
        )
    }

    // cargo-ndk re-invokes plain `cargo` for each target, so pointing the task
    // at the rustup binary is not enough on its own: the child would still pick
    // up a Homebrew rust earlier on PATH, which has neither the Android targets
    // nor a new enough rustc, and reports it as "requires rustc 1.88".
    val cargoToolDirs = listOf(
        File(System.getProperty("user.home"), ".cargo/bin"),
        // Host build scripts pull in windows-sys, which links via raw-dylib
        // and needs a dlltool. rustup's GNU one ships without the assembler it
        // spawns, so this directory holds the NDK's llvm-dlltool renamed
        // dlltool.exe — it writes the import library directly, no assembler.
        File(System.getProperty("user.home"), ".cargo-shims"),
    ).filter { it.isDirectory }
    if (cargoToolDirs.isNotEmpty()) {
        environment(
            "PATH",
            (cargoToolDirs.map { it.absolutePath } + System.getenv("PATH"))
                .joinToString(File.pathSeparator),
        )
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(cargoBuild) }
