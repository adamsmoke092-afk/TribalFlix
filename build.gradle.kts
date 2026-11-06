plugins {
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
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
