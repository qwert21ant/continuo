pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.wagyourtail.xyz/releases") { name = "WagYourTail" }
        maven("https://nexus.gtnewhorizons.com/repository/public/") { name = "GTNH" }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Lets Gradle resolve/auto-provision JVM toolchains (needed for `updateDaemonJvm` to
    // compute per-platform download URLs for gradle/gradle-daemon-jvm.properties, and as a
    // fallback if a machine doesn't already have the required JDK installed).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "continuo"

include("platform")
include("core")
include("core-pathfinder")
include("core-movement")
include("platform-testkit")
include("runtime")
include("adapters:adapter-fabric-1.21.11")
include("adapters:adapter-forge-1.7.10")
