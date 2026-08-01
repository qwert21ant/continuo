# A1 Walking Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Minecraft bot walks forward for 40 ticks on Fabric 1.21.11, driven by a Java 8 core jar containing zero references to Minecraft, with the core/game boundary enforced by build-failing checks.

**Architecture:** Three Gradle modules plus `buildSrc`. `platform` holds the SPI (four interfaces, three enums); `core` holds one class, `ContinuoCore`, tested headlessly against fake implementations; `adapters/adapter-fabric-1.21.11` is the only module that sees `net.minecraft`, and it does nothing but translate. Three `buildSrc` checks fail the build if Minecraft leaks into the core, if module dependencies point the wrong way, or if core bytecode exceeds Java 8.

**Tech Stack:** Java 8 (core/platform) and Java 21 (adapter), Gradle 8.14 with Kotlin DSL, JUnit 5.11.4, Fabric Loader 0.19.3, Fabric API 0.141.6+1.21.11, Mojang mappings.

## Global Constraints

- **`platform` and `core` compile with `--release 8`.** No records, sealed types, pattern switches, `var`, or any API added after Java 8.
- **`platform` and `core` must never reference `net.minecraft`,** directly or transitively. This is checked by the build.
- **The adapter translates; it never decides.** Mapping one enum to another is translation. A conditional that changes *behaviour* is logic and belongs in core.
- **No mixins in this milestone.** Fabric API event subscriptions only.
- **SPI v0 is designed as if Forge 1.7.10 already existed.** No `BlockState`-shaped types, no registry-name assumptions, no modern-API idioms in `platform`.
- **Mojang mappings, not Yarn.** Yarn is end-of-life at 1.21.11 and will not exist for the next Minecraft version.
- Mod id: `continuo`. Root package: `dev.continuo`.
- Pinned versions: `minecraft_version=1.21.11`, `loader_version=0.19.3`, `fabric_version=0.141.6+1.21.11`.
- **Out of scope:** world reading, rotation, goals, pathfinding, config, chat commands, GUI, bridge, production jar packaging, the Forge 1.7.10 adapter.

**Spec:** [`docs/superpowers/specs/2026-08-01-a1-walking-skeleton-design.md`](../specs/2026-08-01-a1-walking-skeleton-design.md)

---

## File Structure

| Path | Responsibility |
|---|---|
| `settings.gradle.kts` | Module registration, plugin management repositories |
| `build.gradle.kts` | Root: `checkDependencyDirection` task and its allowlist |
| `gradle.properties` | Pinned version numbers, single source of truth |
| `buildSrc/build.gradle.kts` | `kotlin-dsl` plugin for the convention plugin |
| `buildSrc/src/main/kotlin/dev/continuo/build/ClassScan.kt` | Class-file byte scanning helpers, no third-party deps |
| `buildSrc/src/main/kotlin/continuo-pure-module.gradle.kts` | Convention plugin: `--release 8`, `checkCorePurity`, `checkCoreBytecode` |
| `platform/src/main/java/dev/continuo/platform/*.java` | SPI v0. One type per file |
| `core/src/main/java/dev/continuo/core/ContinuoCore.java` | The entire core: a 40-tick walk |
| `core/src/test/java/dev/continuo/core/fakes/*.java` | `FakeActuator`, `FakePlatformInfo`, `FakePlatformContext` |
| `core/src/test/java/dev/continuo/core/ContinuoCoreTest.java` | Headless behavioural tests |
| `adapters/adapter-fabric-1.21.11/build.gradle.kts` | The only file affected by the loader-plugin choice |
| `adapters/.../java/dev/continuo/adapter/fabric/ContinuoFabricMod.java` | Entrypoint: wiring and lifecycle |
| `adapters/.../java/dev/continuo/adapter/fabric/FabricActuator.java` | `Input` → `KeyMapping` translation |
| `adapters/.../java/dev/continuo/adapter/fabric/FabricPlatformInfo.java` | Version and loader reporting |
| `adapters/.../resources/fabric.mod.json` | Mod metadata, client entrypoint |
| `.github/workflows/ci.yml` | Build, test, invariant checks |
| `docs/toolchain-decision.md` | Which loader plugin was chosen, why, and what it costs M2 |
| `docs/smoke-checklist-a1.md` | Manual in-game verification steps |

---

## Task 1: Gradle multi-module skeleton

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `.gitignore`
- Create: `platform/build.gradle.kts`, `core/build.gradle.kts`
- Create: `platform/src/main/java/dev/continuo/platform/package-info.java`
- Create: `core/src/test/java/dev/continuo/core/BuildSanityTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: Gradle projects `:platform` and `:core`; `:core` depends on `:platform`. Both compile Java 8 and run JUnit 5.

- [ ] **Step 1: Generate the Gradle wrapper**

There is no Gradle on PATH on this machine, so bootstrap one first. Any installed JDK 17+
can run Gradle 8.14 for this purpose — the Java 21 requirement applies to the Minecraft
adapter, not to Gradle itself.

```bash
SCRATCH="$TMPDIR/gradle-bootstrap"
mkdir -p "$SCRATCH"
curl -fsSL -o "$SCRATCH/gradle.zip" https://services.gradle.org/distributions/gradle-8.14-bin.zip
unzip -q -o "$SCRATCH/gradle.zip" -d "$SCRATCH"
"$SCRATCH/gradle-8.14/bin/gradle" wrapper --gradle-version 8.14
```

If `$TMPDIR` is unset, use the session scratch directory instead. Verify the wrapper works
and then discard the bootstrap copy — it is not part of the repo:

```bash
./gradlew --version
```

Expected: `Gradle 8.14`.

The wrapper files (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`,
`gradle/wrapper/gradle-wrapper.properties`) must be committed. Note that
`gradle-wrapper.jar` is a binary and some `.gitignore` templates exclude `*.jar` — confirm
it is actually staged.

