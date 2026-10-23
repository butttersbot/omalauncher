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
