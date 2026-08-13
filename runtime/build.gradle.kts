plugins {
    id("continuo-pure-module")
}

// :core is `api`, not `implementation`: AdapterRuntime's public constructor takes a CoreApi,
// so every consumer needs that type on its own compile classpath.
dependencies {
    api(project(":platform"))
    api(project(":core"))

    val junitVersion = project.property("junit_version") as String
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation(project(":platform-testkit"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
