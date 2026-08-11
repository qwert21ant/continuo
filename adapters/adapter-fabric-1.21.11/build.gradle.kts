plugins {
    java
    // The Kotlin DSL `plugins {}` block is evaluated in a restricted scope that has no
    // access to `property()`/`providers` from the enclosing script (a Gradle limitation,
    // confirmed by "Unresolved reference" at configuration time), so the version cannot be
    // read from gradle.properties here as the brief's template assumed. Hardcoded literal;
    // value still tracked in gradle.properties (loom_version) for documentation.
    id("fabric-loom") version "1.17.17"
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")

    implementation(project(":platform"))
    implementation(project(":core"))
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