**Toolchain note.** The build requires a Java 21 toolchain. If Gradle reports
"No matching toolchain found for Java 21", the installed JDK 21 is not in a location Gradle
auto-detects. Point Gradle at it explicitly by adding to `gradle.properties`:

```properties
org.gradle.java.installations.paths=C:\\path\\to\\jdk-21
```

- [ ] **Step 2: Write `.gitignore`**

```gitignore
.gradle/
build/
out/
*.class
.idea/
*.iml
run/
```

- [ ] **Step 3: Write `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2G
org.gradle.parallel=true

group=dev.continuo
version=0.1.0-SNAPSHOT

minecraft_version=1.21.11
loader_version=0.19.3
fabric_version=0.141.6+1.21.11

junit_version=5.11.4
```

- [ ] **Step 4: Write `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.wagyourtail.xyz/releases") { name = "WagYourTail" }
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "continuo"

include("platform")
include("core")
```

`adapters/adapter-fabric-1.21.11` is added in Task 5, once the loader plugin is chosen.

- [ ] **Step 5: Write `platform/build.gradle.kts`**

```kotlin
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
```

The toolchain is Java 21 (what the build runs on) while `release = 8` controls the emitted
bytecode and the visible API. These are different things: the toolchain is the compiler,
`release` is the contract.

- [ ] **Step 6: Write `core/build.gradle.kts`**

```kotlin
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

dependencies {
    api(project(":platform"))

    val junitVersion = project.property("junit_version") as String
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
```

- [ ] **Step 7: Write `build.gradle.kts` (root)**

```kotlin
plugins {
    base
}
```

The dependency-direction check is added to this file in Task 2.

- [ ] **Step 8: Write `platform/src/main/java/dev/continuo/platform/package-info.java`**

```java
/**
 * Continuo platform SPI.
 *
 * <p>The contract between the pure core and any Minecraft version. Nothing in this package
 * may reference {@code net.minecraft}, and nothing may assume a Minecraft version newer
 * than 1.7.10. Every type added here is a future version-compatibility problem, so keep
 * the surface minimal.
 */
package dev.continuo.platform;
```

- [ ] **Step 9: Write a sanity test proving the test harness runs**

`core/src/test/java/dev/continuo/core/BuildSanityTest.java`:

```java
package dev.continuo.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuildSanityTest {

    @Test
    void junitRuns() {
        assertEquals(4, 2 + 2);
    }
}
```

- [ ] **Step 10: Run the build and verify it passes**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, `BuildSanityTest > junitRuns() PASSED`

- [ ] **Step 11: Verify the bytecode target is actually Java 8**

Run: `javap -verbose -cp core/build/classes/java/test dev.continuo.core.BuildSanityTest | grep major`
Expected: `major version: 52`

`BuildSanityTest` is used rather than `package-info` because `javac` emits no class file for
a `package-info.java` that carries only javadoc and no annotations — there would be nothing
to inspect. Test sources are covered by the same `options.release = 8` rule, so this is a
valid check of the setting.

If this prints 65 (Java 21), `options.release` is not being applied — fix before continuing.
Task 2 automates this check, but confirm it manually once first so you know what the
automated check is asserting.

- [ ] **Step 12: Commit**

```bash
git add -A
git commit -m "build: add Gradle multi-module skeleton with Java 8 core target"
```

---

## Task 2: Build-failing invariant checks

**Files:**
- Create: `buildSrc/build.gradle.kts`
- Create: `buildSrc/src/main/kotlin/dev/continuo/build/ClassScan.kt`
- Create: `buildSrc/src/main/kotlin/continuo-pure-module.gradle.kts`
- Modify: `platform/build.gradle.kts`, `core/build.gradle.kts` (apply the convention plugin)
- Modify: `build.gradle.kts` (add `checkDependencyDirection`)

**Interfaces:**
- Consumes: Gradle projects `:platform`, `:core` from Task 1
- Produces: convention plugin id `continuo-pure-module`, which applies `java-library`, sets
  `release = 8`, and registers tasks `checkCorePurity` and `checkCoreBytecode` wired into
  `check`. Root task `checkDependencyDirection`, also wired into `check`.

- [ ] **Step 1: Write `buildSrc/build.gradle.kts`**

```kotlin
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}
```

No third-party dependencies. The checks read class files as raw bytes, which is enough for
both purity and bytecode-version scanning and keeps the build free of an ASM version to
maintain.

- [ ] **Step 2: Write the class-scanning helpers**

`buildSrc/src/main/kotlin/dev/continuo/build/ClassScan.kt`:

```kotlin
package dev.continuo.build

import java.io.File

object ClassScan {

    /** Major class-file version. 52 = Java 8, 65 = Java 21. */
    fun majorVersion(classFile: File): Int {
        val bytes = classFile.readBytes()
        require(bytes.size >= 8) { "Not a class file: $classFile" }
        return ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
    }

    /** True if the raw bytes of the class file contain [needle] as ASCII. */
    fun containsAscii(classFile: File, needle: String): Boolean {
        val haystack = classFile.readBytes()
        val pattern = needle.toByteArray(Charsets.US_ASCII)
        if (pattern.isEmpty() || haystack.size < pattern.size) return false
        outer@ for (start in 0..haystack.size - pattern.size) {
            for (i in pattern.indices) {
                if (haystack[start + i] != pattern[i]) continue@outer
            }
            return true
        }
        return false
    }

    /** All `.class` files under [dir], or empty if it does not exist. */
    fun classFiles(dir: File): List<File> =
        if (!dir.exists()) emptyList()
        else dir.walkTopDown().filter { it.isFile && it.extension == "class" }.toList()
}
```

Scanning raw bytes rather than parsing the constant pool means a *string literal* containing
`net/minecraft` also trips the check. That is intentional — a core class that mentions
Minecraft even in a string is worth a human look.

- [ ] **Step 3: Write the convention plugin**

`buildSrc/src/main/kotlin/continuo-pure-module.gradle.kts`:

