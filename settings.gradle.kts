import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "idea-monkeyc"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.jetbrains.intellij.platform.settings") version "2.18.1"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.PREFER_SETTINGS
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
            // LSP4IJ is published to the Marketplace, not to Maven Central.
            marketplace()
        }
    }
}
