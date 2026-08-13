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

// The AT must be registered on both tasks below, not just deobfuscateMergedJarToSrg.
// deobfuscateMergedJarToSrg applies it to the binary SRG jar (confirmed by inspecting
// build/rfg/srg_merged_minecraft.jar with javap: field_74513_e is public there), but
// compileJava for this module compiles against classes produced by recompiling the
// *decompiled Java source* (compilePatchedMcJava), not that binary jar. Forge's own
// bundled source patches, applied in patchDecompiledJar, reassert the field's original
// "private" text in that decompiled source (verified by inspecting
// build/rfg/mcp_patched_minecraft-sources.jar after a from-scratch run: still private).
// applyJST is RFG's dedicated task for re-applying access transformers to that decompiled
// source after Forge's patches run; without it, applyJST reports SKIPPED (nothing to do)
// and the field reverts. With it configured, the build actually succeeds.
tasks.deobfuscateMergedJarToSrg.configure {
    accessTransformerFiles.from("src/main/resources/META-INF/continuo_at.cfg")
}

tasks.applyJST.configure {
    accessTransformerFiles.from("src/main/resources/META-INF/continuo_at.cfg")
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":core"))
}