```kotlin
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
```

- [ ] **Step 4: Apply the convention plugin to `platform` and `core`**

Replace the whole of `platform/build.gradle.kts` with:

```kotlin
plugins {
    id("continuo-pure-module")
}
```

Replace the whole of `core/build.gradle.kts` with:

```kotlin
plugins {
    id("continuo-pure-module")
}

dependencies {
    api(project(":platform"))

    val junitVersion = project.property("junit_version") as String
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
```

- [ ] **Step 5: Add the dependency-direction check to the root build**

Replace the whole of `build.gradle.kts` with:

```kotlin
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
```

`ProjectDependency.path` is used rather than the deprecated `dependencyProject`, which
Gradle 9 removes.

- [ ] **Step 6: Run the build and verify all three checks pass**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. Confirm `checkCorePurity`, `checkCoreBytecode`, and
`checkDependencyDirection` all appear in the output as executed, not skipped.

Run: `./gradlew checkCorePurity checkCoreBytecode checkDependencyDirection --info | grep -i "Task :"`
Expected: each task listed and executed.

- [ ] **Step 7: Create a scratch class so the checks have something to scan**

Both negative tests below need at least one compiled class in `core`'s **main** source set.
At this point `core/src/main/java` is empty and `platform` contains only a `package-info.java`
that emits no class file, so a check with nothing to scan would pass vacuously and the
negative tests would prove nothing.

Create `core/src/main/java/dev/continuo/core/Scratch.java`:

```java
package dev.continuo.core;

/** Temporary. Deleted at the end of Task 2. Exists so the invariant checks have a target. */
final class Scratch {

    static final String NOTE = "placeholder";

    private Scratch() {
    }
}
```

Run: `./gradlew :core:build`
Expected: BUILD SUCCESSFUL, and `core/build/classes/java/main/dev/continuo/core/Scratch.class` exists.

- [ ] **Step 8: Verify `checkCorePurity` fails when violated**

Temporarily change the `NOTE` constant in `Scratch.java` to:

```java
    static final String NOTE = "net/minecraft/world/level/Level";
```

Run: `./gradlew :core:build`
Expected: FAIL with "Core purity violated in :core" naming `Scratch.class`.

Restore `NOTE` to `"placeholder"` and re-run:

Run: `./gradlew :core:build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Verify `checkCoreBytecode` fails when violated**

Temporarily append to `core/build.gradle.kts`:

```kotlin
tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}
```

Run: `./gradlew :core:clean :core:build`
Expected: FAIL with "Java 8 bytecode required in :core" and "major 65", naming `Scratch.class`.

Remove those three lines and re-run:

Run: `./gradlew :core:clean :core:build`
Expected: BUILD SUCCESSFUL

This check has no real consumer until M2, so this is the only moment it can be proven to
work before the project starts trusting it. A check that silently passes because its scanner
never found a file is exactly the failure mode this step exists to rule out.

- [ ] **Step 10: Delete the scratch class**

```bash
rm core/src/main/java/dev/continuo/core/Scratch.java
```

Run: `./gradlew :core:build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 11: Verify `checkDependencyDirection` fails when violated**

Temporarily add to `platform/build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":core"))
}
```

Run: `./gradlew checkDependencyDirection`
Expected: FAIL with ":platform must not depend on :core".

Then remove the block and re-run to confirm it passes.

- [ ] **Step 12: Run the full build once more to confirm a clean state**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 13: Commit**

```bash
git add -A
git commit -m "build: add core purity, bytecode, and dependency-direction checks"
```

---

## Task 3: SPI v0

**Files:**
- Create: `platform/src/main/java/dev/continuo/platform/IActuator.java`
- Create: `platform/src/main/java/dev/continuo/platform/Input.java`
- Create: `platform/src/main/java/dev/continuo/platform/IPlatformInfo.java`
- Create: `platform/src/main/java/dev/continuo/platform/Loader.java`
- Create: `platform/src/main/java/dev/continuo/platform/IGameEvents.java`
- Create: `platform/src/main/java/dev/continuo/platform/TickPhase.java`
- Create: `platform/src/main/java/dev/continuo/platform/IPlatformContext.java`

**Interfaces:**
- Consumes: the `continuo-pure-module` convention plugin from Task 2
- Produces: `IActuator.setInput(Input, boolean)`; `enum Input { FORWARD, BACK, LEFT, RIGHT, JUMP, SNEAK, SPRINT }`; `IPlatformInfo.gameVersion(): String` and `IPlatformInfo.loader(): Loader`; `enum Loader { FABRIC, FORGE, NEOFORGE }`; `IGameEvents.onClientTick(TickPhase)`; `enum TickPhase { PRE, POST }`; `IPlatformContext.actuator(): IActuator` and `IPlatformContext.info(): IPlatformInfo`.

This task has no tests of its own — interfaces have no behaviour. Task 4 exercises every
type here. The reviewer's gate on this task is the *shape* of the SPI, which is the most
consequential decision in the milestone.

- [ ] **Step 1: Write `IActuator.java`**

```java
package dev.continuo.platform;

/**
 * The single channel through which the core influences the game.
 *
 * <p>Implemented by adapters. Every effect the core has on the world passes through here,
 * which is what makes the core testable and what will later make input humanization a
 * single seam rather than a cross-cutting concern.
 */
public interface IActuator {

    /**
     * Sets a movement input to pressed or released. Idempotent: setting an already-pressed
     * input to pressed is a no-op from the game's perspective.
     */
    void setInput(Input input, boolean pressed);
}
```

- [ ] **Step 2: Write `Input.java`**

```java
package dev.continuo.platform;

/**
 * Abstract movement inputs, named for intent rather than for any keyboard layout or
 * Minecraft version's internal naming.
 */
public enum Input {
    FORWARD,
    BACK,
    LEFT,
    RIGHT,
    JUMP,
    SNEAK,
    SPRINT
}
```

- [ ] **Step 3: Write `IPlatformInfo.java`**

