package dev.continuo.pathfinder;

/**
 * Renders a {@link FixtureWorld} through the published {@link PathRenderer}.
 *
 * <p>It exists for one reason: {@code FixtureWorld} carries its own bounds and its own start and
 * goal, and every fixture test would otherwise repeat nine arguments. It also absorbs the one
 * asymmetry in the published signature — {@code BlockSource.maxY()} is one past the top, while
 * {@code PathRenderer}'s {@code maxY} is inclusive like its five neighbours.
 *
 * <p>It cannot be called {@code PathRenderer}: a test-source class sharing a package and name
 * with a main-source class shadows it on the test compile classpath, and the tests would then be
 * exercising a copy rather than the published implementation.
 */
final class FixtureRenderer {

    private FixtureRenderer() {
    }

    /**
     * @param world the fixture; never {@code null}
     * @param result the search result; never {@code null}
     * @return the rendering, ending in a newline
     */
    static String render(FixtureWorld world, PathResult result) {
        return PathRenderer.render(world,
            world.minX(), world.minY(), world.minZ(),
            world.maxX(), world.maxY() - 1, world.maxZ(),
            world.start(), world.goal(), result);
    }
}
