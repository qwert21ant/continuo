plugins {
    id("continuo-pure-module")
}

dependencies {
    // Deliberately NOT :core-pathfinder. A movement must be writable without access to the
    // search, and checkDependencyDirection fails the build if that ever stops being true.
    api(project(":core-movement"))

    val junitVersion = project.property("junit_version") as String
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    // Test-scoped only, so the seam above still holds: the end-to-end test needs a real A* to
    // prove a discovered movement is actually used by a search.
    testImplementation(project(":core-pathfinder"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