```java
package dev.continuo.platform;

/**
 * Metadata about the platform the core is running on.
 *
 * <p>Has no consumer in A1. It is present because it costs nothing, it establishes the
 * direction capability negotiation will need later, and it gives the smoke check a way to
 * prove which adapter is actually loaded.
 */
public interface IPlatformInfo {

    /** Human-readable game version, for example {@code "1.21.11"}. */
    String gameVersion();

    /** The mod loader hosting this adapter. */
    Loader loader();
}
```

- [ ] **Step 4: Write `Loader.java`**

```java
package dev.continuo.platform;

public enum Loader {
    FABRIC,
    FORGE,
    NEOFORGE
}
```

- [ ] **Step 5: Write `IGameEvents.java`**

```java
package dev.continuo.platform;

/**
 * Events flowing from the game into the core.
 *
 * <p>Note the direction: the core implements this and the adapter calls it. The adapter
 * holds the core, never the reverse.
 */
public interface IGameEvents {

    /** Called once per client tick, per phase. */
    void onClientTick(TickPhase phase);
}
```

- [ ] **Step 6: Write `TickPhase.java`**

```java
package dev.continuo.platform;

/**
 * Which side of the game's own tick processing a callback is running on.
 *
 * <p>A1 only uses {@link #PRE}. {@link #POST} exists so that the {@code onClientTick}
 * signature does not have to change when both phases are needed.
 */
public enum TickPhase {
    PRE,
    POST
}
```

- [ ] **Step 7: Write `IPlatformContext.java`**

```java
package dev.continuo.platform;

/**
 * Everything the adapter hands the core at startup.
 *
 * <p>Bundled into one type so that adding a capability later changes one signature rather
 * than every call site.
 */
public interface IPlatformContext {

    IActuator actuator();

    IPlatformInfo info();
}
```

- [ ] **Step 8: Build and verify the SPI compiles clean under Java 8 rules**

Run: `./gradlew :platform:build`
Expected: BUILD SUCCESSFUL, with `checkCorePurity` and `checkCoreBytecode` passing.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat(platform): add SPI v0 - actuator, platform info, tick events"
```

---

## Task 4: ContinuoCore, test-first

**Files:**
- Create: `core/src/test/java/dev/continuo/core/fakes/FakeActuator.java`
- Create: `core/src/test/java/dev/continuo/core/fakes/FakePlatformInfo.java`
- Create: `core/src/test/java/dev/continuo/core/fakes/FakePlatformContext.java`
- Create: `core/src/test/java/dev/continuo/core/ContinuoCoreTest.java`
- Create: `core/src/main/java/dev/continuo/core/ContinuoCore.java`
- Delete: `core/src/test/java/dev/continuo/core/BuildSanityTest.java`

**Interfaces:**
- Consumes: all SPI types from Task 3
- Produces: `ContinuoCore` with `start(IPlatformContext)`, `stop()`, `requestWalk()`, and
  `onClientTick(TickPhase)` from `IGameEvents`. Constant `WALK_TICKS = 40`.

**Tick numbering contract:** ticks are counted from the first `onClientTick` *after*
`requestWalk()`, numbered from 1. `FORWARD` is pressed while handling tick 1, held through
tick 40, and released while handling tick 41. The walk spans 40 ticks of held input.

- [ ] **Step 1: Write the test doubles**

`core/src/test/java/dev/continuo/core/fakes/FakeActuator.java`:

```java
package dev.continuo.core.fakes;

import dev.continuo.platform.IActuator;
import dev.continuo.platform.Input;

import java.util.ArrayList;
import java.util.List;

/** Records every actuator call so tests can assert on exact call sequences. */
public final class FakeActuator implements IActuator {

    public static final class Call {
        public final Input input;
        public final boolean pressed;

        Call(Input input, boolean pressed) {
            this.input = input;
            this.pressed = pressed;
        }

        @Override
        public String toString() {
            return input + "=" + pressed;
        }
    }

    private final List<Call> calls = new ArrayList<Call>();

    @Override
    public void setInput(Input input, boolean pressed) {
        calls.add(new Call(input, pressed));
    }

    public List<Call> calls() {
        return calls;
    }

    public int callCount() {
        return calls.size();
    }

    public void clear() {
        calls.clear();
    }
}
```

`core/src/test/java/dev/continuo/core/fakes/FakePlatformInfo.java`:

```java
package dev.continuo.core.fakes;

import dev.continuo.platform.IPlatformInfo;
import dev.continuo.platform.Loader;

public final class FakePlatformInfo implements IPlatformInfo {

    private final String gameVersion;
    private final Loader loader;

    public FakePlatformInfo(String gameVersion, Loader loader) {
        this.gameVersion = gameVersion;
        this.loader = loader;
    }

    @Override
    public String gameVersion() {
        return gameVersion;
    }

    @Override
    public Loader loader() {
        return loader;
    }
}
```

`core/src/test/java/dev/continuo/core/fakes/FakePlatformContext.java`:

```java
package dev.continuo.core.fakes;

import dev.continuo.platform.IActuator;
import dev.continuo.platform.IPlatformContext;
import dev.continuo.platform.IPlatformInfo;
import dev.continuo.platform.Loader;

public final class FakePlatformContext implements IPlatformContext {

    private final FakeActuator actuator = new FakeActuator();
    private final IPlatformInfo info = new FakePlatformInfo("0.0-test", Loader.FABRIC);

    @Override
    public IActuator actuator() {
        return actuator;
    }

    @Override
    public IPlatformInfo info() {
        return info;
    }

    public FakeActuator fakeActuator() {
        return actuator;
    }
}
```

- [ ] **Step 2: Write the failing tests**

`core/src/test/java/dev/continuo/core/ContinuoCoreTest.java`:

```java
package dev.continuo.core;

