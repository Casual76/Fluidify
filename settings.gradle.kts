pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // libadb-android and the SPAKE2 it pairs with, which are published only on JitPack.
        // Exclusive both ways: those two are looked for nowhere else, and nothing else is
        // ever resolved from JitPack.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter {
                includeModule("com.github.MuntashirAkon", "libadb-android")
                includeGroup("com.github.MuntashirAkon.spake2-java")
            }
        }
    }
}

rootProject.name = "Fluidify"
include(":app")
include(":core")
include(":wear-protocol")
include(":wear")

// --- fluid-engine (inizio) ---
val engineDir = file("engine")
if (engineDir.exists()) {
  listOf(
  "engine-foundation",
  "engine-ui",
  "engine-net",
  "engine-update",
  "engine-wear"
  ).forEach { name ->
    include(":$name")
    project(":$name").projectDir = engineDir.resolve(name)
  }
}
// --- fluid-engine (fine) ---
