plugins {
    id("continuo-pure-module")
}

// :core is `api`, not `implementation`: AdapterRuntime's public constructor takes a CoreApi,
// so every consumer needs that type on its own compile classpath.
dependencies {
    api(project(":platform"))
    api(project(":core"))

    // The probe's public API names PathOutcome, so :core-pathfinder is `api` rather than
    // `implementation`. :core-movement is named directly for CapabilitySet and Capability, not
    // only reached through the pathfinder, so it is declared rather than left transitive.
    api(project(":core-pathfinder"))
    api(project(":core-movement"))

    // Discovered by ServiceLoader at runtime, never compiled against. Without this nothing puts
    // the parkour movement on the game's classpath - the adapters depend on :runtime and never
    // on the movement modules - and the probe would request Capability.PARKOUR from a registry
    // with nothing to grant it. Every search would silently run the four built-ins and look
    // exactly like one that exercised parkour.
    runtimeOnly(project(":movement-parkour"))

    val junitVersion = project.property("junit_version") as String
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation(project(":platform-testkit"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