import dev.continuo.core.fakes.FakeActuator;
import dev.continuo.core.fakes.FakePlatformContext;
import dev.continuo.platform.Input;
import dev.continuo.platform.TickPhase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContinuoCoreTest {

    private FakePlatformContext ctx;
    private FakeActuator actuator;
    private ContinuoCore core;

    @BeforeEach
    void setUp() {
        ctx = new FakePlatformContext();
        actuator = ctx.fakeActuator();
        core = new ContinuoCore();
        core.start(ctx);
    }

    private void tick(int times) {
        for (int i = 0; i < times; i++) {
            core.onClientTick(TickPhase.PRE);
        }
    }

    @Test
    void pressesForwardOnFirstTickAfterRequest() {
        core.requestWalk();
        tick(1);

        assertEquals(1, actuator.callCount());
        assertEquals(Input.FORWARD, actuator.calls().get(0).input);
        assertTrue(actuator.calls().get(0).pressed);
    }

    @Test
    void holdsForwardForFortyTicksThenReleasesOnTickFortyOne() {
        core.requestWalk();
        tick(40);

        assertEquals(1, actuator.callCount(), "no release before tick 41");

        tick(1);

        assertEquals(2, actuator.callCount());
        assertEquals(Input.FORWARD, actuator.calls().get(1).input);
        assertEquals(false, actuator.calls().get(1).pressed);
    }

    @Test
    void doesNothingAfterTheWalkCompletes() {
        core.requestWalk();
        tick(41);
        actuator.clear();

        tick(4);

        assertEquals(0, actuator.callCount());
    }

    @Test
    void neverTouchesAnyInputOtherThanForward() {
        core.requestWalk();
        tick(45);

        assertTrue(actuator.callCount() > 0, "walk must produce actuator calls");
        for (FakeActuator.Call call : actuator.calls()) {
            assertEquals(Input.FORWARD, call.input);
        }
    }

    @Test
    void ignoresRequestWalkWhileAlreadyWalking() {
        core.requestWalk();
        tick(10);
        actuator.clear();

        core.requestWalk();
        tick(10);

        assertEquals(0, actuator.callCount(), "re-triggering mid-walk must be ignored");
    }

    @Test
    void doesNothingBeforeAnyWalkIsRequested() {
        tick(20);

        assertEquals(0, actuator.callCount());
    }

    @Test
    void ignoresPostPhaseTicks() {
        core.requestWalk();
        core.onClientTick(TickPhase.POST);

        assertEquals(0, actuator.callCount());
    }

    @Test
    void stopReleasesForwardMidWalk() {
        core.requestWalk();
        tick(20);
        actuator.clear();

        core.stop();

        assertEquals(1, actuator.callCount());
        assertEquals(Input.FORWARD, actuator.calls().get(0).input);
        assertEquals(false, actuator.calls().get(0).pressed);
    }

    @Test
    void stopWhenNotWalkingReleasesNothing() {
        core.stop();

        assertEquals(0, actuator.callCount());
    }

    @Test
    void canWalkAgainAfterStop() {
        core.requestWalk();
        tick(20);
        core.stop();
        actuator.clear();

        core.requestWalk();
        tick(1);

        assertEquals(1, actuator.callCount());
        assertTrue(actuator.calls().get(0).pressed);
    }

    @Test
    void requestWalkBeforeStartFails() {
        ContinuoCore unstarted = new ContinuoCore();

        assertThrows(IllegalStateException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                unstarted.requestWalk();
            }
        });
    }

    @Test
    void stopBeforeStartFails() {
        ContinuoCore unstarted = new ContinuoCore();

        assertThrows(IllegalStateException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                unstarted.stop();
            }
        });
    }
}
```

The anonymous `Executable` rather than a lambda is deliberate: the test source compiles at
`release = 8`, where lambdas are legal, but an anonymous class keeps the style consistent
with the Java 8 main sources and avoids any question about it.

- [ ] **Step 3: Delete the sanity test**

```bash
rm core/src/test/java/dev/continuo/core/BuildSanityTest.java
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `./gradlew :core:test`
Expected: FAIL — compilation error, `cannot find symbol: class ContinuoCore`

- [ ] **Step 5: Write the minimal implementation**

`core/src/main/java/dev/continuo/core/ContinuoCore.java`:

```java
package dev.continuo.core;

import dev.continuo.platform.IGameEvents;
import dev.continuo.platform.IPlatformContext;
import dev.continuo.platform.Input;
import dev.continuo.platform.TickPhase;

/**
 * The entire core, for now: on request, hold FORWARD for {@link #WALK_TICKS} ticks.
 *
 * <p>Deliberately has no static state and no knowledge of its owner. The adapter
 * constructs it and holds it, which is exactly why this class can be tested with no
 * Minecraft on the classpath.
 */
public final class ContinuoCore implements IGameEvents {

    /** Roughly 8.6 blocks at vanilla walking speed. */
    public static final int WALK_TICKS = 40;

    private IPlatformContext context;
    private boolean walking;
    private int tick;

    /** Called once by the adapter, before any other method. */
    public void start(IPlatformContext context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context;
    }

    /**
     * Releases any held input and resets state.
     *
     * <p>The adapter must call this on world unload and on shutdown. Without it, a
     * disconnect mid-walk leaves the client holding a movement key.
     */
    public void stop() {
        if (context == null) {
            throw new IllegalStateException("start(IPlatformContext) must be called first");
        }
        if (walking) {
            context.actuator().setInput(Input.FORWARD, false);
        }
        walking = false;
        tick = 0;
    }

    /** Begins a walk. Ignored if a walk is already in progress. */
    public void requestWalk() {
        if (context == null) {
            throw new IllegalStateException("start(IPlatformContext) must be called first");
        }
        if (walking) {
            return;
        }
        walking = true;
        tick = 0;
    }

    @Override
    public void onClientTick(TickPhase phase) {
        if (phase != TickPhase.PRE || !walking) {
            return;
        }
        tick++;
        if (tick == 1) {
            context.actuator().setInput(Input.FORWARD, true);
        } else if (tick == WALK_TICKS + 1) {
            context.actuator().setInput(Input.FORWARD, false);
            walking = false;
            tick = 0;
        }
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :core:test`
Expected: PASS, 12 tests

