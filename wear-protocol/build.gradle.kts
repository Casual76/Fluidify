plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * What the phone and the watch say to each other, and the few decisions both of
 * them have to make the same way.
 *
 * Plain Kotlin on purpose: no Android, no Play Services. The schema and the logic
 * around it (how far the song has gone since the phone last spoke, when a burst
 * of changes is worth a message, which way a download should travel) are what
 * the two apps must never disagree on, and a JVM module is the one place both can
 * depend on and the one place a unit test runs in milliseconds.
 */
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
