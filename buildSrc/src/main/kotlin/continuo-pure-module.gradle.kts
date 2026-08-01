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

val checkCorePurity = tasks.register("checkCorePurity") {
    group = "verification"
    description = "Fails if this module references net.minecraft in bytecode or dependencies."
    dependsOn(tasks.named("classes"))
    val dir = mainClassesDir
    val configuration = configurations.named("compileClasspath")
    doLast {
        val offendingClasses = ClassScan.classFiles(dir.get().asFile)
            .filter { ClassScan.containsAscii(it, "net/minecraft") }
            .map { it.name }

        val offendingDeps = configuration.get().resolvedConfiguration
            .lenientConfiguration.allModuleDependencies
            .map { "${it.moduleGroup}:${it.moduleName}" }
            .filter { it.contains("minecraft", ignoreCase = true) }

        if (offendingClasses.isNotEmpty() || offendingDeps.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Core purity violated in ${project.path}.")
                    if (offendingClasses.isNotEmpty()) {
                        appendLine("  Classes referencing net/minecraft: $offendingClasses")
                    }
                    if (offendingDeps.isNotEmpty()) {
                        appendLine("  Minecraft dependencies on the compile classpath: $offendingDeps")
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
        val offenders = ClassScan.classFiles(dir.get().asFile)
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

tasks.named("check") {
    dependsOn(checkCorePurity, checkCoreBytecode)
}
