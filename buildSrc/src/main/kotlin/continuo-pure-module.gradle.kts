import dev.continuo.build.ClassScan

plugins {
    `java-library`
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 8
}

repositories { mavenCentral() }

val mainClassesDir = layout.buildDirectory.dir("classes/java/main")

// Forbidden loader/game package prefixes, in both the slash form the JVM constant pool uses
// for internal class names and the dot form used by string literals such as
// Class.forName("net.minecraft.client.Minecraft"). Scanning only one form lets the other
// evade detection entirely.
val forbiddenBytecodeNeedles = listOf(
    "net/minecraft", "net.minecraft",
    "net/fabricmc", "net.fabricmc",
    "net/minecraftforge", "net.minecraftforge",
    "cpw/mods", "cpw.mods"
)

// Dependency GROUP prefixes that indicate a loader/game artifact. Matched against the
// Maven group only (never the whole "group:name" string) so that an unrelated artifact
// merely named e.g. "minecraft-something" by a third party doesn't create noise, while a
// group that starts with one of these, or otherwise contains "minecraft", is always caught.
val forbiddenGroupPrefixes = listOf("net.fabricmc", "net.minecraftforge", "cpw.mods")

val checkCorePurity = tasks.register("checkCorePurity") {
    group = "verification"
    description = "Fails if this module references net.minecraft in bytecode or dependencies."
    dependsOn(tasks.named("classes"))
    val dir = mainClassesDir
    val compileClasspath = configurations.named("compileClasspath")
    val runtimeClasspath = configurations.named("runtimeClasspath")
    doLast {
        val classFiles = ClassScan.classFiles(dir.get().asFile)
        check(classFiles.isNotEmpty()) {
            "checkCorePurity scanned no class files in ${project.path} — the check is broken, not passing"
        }

        val offendingClasses = classFiles
            .filter { file -> forbiddenBytecodeNeedles.any { needle -> ClassScan.containsAscii(file, needle) } }
            .map { it.name }

        val offendingDeps = (compileClasspath.get().resolvedConfiguration.lenientConfiguration.allModuleDependencies +
                runtimeClasspath.get().resolvedConfiguration.lenientConfiguration.allModuleDependencies)
            .map { "${it.moduleGroup}:${it.moduleName}" }
            .filter { dep ->
                val group = dep.substringBefore(":")
                forbiddenGroupPrefixes.any { group.startsWith(it) } || group.contains("minecraft", ignoreCase = true)
            }
            .distinct()

        if (offendingClasses.isNotEmpty() || offendingDeps.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Core purity violated in ${project.path}.")
                    if (offendingClasses.isNotEmpty()) {
                        appendLine("  Classes referencing forbidden packages: $offendingClasses")
                    }
                    if (offendingDeps.isNotEmpty()) {
                        appendLine("  Loader/game dependencies on the compile or runtime classpath: $offendingDeps")
                    }
                    append("  The core must not know Minecraft exists. Move this to an adapter.")
                }
            )
        }
    }
}

val checkCoreBytecode = tasks.register("checkCoreBytecode") {
    group = "verification"
    description = "Fails if any main class exceeds Java 8 bytecode (major version 52)."
    dependsOn(tasks.named("classes"))
    val dir = mainClassesDir
    doLast {
        val classFiles = ClassScan.classFiles(dir.get().asFile)
        check(classFiles.isNotEmpty()) {
            "checkCoreBytecode scanned no class files in ${project.path} — the check is broken, not passing"
        }

        val offenders = classFiles
            .map { it to ClassScan.majorVersion(it) }
            .filter { (_, major) -> major > 52 }

        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Java 8 bytecode required in ${project.path}, but found: " +
                    offenders.joinToString { (f, major) -> "${f.name} (major $major)" } +
                    ". Forge 1.7.10 runs on Java 8 and must load this jar."
            )
        }
    }
}

// Nothing else in the build reads the javadoc, so a broken {@link} in the SPI's behavioural
// contract would be invisible. -Xwerror promotes doclint warnings to failures; -missing is
// excluded because not every member is documented and requiring that is a separate argument.
// -Xwerror is safe only because Javadoc never inherits options.release = 8 from JavaCompile,
// so doclint never sees the source-8-is-obsolete warning javac emits. Do not set a source or
// release level here — that would turn that unrelated deprecation notice into a build failure.
tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        addBooleanOption("Xdoclint:all,-missing", true)
        addBooleanOption("Xwerror", true)
    }
}

tasks.named("check") {
    dependsOn(checkCorePurity, checkCoreBytecode, tasks.named("javadoc"))
}
