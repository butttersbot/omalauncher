rootProject.name = "Omalauncher"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":app")
include(":owl")
include(":chat")
include(":desktop")

// Optional isolated Android UI fixture; never part of a normal launcher build.
if (providers.gradleProperty("artifactPreview").isPresent) {
    include(":artifact-preview")
    project(":artifact-preview").projectDir = file("tools/artifact-preview")
}

// Standalone, read-only outcome browser; no launcher dependency or wallet.
if (providers.gradleProperty("hunch").isPresent) include(":hunch")
// Soma Messages: a standalone app beside the launcher, on Soma's shared looks and composer (features/soma-messages in ClaudeSync).
include(":messages")
