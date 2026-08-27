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
    }
}

rootProject.name = "Fluidify"
include(":app")

// --- fluid-engine (inizio) ---
val engineDir = file("engine")
if (engineDir.exists()) {
  listOf(
  "engine-foundation",
  "engine-ui",
  "engine-net",
  "engine-update"
  ).forEach { name ->
    include(":$name")
    project(":$name").projectDir = engineDir.resolve(name)
  }
}
// --- fluid-engine (fine) ---
