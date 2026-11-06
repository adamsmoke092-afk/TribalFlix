buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.6.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.20")
    }
}

plugins {
    id("com.android.application") apply false
    id("org.jetbrains.kotlin.android") apply false
    id("org.jetbrains.kotlin.plugin.compose") apply false
}

allprojects {
    configurations.all {
        resolutionStrategy.eachDependency {
            // Force Kotlin stdlib to a version compatible with Kotlin 2.0.20
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion("2.0.20")
            }
        }
    }
}

task clean(type: Delete) {
    delete(rootProject.buildDir)
}
