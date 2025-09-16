plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
    kotlin("jvm") version "2.2.0" apply false
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}

rootProject.name = "FileIndexer"
include(":fileindex-core", ":fileindex-demo")