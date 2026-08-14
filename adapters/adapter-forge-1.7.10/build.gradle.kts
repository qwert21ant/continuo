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
    implementation(project(":runtime"))
}

// --- Dev-run-only workaround: strip the OpenAL natives before runClient launches ---
//
// `./gradlew :adapters:adapter-forge-1.7.10:runClient` was crashing reproducibly (2/2 runs)
// about 40 seconds after launch with:
//
//   java.lang.UnsatisfiedLinkError: org.lwjgl.openal.AL10.nalGetSourcei(II)I
//     at paulscode.sound.libraries.ChannelLWJGLOpenAL.playing
//     at net.minecraft.client.audio.SoundManager.isSoundPlaying
//     at net.minecraft.client.audio.MusicTicker.update
//     at net.minecraft.client.Minecraft.runTick
//
// Root cause (established from run/logs/fml-client-latest.log, not a defect in this
// project's mod code — our mod never appears in the failure and initialises correctly):
//
//   1. Sound system #1 starts and successfully creates an OpenAL context.
//   2. FML's second startup resource reload calls SoundManager.reloadSoundSystem().
//      paulscode's context teardown for #1 is asynchronous, so #2's AL.create() can run
//      before #1's context is destroyed, and fails with
//      "IllegalStateException: Only one OpenAL context may be instantiated at any one time".
//   3. Because of that, #2 never finishes loading, and paulscode's fixed 30-second
//      initialisation timeout eventually fires.
//   4. The fallback "Switching to No Sound" then tears down the OpenAL binding while a
//      thread from #1 is still calling into it, producing the UnsatisfiedLinkError above
//      and killing the client thread.
//
// This is a race in Minecraft 1.7.10 / paulscode's sound engine that fast machines lose
// reliably; it has nothing to do with anything this module does.
//
// The approved fix is to make OpenAL unavailable for the dev client, not to try to patch
// around the race: with no OpenAL native library present, AL.create() fails immediately
// during the *first* sound system initialisation, paulscode falls back to silent mode
// right away, no OpenAL context is ever created, ChannelLWJGLOpenAL is never exercised,
// and the failing native call is simply unreachable. Deleting both the 32- and 64-bit
// DLLs is deterministic; only OpenAL64.dll is actually loaded by this JVM, but removing
// OpenAL32.dll too avoids relying on that assumption.
//
// This is a dev-run-only workaround, applied by deleting files out of the generated
// run/natives/lwjgl2 directory before runClient launches. It is NOT part of the shipped
// mod artifact and has no effect on anything other than this Gradle task. Running without
// sound is acceptable here because this adapter drives a movement bot; nothing in this
// module or its tests plays or depends on audio.
//
// Configuration-cache note: `runClient`'s action below must not call Project methods like
// file(...) or delete(...) — RetroFuturaGradle 2.x supports the configuration cache, and
// those calls are not serializable into the cache entry. The File objects are therefore
// resolved once here, at configuration time, and only File.delete() (a plain JDK call) is
// invoked inside doFirst.
val lwjgl2NativesDir = layout.projectDirectory.dir("run/natives/lwjgl2").asFile
val openAl32Native = File(lwjgl2NativesDir, "OpenAL32.dll")
val openAl64Native = File(lwjgl2NativesDir, "OpenAL64.dll")

tasks.named("runClient") {
    doFirst {
        openAl32Native.delete()
        openAl64Native.delete()
    }
}
