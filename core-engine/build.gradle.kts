plugins {
    id("continuo-pure-module")
}

// :core-pathfinder is `api`, not `implementation`: PathExecutor's public surface names Pos, and
// ContinuoCore's consumers reach BlockLookup through it, so both need those types on their own
// compile classpath.
dependencies {
    api(project(":core-pathfinder"))

    val junitVersion = project.property("junit_version") as String
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(project(":platform-testkit"))
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
