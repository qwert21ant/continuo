# Bytecode injection strategy (decided ahead of need)

**Chosen:** SpongePowered Mixin, on every adapter, forever.
**Date:** 2026-08-14
**Status:** Decided on reasoning, **not yet validated by a build.** Nothing in the repo uses
mixins today. See "Honest uncertainties" before acting on this.

## Why this exists

Both target versions expose only a fraction of what the core will eventually need through
their loader APIs. `IGameEvents.onPositionCorrection` — a server position rollback — has no
clean event on Fabric 1.21.11 *or* Forge 1.7.10; it means hooking the clientbound position
packet on both. That is the first thing this project will need bytecode injection for, and it
lands at **M5**, not before.

The question this document answers, asked during the M3 brainstorm: is there one injection
tool that serves *all* adapters, rather than one per version family?

## The answer: the library is already universal

**Mixin is not a Minecraft library.** It is a general JVM bytecode-transformation framework
built on ASM. `@Mixin`, `@Inject`, `@Redirect`, `@Shadow` are generic constructs with no
Minecraft dependency. What is Minecraft-specific is only the *bootstrap layer* — how mixin
configs get registered and applied during classloading, and how obfuscated names are resolved.

That bootstrap differs per loader, and it is the only thing that does:

| Target | Where Mixin comes from |
|---|---|
| Fabric, any version | Fabric Loader bundles it (`net.fabricmc:sponge-mixin`, a maintained fork). Nothing to add. |
| Forge / NeoForge 1.16+ | The loader bundles it, via ModLauncher. Nothing to add. |
| Forge 1.7.10 – 1.12.2 | **UniMixins** (LegacyModdingMC) — one distribution unifying the older zoo of MixinBooter / SpongeMixins / GTNHMixins / GasStation / Grimoire. |
| Forge 1.13 – 1.15 | The awkward gap. MixinBootstrap territory. **Not on this project's roadmap.** |

Every version this project targets or plans to target is covered: 1.7.10 (UniMixins),
1.21.11 (Fabric Loader), and M9's NeoForge and 1.20.x (bundled). The annotations and the idiom
are identical on all of them.

The premise worth correcting, because it points the wrong way: UniMixins is **not** a different
tool from what modern loaders use. It is a *packaging* of the same upstream SpongePowered Mixin
for loaders that predate bundling it. There is no legacy-versus-modern split in the injection
model, only in where the jar comes from.

**MixinExtras** is worth knowing alongside it: `@WrapOperation` and `@ModifyExpressionValue`
give expression-level injections that are far less brittle than `@Redirect`, and UniMixins
bundles it, so it is available on 1.7.10 too.

## What stays version-specific regardless

This is the part that is easy to be disappointed by later, so it is stated plainly:

**The library is universal. The mixin code is not, and cannot be.** A mixin targets
`net.minecraft.client.Minecraft` by name, with version-specific method signatures and — on
1.7.10 — SRG names resolved through a refmap. That target is different on every version by
definition.

So mixin classes live in `adapters/`, permanently, and are never shared. What universality buys
is one idiom, one mental model, one set of annotations, and one build-side concept — not shared
code. That is the same bargain the rest of the project already made: version differences are
translation, not logic.

**Consequence for the standing SPI audit (H is a property, not a phase):** a mixin body must
stay thin. It captures the event and immediately calls into `:runtime` or the adapter. A mixin
containing an `if` is logic in an adapter, and the audit should read it as such.

## Ruled out

- **Java agents.** They need `-javaagent` on the JVM command line, which no mod user will have;
  self-attach is disabled by default on modern JVMs; and an agent fights the loaders' own
  transformation pipelines rather than cooperating with them.
- **Raw ASM via loader transformers** (`IClassTransformer` on legacy, `ILaunchPluginService`
  on modern). Lower level, and it gives up the thing Mixin exists for: when two mods inject
  into the same method, Mixin resolves it and reports conflicts. Raw transformers clobber each
  other silently.
- **Byte Buddy / Javassist.** General-purpose and capable, but no loader integration, no
  refmap, no conflict handling. Same objection as agents.

**Access transformers and access wideners are not an alternative and are not ruled out** —
they solve visibility, not interception, and this project already uses one
(`META-INF/continuo_at.cfg` widening `KeyBinding.pressed`). Reach for those first when the
problem is only that a field or method is private; reach for Mixin when you need to *observe*
or *change* behaviour.

## Timing

The roadmap gates mixins as "not before M3". That gate is now moot for M3 itself: the M3
design takes calculation-scoped world snapshots with no event-driven cache invalidation, so
**M3 needs no mixins at all** and `IGameEvents` stays at a single method through the whole
sub-project.

M5 is the first real need. Budget standing up UniMixins on the 1.7.10 side as its own task
there — it is new tooling on the most environment-sensitive of the two adapters, and the
project's own history says that kind of task costs more than it looks.

## Honest uncertainties

- **None of this has been built or run.** Unlike `toolchain-decision.md`, which records actual
  build attempts, this document is reasoning recorded ahead of need. No mixin exists in this
  repo, UniMixins has never been added to the 1.7.10 build, and no refmap has ever been
  generated here.
- The specific version numbers and Forge's exact bundling cutoff were **not verified against
  current releases** at the time of writing. Check the UniMixins releases page and Forge's
  bundling history when the work actually starts; treat the table above as the shape of the
  answer, not as pinned coordinates.
- RetroFuturaGradle's refmap and mixin wiring for 1.7.10 is unexamined. It is a known-solved
  problem in that ecosystem, but "known-solved by others" is how the Loom-versus-unimined
  question started too.
- Whether Mixin's presence changes the `checkCorePurity` / `checkDependencyDirection`
  invariants is unconsidered. It should not — mixins are adapter-scoped — but nobody has
  looked.
