plugins {
    id("continuo-pure-module")
}

dependencies {
    api(project(":core"))
    api(project(":core-movement"))

    val junitVersion = project.property("junit_version") as String
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Discovered by ServiceLoader at test runtime only, never compiled against. The
    // MovementKind coverage guard has to see every movement that exists, and parkour lives in a
    // module core-pathfinder does not and must not depend on. Verified safe against the existing
    // suite: the only tests that assert on a movement list read activeFor(CapabilitySet.none()),
    // which filters parkour out because it requires Capability.PARKOUR.
    testRuntimeOnly(project(":movement-parkour"))
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