- [ ] **Step 7: Run the full build including invariant checks**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL with `checkCorePurity`, `checkCoreBytecode`, and
`checkDependencyDirection` all passing.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(core): add ContinuoCore with headless 40-tick walk and tests"
```

---

## Task 5: Loader toolchain spike and adapter module

**Files:**
- Create: `adapters/adapter-fabric-1.21.11/build.gradle.kts`
- Modify: `settings.gradle.kts` (register the adapter module)
- Create: `docs/toolchain-decision.md`

**Interfaces:**
- Consumes: Gradle projects `:platform` and `:core`
- Produces: Gradle project `:adapters:adapter-fabric-1.21.11` with a working `runClient`
  task, Minecraft 1.21.11 on the compile classpath under Mojang mappings, and
  `:platform` + `:core` as project dependencies.

**Why this is a spike, not a straightforward config task:** the roadmap selects unimined so
that one plugin serves both Fabric 1.21.11 (M1) and Forge 1.7.10 (M2). But unimined's most
recent published release is **1.4.1, from June 2024** — eighteen months before Minecraft
1.21.11 shipped. There is no release that claims 1.21.11 support. This task establishes
whether the roadmap's primary choice is viable, and falls back deliberately if it is not.

Nothing in Tasks 1–4 depends on the outcome, because `platform` and `core` are plain
`java-library` modules that no loader plugin touches. That containment is the point.

- [ ] **Step 1: Check unimined's current state before writing any config**

Check, in order:
1. <https://github.com/unimined/unimined/releases> — is there a release newer than 1.4.1?
2. <https://maven.wagyourtail.xyz/releases/xyz/wagyourtail/unimined/xyz.wagyourtail.unimined.gradle.plugin/> — newest version directory.
3. The repo's `README` and recent issues for any statement about 1.21.x support.

Record the newest available version. If a release from 2025 or later exists, attempt Path A.
If 1.4.1 is still the newest, go directly to Path B — an eighteen-month-stale plugin against
a version released after it is not worth a day of debugging.

- [ ] **Step 2 (Path A): Try unimined**

`adapters/adapter-fabric-1.21.11/build.gradle.kts`:

```kotlin
plugins {
    java
    id("xyz.wagyourtail.unimined") version "<version found in Step 1>"
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

val minecraftVersion = project.property("minecraft_version") as String
val loaderVersion = project.property("loader_version") as String
val fabricVersion = project.property("fabric_version") as String

unimined.minecraft {
    version(minecraftVersion)
    mappings { mojmap() }
    fabric { loader(loaderVersion) }
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":core"))
    "modImplementation"("net.fabricmc.fabric-api:fabric-api:$fabricVersion")
}
```

Property names are read with `project.property("...")` rather than the `by project`
delegate. The delegate matches the property name *exactly* — it does not convert
`minecraft_version` to `minecraftVersion` — and silently fails at configuration time.
This was confirmed the hard way in Task 1.

Run: `./gradlew :adapters:adapter-fabric-1.21.11:build`

**Timebox this to two hours.** If unimined cannot resolve 1.21.11, or fails on Mojang
mappings, go to Path B. Do not debug further — M2 will revisit the toolchain anyway, and
Loom is a fully supported fallback.

- [ ] **Step 3 (Path B): Use Fabric Loom**

Find the current Loom version from <https://fabricmc.net/develop> or from the
`gradle.properties` of the matching branch of
<https://github.com/FabricMC/fabric-example-mod>. Do not guess it; Loom releases frequently
and a wrong version fails at configuration time.

Add to `gradle.properties`:

```properties
loom_version=<version found above>
```

`adapters/adapter-fabric-1.21.11/build.gradle.kts`:

```kotlin
plugins {
    java
    id("fabric-loom") version "${property("loom_version")}"
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")

    implementation(project(":platform"))
    implementation(project(":core"))
}
```

If Loom rejects the Gradle version, bump the wrapper:
`./gradlew wrapper --gradle-version <required>`. Record the change in the decision doc.

**Mojang mappings, not Yarn.** Yarn is end-of-life at 1.21.11 and will not exist for the
next Minecraft version. Every Minecraft type named in Task 6 uses Mojang names.

**Production jar packaging is out of scope for A1.** `runClient` puts project dependencies
on the classpath automatically, which is all the smoke test needs. Bundling `core` and
`platform` into a distributable jar (jar-in-jar or shading) is deferred.

- [ ] **Step 4: Register the module**

Add to `settings.gradle.kts`, after `include("core")`:

```kotlin
include("adapters:adapter-fabric-1.21.11")
```

- [ ] **Step 5: Verify the dependency-direction check covers the new module**

Run: `./gradlew checkDependencyDirection`
Expected: PASS. The allowlist entry for `:adapters:adapter-fabric-1.21.11` was written in
Task 2 Step 5, so this should already be satisfied. If it reports "not listed in
allowedProjectDependencies", the module path does not match the allowlist key — fix the key.

- [ ] **Step 6: Verify Minecraft has not leaked into the core**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL. `checkCorePurity` must still pass on `:platform` and `:core`
now that Minecraft is present elsewhere in the build. If it now fails, a Minecraft
dependency has leaked through a project dependency — that is exactly the failure this check
exists to catch, and it must be fixed rather than suppressed.

- [ ] **Step 7: Confirm the dev client launches**

Run: `./gradlew :adapters:adapter-fabric-1.21.11:runClient`
Expected: a Minecraft 1.21.11 client window opens and reaches the main menu. No mod
behaviour yet — this only proves the toolchain works. Close the client.

- [ ] **Step 8: Write the decision doc**

`docs/toolchain-decision.md`:

```markdown
# Loader toolchain decision (M1)

