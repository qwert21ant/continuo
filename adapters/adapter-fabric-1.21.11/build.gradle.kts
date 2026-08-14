plugins {
    java
    // The Kotlin DSL `plugins {}` block is evaluated in a restricted scope with no access
    // to `property()`/`providers` from the enclosing script (a Gradle limitation, confirmed
    // by "Unresolved reference" at configuration time), so this version cannot be read from
    // gradle.properties. It is deliberately a literal and this line is the single source of
    // truth for it — do not add a loom_version property expecting it to take effect.
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
    implementation(project(":runtime"))
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
