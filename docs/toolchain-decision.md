# Loader toolchain decision (M1)

**Chosen:** Fabric Loom 1.17.17
**Date:** 2026-08-01

## What was tried

### Step 1: unimined's current state (checked before writing any config)

The roadmap's premise (per the task brief) was: "unimined's most recent published release is
1.4.1, from June 2024, eighteen months before Minecraft 1.21.11 shipped." This was checked
directly rather than trusted, per the brief's instruction, and **the date in that premise is
wrong** — but the conclusion it leads to (fall back to Loom) turned out to be right anyway,
for a stronger reason than staleness.

Evidence gathered:

1. **Releases page** — <https://github.com/unimined/unimined/releases>. Newest release is
   `1.4.1`. No release newer than 1.4.1 exists.
2. **GitHub Releases API** — `https://api.github.com/repos/unimined/unimined/releases/latest`
   returns `"tag_name": "1.4.1"`, `"published_at": "2025-06-30T03:58:33Z"`. So 1.4.1 actually
   shipped **June 2025, not June 2024** as the brief assumed — the premise's date was off by
   a year. This was cross-checked against the repo's Tags page (same date) and the Maven
   metadata (`<lastUpdated>20250630040143</lastUpdated>` at
   <https://maven.wagyourtail.xyz/releases/xyz/wagyourtail/unimined/xyz.wagyourtail.unimined.gradle.plugin/maven-metadata.xml>,
   also `<release>1.4.1</release>` / `<latest>1.4.1</latest>` — no newer release published to
   Maven either).
3. **Minecraft 1.21.11 release date** — per the Minecraft Wiki, 1.21.11 ("Mounts of Mayhem")
   shipped **December 9, 2025**, i.e. *after* unimined 1.4.1 (June 2025). So the actual gap is
   about 5-6 months, not eighteen — 1.4.1 could not have targeted a Minecraft version that
   didn't exist yet, but it also never received a follow-up release once 1.21.11 did ship.