**Chosen:** <unimined X.Y.Z | Fabric Loom X.Y>
**Date:** <date>

## What was tried

<For Path A: the unimined version attempted and what happened.>
<For Path B: why Path A was skipped or abandoned.>

## Consequence for M2 (Forge 1.7.10)

- If unimined: M2 uses the same plugin, which was the roadmap's reason for choosing it.
- If Loom: M2 needs a second plugin for 1.7.10 — RetroFuturaGradle is the maintained
  option. The roadmap's "one plugin for both" premise does not hold, and M2 must budget
  for standing up a second, unrelated toolchain.

## Gradle version

<Version, and whether it was bumped from 8.14 to satisfy the plugin.>
```

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "build: add Fabric 1.21.11 adapter module and record toolchain decision"
```

---

## Task 6: Fabric adapter implementation

**Files:**
- Create: `adapters/adapter-fabric-1.21.11/src/main/java/dev/continuo/adapter/fabric/FabricActuator.java`
- Create: `adapters/adapter-fabric-1.21.11/src/main/java/dev/continuo/adapter/fabric/FabricPlatformInfo.java`
- Create: `adapters/adapter-fabric-1.21.11/src/main/java/dev/continuo/adapter/fabric/FabricPlatformContext.java`
- Create: `adapters/adapter-fabric-1.21.11/src/main/java/dev/continuo/adapter/fabric/ContinuoFabricMod.java`
- Create: `adapters/adapter-fabric-1.21.11/src/main/resources/fabric.mod.json`

**Interfaces:**
- Consumes: `ContinuoCore` (`start`, `stop`, `requestWalk`, `onClientTick`) from Task 4; all SPI types from Task 3
- Produces: a loadable Fabric client mod. No later task consumes its types.

All Minecraft types below use **Mojang mappings**: `Minecraft` (not `MinecraftClient`),
`KeyMapping` (not `KeyBinding`), `InputConstants` (not `InputUtil`), `Options.keyUp` (not
`options.forwardKey`).

- [ ] **Step 1: Write `FabricActuator.java`**

```java
package dev.continuo.adapter.fabric;

import dev.continuo.platform.IActuator;
import dev.continuo.platform.Input;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Translates abstract {@link Input} values into Minecraft key mappings.
 *
 * <p>Pure translation: an enum maps to an enum. No decision is made here. If this class
 * ever grows a conditional that changes behaviour rather than resolving a name, that logic
 * belongs in the core.
 */
final class FabricActuator implements IActuator {

    private final Minecraft minecraft;

    FabricActuator(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    @Override
    public void setInput(Input input, boolean pressed) {
        KeyMapping mapping = mappingFor(input);
        if (mapping != null) {
            mapping.setDown(pressed);
        }
    }

    private KeyMapping mappingFor(Input input) {
        switch (input) {
            case FORWARD: return minecraft.options.keyUp;
            case BACK:    return minecraft.options.keyDown;
            case LEFT:    return minecraft.options.keyLeft;
            case RIGHT:   return minecraft.options.keyRight;
            case JUMP:    return minecraft.options.keyJump;
            case SNEAK:   return minecraft.options.keyShift;
            case SPRINT:  return minecraft.options.keySprint;
            default:      return null;
        }
    }
}
```

If any field name is rejected by the compiler, the Mojang mapping for that option has
changed. Find the correct name in `net.minecraft.client.Options` — do not work around it by
moving logic into this class.

- [ ] **Step 2: Write `FabricPlatformInfo.java`**

```java
package dev.continuo.adapter.fabric;

import dev.continuo.platform.IPlatformInfo;
import dev.continuo.platform.Loader;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Reports the game version via the loader rather than via a Minecraft class, because the
 * loader API is stable across versions and {@code SharedConstants} is not.
 */
final class FabricPlatformInfo implements IPlatformInfo {

    @Override
    public String gameVersion() {
        return FabricLoader.getInstance()
            .getModContainer("minecraft")
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
    }

    @Override
    public Loader loader() {
        return Loader.FABRIC;
    }
}
```

- [ ] **Step 3: Write `FabricPlatformContext.java`**

```java
package dev.continuo.adapter.fabric;

import dev.continuo.platform.IActuator;
import dev.continuo.platform.IPlatformContext;
import dev.continuo.platform.IPlatformInfo;
import net.minecraft.client.Minecraft;

final class FabricPlatformContext implements IPlatformContext {

    private final IActuator actuator;
    private final IPlatformInfo info = new FabricPlatformInfo();

    FabricPlatformContext(Minecraft minecraft) {
        this.actuator = new FabricActuator(minecraft);
    }

    @Override
    public IActuator actuator() {
        return actuator;
    }

    @Override
    public IPlatformInfo info() {
        return info;
    }
}
```

- [ ] **Step 4: Write `ContinuoFabricMod.java`**

```java
package dev.continuo.adapter.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import dev.continuo.core.ContinuoCore;
import dev.continuo.platform.TickPhase;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wiring only. Translates a keypress into a core method call and a client tick into a core
 * tick. Every behavioural decision lives in {@link ContinuoCore}.
 */
public final class ContinuoFabricMod implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("continuo");

    private final ContinuoCore core = new ContinuoCore();
    private KeyMapping walkKey;

    @Override
    public void onInitializeClient() {
        walkKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.continuo.walk",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "category.continuo"
        ));

        FabricPlatformContext context = new FabricPlatformContext(Minecraft.getInstance());
        core.start(context);

        LOGGER.info(
            "Continuo core started on {} / {}",
            context.info().gameVersion(),
            context.info().loader()
        );

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            while (walkKey.consumeClick()) {
                LOGGER.info("Continuo walk requested");
                core.requestWalk();
            }
            core.onClientTick(TickPhase.PRE);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            LOGGER.info("Continuo stopping: disconnected");
            core.stop();
        });
    }
}
```

Three details that matter:

