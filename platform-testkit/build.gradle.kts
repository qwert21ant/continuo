plugins {
    id("continuo-pure-module")
}

// JUnit is an `api` dependency, not `testImplementation`: AdapterConformanceTest is a
// production type of this module — consumers extend it from their own test source sets —
// so JUnit is on this module's MAIN compile classpath, and must be on the consumer's too.
dependencies {
    api(project(":platform"))
    api(project(":core"))

    val junitVersion = project.property("junit_version") as String
    api("org.junit.jupiter:junit-jupiter:$junitVersion")
}
