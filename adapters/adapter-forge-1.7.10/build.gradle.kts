plugins {
    java
    // The Kotlin DSL `plugins {}` block is evaluated in a restricted scope with no access to
    // `property()` from the enclosing script, exactly as the Fabric module documents, so this
    // version is a literal and this line is the single source of truth for it.
    id("com.gtnewhorizons.retrofuturagradle") version "2.0.3"
}

// Forge 1.7.10 runs on Java 8. This is the only module in the build that both compiles and
// runs on 8; `platform` and `core` compile to 8 bytecode from a 21 toolchain.
java {
    toolchain { languageVersion = JavaLanguageVersion.of(8) }
}

repositories {
    mavenCentral()
    maven("https://nexus.gtnewhorizons.com/repository/public/") { name = "GTNH" }
}

minecraft {
    mcVersion.set(property("forge_mc_version") as String)
    username.set("Developer")
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":core"))
}
