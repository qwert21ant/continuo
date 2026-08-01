plugins {
    base
}

/**
 * The only place module dependency direction is declared. A module may depend on exactly
 * the projects listed for it, and nothing else. Adapters may depend on platform and core;
 * nothing may depend on an adapter.
 */
val allowedProjectDependencies: Map<String, Set<String>> = mapOf(
    ":platform" to emptySet(),
    ":core" to setOf(":platform"),
    // ":adapters" itself is an implicit parent project created by the colon-segmented
    // `include("adapters:adapter-fabric-1.21.11")` in settings.gradle.kts. It has no
    // build.gradle.kts and declares no dependencies, but it is still a real project in
    // `allprojects` and must be listed or the direction check fails on it.
    ":adapters" to emptySet(),
    ":adapters:adapter-fabric-1.21.11" to setOf(":platform", ":core")
)

val checkDependencyDirection = tasks.register("checkDependencyDirection") {
    group = "verification"
    description = "Fails if any module depends on a project it is not allowed to depend on."
    doLast {
        val violations = mutableListOf<String>()

        allprojects.filter { it != rootProject }.forEach { p ->
            val allowed = allowedProjectDependencies[p.path]
            if (allowed == null) {
                violations += "${p.path} is not listed in allowedProjectDependencies"
                return@forEach
            }
            val actual = p.configurations
                .filter { it.name.endsWith("Implementation") || it.name.endsWith("Api") ||
                          it.name == "implementation" || it.name == "api" }
                .flatMap { it.dependencies }
                .filterIsInstance<ProjectDependency>()
                .map { it.path }
                .toSet()

            (actual - allowed).forEach { bad ->
                violations += "${p.path} must not depend on $bad"
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "Module dependency direction violated:\n" +
                    violations.joinToString("\n") { "  $it" } +
                    "\nSee allowedProjectDependencies in the root build.gradle.kts."
            )
        }
    }
}

tasks.named("check") {
    dependsOn(checkDependencyDirection)
}
