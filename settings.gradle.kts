pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.wagyourtail.xyz/releases") { name = "WagYourTail" }
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "continuo"

include("platform")
include("core")
include("adapters:adapter-fabric-1.21.11")