4. **Issues/README for 1.21.x support** — found direct, decisive evidence that even
   post-1.4.1 development does not support 1.21.11 cleanly:
   - Issue [#189 "1.21.11 support"](https://github.com/unimined/unimined/issues/189): filed
     by a user running `unimined 1.4.2-SNAPSHOT` (an unreleased build newer than 1.4.1),
     reporting "1.21.11 seems to be broken for all modloaders, Minecraft classes are not
     getting deobfuscated."
   - The fix landed in PR [#185](https://github.com/unimined/unimined/pull/185) ("26.1+
     Support and Bug Fixes"), merged 2026-03-29 into the `lts/1.4` branch — **eight months
     after Minecraft 1.21.11 shipped, and still not cut as a tagged release.**
   - The only place this fix is available is the floating `1.4.2-SNAPSHOT` coordinate on
     <https://maven.wagyourtail.xyz/snapshots/...> (confirmed present in that repository's
     directory listing). There is still no `1.4.2` (or later) *release*.
   - Separately, issue [#193](https://github.com/unimined/unimined/issues/193) ("NeoForge
     1.21.11 build fails") was still **open** as of March 2026, showing 1.21.11 support work
     is ongoing and not fully settled even in bleeding-edge builds.

**Conclusion for Step 1:** the newest published *release* is still 1.4.1, and it does not
support 1.21.11 (confirmed broken by a user running a newer unreleased snapshot). The only
build with a fix is an unreleased, floating `-SNAPSHOT` coordinate merged eight months after
1.21.11 shipped. Per the brief's own decision rule ("If 1.4.1 is still the newest, go directly
to Path B"), and given that pinning a multi-year toolchain decision to a floating snapshot
dependency (not a stable, reproducible release coordinate) is not appropriate regardless of
whether it happens to build today, **Path A was skipped and Path B (Fabric Loom) was used
directly.** No time was spent attempting to configure unimined.

### Path B: Fabric Loom

- Loom version was found by fetching
  `https://raw.githubusercontent.com/FabricMC/fabric-example-mod/1.21.11/gradle.properties`
  (the official Fabric example mod's own `1.21.11` branch — confirmed to exist via the
  GitHub branches API). That file pins the *exact same* `minecraft_version`, `loader_version`,
  and `fabric_api_version` this project already had pinned in `gradle.properties`
  (`1.21.11` / `0.19.3` / `0.141.6+1.21.11`), which is a strong signal these are the correct,
  currently-working coordinates. It uses `loom_version=1.17-SNAPSHOT`.
- Rather than depend on a floating `-SNAPSHOT` coordinate (the same category of problem
  Path A was rejected for), the stable numbered release was found instead via
  `https://maven.fabricmc.net/net/fabricmc/fabric-loom/maven-metadata.xml`, which lists
  `<release>1.17.17</release>` as the latest stable release in the same `1.17.x` line the
  example mod's snapshot tracks. **`1.17.17` was used instead of `1.17-SNAPSHOT`.**
- `adapters/adapter-fabric-1.21.11/build.gradle.kts` was written per the brief's Path B
  template, with one required deviation: the brief's
  `id("fabric-loom") version "${property("loom_version")}"` does not compile. The Kotlin DSL
  `plugins {}` block runs in a restricted scope (`PluginDependenciesSpecScope`) that has no
  access to `property()` or `providers` from the enclosing script — confirmed by an
  `Unresolved reference` compile error for both `property(...)` and
  `providers.gradleProperty(...)`. The version is hardcoded as a literal
  (`id("fabric-loom") version "1.17.17"`) with a comment explaining why, matching how
  Loom-based mods conventionally pin this value. `loom_version=1.17.17` remains in
  `gradle.properties` for documentation/traceability even though the build script no longer
  reads it programmatically.
- First real build attempt failed with: `Dependency requires at least JVM runtime version 21.
  This build uses a Java 17 JVM.` — the Gradle **daemon's own JVM**, not just the project's
  compile toolchain, must be Java 21+ for Loom 1.17.17's plugin classpath to load. Fixed by
  adding `org.gradle.java.home=C:\\SDK\\jdk-21.0.12` to `gradle.properties` (alongside the
  existing `org.gradle.java.installations.paths` used for toolchain resolution — these are
  two different settings; the latter alone was not sufficient).
- Second attempt failed with a Gradle plugin variant mismatch: Loom 1.17.17 declares
  `org.gradle.plugin.api-version = 9.5.0`, but the committed wrapper was Gradle 8.14. **The
  Gradle wrapper was bumped from 8.14 to 9.6.1** (the current stable Gradle release, confirmed
  via `https://services.gradle.org/versions/current`), by editing
  `gradle/wrapper/gradle-wrapper.properties` directly (the normal `./gradlew wrapper
  --gradle-version` task could not be used because the broken adapter subproject's
  configuration failure blocked *all* task execution, including `wrapper`, since Gradle
  configures every project before running any task).
- Third attempt: `./gradlew :adapters:adapter-fabric-1.21.11:build` — **BUILD SUCCESSFUL**
  (see full output in the accompanying report). Loom printed
  `Fabric Loom: 1.17.17` and the Mojang mappings license notice, confirming Mojang mappings
  (not Yarn) were used, per the hard requirement.
- A separate, unrelated gap was found and fixed while running Step 5
  (`checkDependencyDirection`): registering `include("adapters:adapter-fabric-1.21.11")` in
  `settings.gradle.kts` implicitly creates a parent project `:adapters` (Gradle's
  colon-segmented `include` always creates one project per path segment). That parent project
  has no `build.gradle.kts` and declares no dependencies, but it is still enumerated by
  `allprojects` in the root `checkDependencyDirection` task and was not in
  `allowedProjectDependencies`, so the check failed with "`:adapters` is not listed in
  allowedProjectDependencies." This is not a toolchain issue — Task 2's allowlist just didn't
  anticipate the implicit intermediate project. Fixed properly by adding
  `":adapters" to emptySet()` to the map in the root `build.gradle.kts` (not suppressed).
- `./gradlew clean build` (full multi-module build): **BUILD SUCCESSFUL.**
  `:platform:checkCorePurity`, `:platform:checkCoreBytecode`, `:core:checkCorePurity`, and
  `:core:checkCoreBytecode` all ran and passed — Minecraft did not leak into `platform` or
  `core` even though it is now present elsewhere in the build. All 12 `core` tests passed.

## Consequence for M2 (Forge 1.7.10)

Loom was used, so the roadmap's "one plugin for both Fabric 1.21.11 and Forge 1.7.10" premise
does not hold. **M2 will need a second, unrelated toolchain for Forge 1.7.10** —
RetroFuturaGradle is the maintained option for that era of Forge. This should be budgeted as
its own spike in M2, not assumed to be a drop-in extension of the M1 setup. Note that this
would have been true even if unimined's 1.4.1 release date had been correctly understood from
the start (June 2025, not June 2024) — the deciding factor was concrete evidence that 1.21.11
support is broken as of the latest release and only partially fixed in an unreleased,
non-reproducible snapshot, not the plugin's staleness alone.

## Gradle version

Bumped from **8.14 to 9.6.1** (current stable release as of 2026-08-01, confirmed via
`https://services.gradle.org/versions/current`). Required because Fabric Loom 1.17.17
declares `org.gradle.plugin.api-version = 9.5.0` and Gradle 8.14 could not satisfy that plugin
variant. `gradle/wrapper/gradle-wrapper.properties` was edited directly (its
`distributionUrl`) rather than via `./gradlew wrapper --gradle-version 9.6.1`, because running
any task at all — including `wrapper` — first configures every project in the build,
including the adapter module, which failed to configure under the old Gradle version.

Additionally, the Gradle **daemon's own JVM** (not just the project's Java 21 compile
toolchain) must now be Java 21+, since Loom 1.17.17's plugin classpath requires it. This is
set via `org.gradle.java.home=C:\\SDK\\jdk-21.0.12` in `gradle.properties`, alongside the
pre-existing `org.gradle.java.installations.paths` (used for toolchain resolution — a
different setting that alone did not fix this).

## Step 7 — PENDING HUMAN VERIFICATION

`runClient` was deliberately **not run**. Launching a Minecraft client requires a human
watching a game window to confirm the main menu is reached; this task only proves the
toolchain configures and builds correctly. Run:

```
./gradlew :adapters:adapter-fabric-1.21.11:runClient
```

Expected: a Minecraft 1.21.11 client window opens under Mojang mappings and reaches the main
menu, with no mod behaviour to observe yet. Close the client when confirmed.

## Honest uncertainties

- `runClient` itself has not been executed by anyone yet (human or agent) — `build` proves
  compilation, mapping resolution, and jar remapping succeed, but not that the client actually
  launches and reaches the main menu. That is exactly what Step 7 is for.
- The Gradle daemon-JVM requirement (`org.gradle.java.home`) is pinned to this machine's exact
  path (`C:\SDK\jdk-21.0.12`), matching the existing convention for
  `org.gradle.java.installations.paths` in this repo, but it means both settings will need
  updating together if this project ever moves to a different machine or CI runner.
- Bumping the wrapper to Gradle 9.6.1 was not exhaustively tested against every existing task
  beyond `build`, `clean build`, and `checkDependencyDirection` (all of which passed). It is
  possible, though not observed, that some other tooling (IDE import, CI config referencing
  8.14 explicitly, etc.) outside this repo's Gradle files assumes 8.14 and would need
  updating — nothing in this repo's tracked files does.
