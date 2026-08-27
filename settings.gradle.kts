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
        // NewPipeExtractor, which powers the YouTube Music backend, publishes
        // nowhere else. Narrowed to that one group so nothing else in the tree
        // can silently start resolving from an unmoderated repository.
        maven("https://jitpack.io") {
            // Its own transitive modules (timeago-parser and friends) are
            // published under `com.github.TeamNewPipe.NewPipeExtractor`, hence
            // the prefix rather than the single group.
            content { includeGroupByRegex("com\\.github\\.TeamNewPipe.*") }
        }
        // Metrolist's extractor fork, which the vendored innertube module needs.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.MetrolistGroup.*") }
        }
    }
}

rootProject.name = "Fluidify"
include(":app")
include(":innertube")

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