`START_CLIENT_TICK` rather than `END_CLIENT_TICK`, because the core is told this is
`TickPhase.PRE` and the two must agree. Setting inputs before the game processes the tick
is also what makes them take effect on that tick rather than the next.

`consumeClick()` in a `while` loop is the Fabric idiom for draining queued presses; a plain
`if` drops presses that arrive more than once between ticks.

`ClientPlayConnectionEvents.DISCONNECT` is what prevents a disconnect mid-walk from leaving
the client holding W. This is the one adapter responsibility that is not pure translation,
and it is a lifecycle guarantee rather than a behavioural decision.

- [ ] **Step 5: Write `fabric.mod.json`**

`adapters/adapter-fabric-1.21.11/src/main/resources/fabric.mod.json`:

```json
{
  "schemaVersion": 1,
  "id": "continuo",
  "version": "${version}",
  "name": "Continuo",
  "description": "Version-independent Minecraft automation. Walking skeleton.",
  "license": "MIT",
  "environment": "client",
  "entrypoints": {
    "client": [
      "dev.continuo.adapter.fabric.ContinuoFabricMod"
    ]
  },
  "depends": {
    "fabricloader": ">=0.19.3",
    "minecraft": "~1.21.11",
    "java": ">=21",
    "fabric-api": "*"
  }
}
```

For `${version}` to be substituted, the adapter build file needs resource processing. Add to
`adapters/adapter-fabric-1.21.11/build.gradle.kts`:

```kotlin
tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
```

- [ ] **Step 6: Build and verify everything compiles**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL. `checkCorePurity` still passes on `:platform` and `:core`
despite `net.minecraft` now being on the adapter's classpath — that contrast is the whole
architecture in one build output.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(adapter-fabric): add keybind, tick hook, and input translation"
```

---

## Task 7: CI, smoke verification, and closeout

**Files:**
- Create: `.github/workflows/ci.yml`
- Create: `docs/smoke-checklist-a1.md`

**Interfaces:**
- Consumes: everything from Tasks 1–6
- Produces: nothing consumed by later tasks. This closes A1.

- [ ] **Step 1: Write the CI workflow**

`.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
    branches: ["**"]
  pull_request:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: "21"
          distribution: "temurin"

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Build, test, and check invariants
        run: ./gradlew build --stacktrace

      - name: Verify core bytecode is Java 8
        run: ./gradlew checkCoreBytecode checkCorePurity checkDependencyDirection
```

The final step is redundant with `build` — it is there so that a failure names the specific
invariant that broke, rather than being buried in a long build log.

- [ ] **Step 2: Verify CI passes**

Push the branch and confirm the workflow goes green. If GitHub Actions is not yet available
for this repo, run the equivalent locally and note it:

```bash
./gradlew clean build --stacktrace
```

- [ ] **Step 3: Write the smoke checklist**

`docs/smoke-checklist-a1.md`:

```markdown
# A1 manual smoke checklist — Fabric 1.21.11

Run: `./gradlew :adapters:adapter-fabric-1.21.11:runClient`

1. **Startup log.** Before reaching the main menu, the log contains
   `Continuo core started on 1.21.11 / FABRIC`.
   Failure here means `IPlatformInfo` is not wired, or the mod did not load at all.

2. **World.** Create a new Superflat world in Creative, then switch to Survival
   (`/gamemode survival`) so walking speed is vanilla and flight is off.

3. **Baseline.** Press F3. Record the XYZ coordinates and the facing axis.

4. **Walk.** Press `K`. Expected: the player walks forward without further input and stops
   on its own. The log contains `Continuo walk requested`.

5. **Distance.** Read F3 again. Displacement along the facing axis should be **8–9 blocks**.
   40 ticks at vanilla walking speed is about 8.6 blocks.
   - Roughly double or half means the tick hook is firing at the wrong rate.
   - Zero means the actuator is not reaching the key mapping.
   - Never stopping means the tick counter is not advancing.

6. **Repeat.** Press `K` again. The walk repeats identically. This confirms state resets.

7. **Re-trigger mid-walk.** Press `K`, then press `K` again while still moving. The bot must
   walk the same 8–9 blocks total, not further. Re-triggering is specified as ignored.

8. **Disconnect mid-walk.** Press `K`, and while the bot is still moving, open the menu and
   quit to title. Rejoin the world. Confirm the player is **not** drifting forward and that
   the W key is not stuck. This verifies `core.stop()` on disconnect.

Record the result of each step. Any failure blocks A1 sign-off.
```

- [ ] **Step 4: Run the smoke checklist**

Run: `./gradlew :adapters:adapter-fabric-1.21.11:runClient`

Work through every step in `docs/smoke-checklist-a1.md` and record the results. Step 8 is
the one most likely to reveal a real defect — verify it properly rather than assuming it.

- [ ] **Step 5: Verify the A1 done criteria**

Confirm each, with evidence, and do not mark any as done without running it:

1. `./gradlew clean build` is green — core tests pass, all three invariant checks pass
2. Each invariant check was verified to fail when deliberately violated (Task 2, Steps 7–9)
3. The manual smoke checklist passes end to end
4. Disconnecting mid-walk leaves no input stuck (smoke step 8)
5. CI runs criterion 1 on every push

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "ci: add build workflow and A1 smoke checklist"
```

---

## What A1 deliberately does not do

Recorded so a reviewer does not mistake these for oversights: no world reading, no rotation
control, no goals, no pathfinding, no config, no chat commands, no GUI, no bridge, no
mixins, no production jar packaging, and no Forge 1.7.10 adapter.

The walking behaviour itself is throwaway. What survives A1 is the SPI shape, the build
invariants, and the proof that core logic is testable without Minecraft.

## Next milestone

M2 — `adapter-forge-1.7.10` against this same SPI, and the SPI v1 revision that follows from
what 1.7.10 teaches. If Task 5 landed on Loom rather than unimined, M2 must also budget for
standing up RetroFuturaGradle as a second, unrelated toolchain.
