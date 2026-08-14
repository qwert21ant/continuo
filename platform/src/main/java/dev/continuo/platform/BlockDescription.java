package dev.continuo.platform;

/**
 * A block's raw physical facts, as one Minecraft version reports them.
 *
 * <p>Deliberately contains <em>no judgement</em>. An adapter reports what the game says and
 * nothing more; deciding that a given set of collision boxes is a slab, or that a given block
 * should be avoided, is the core's job and happens against a shared, version-independent
 * classifier. That division is what keeps two adapters from disagreeing about the same block,
 * and it is why this type carries an id and a box array rather than a shape.
 *
 * <p>This is the only value class in this package. It is one because an adapter constructs one
 * of these per distinct block state and would otherwise have to write a six-method class or an
 * anonymous inner class each time.
 *
 * <p>Immutable. Subject to all four global rules in this package's documentation.
 */
public final class BlockDescription {

    private final String id;
    private final String stateKey;
    private final double[] collisionBoxes;
    private final String fluidId;
    private final boolean climbable;
    private final boolean gravity;

    /**
     * @param id the block's namespaced registry name, such as {@code minecraft:oak_slab};
     *           never {@code null}
     * @param stateKey a human-meaningful key identifying this specific state, beginning with
     *                 {@code id}; never {@code null}
     * @param collisionBoxes flattened six-tuples of {@code minX, minY, minZ, maxX, maxY, maxZ}
     *                       in block-relative coordinates, so a full cube is
     *                       {@code {0,0,0, 1,1,1}}. Empty means no collision at all. Copied on
     *                       construction; never {@code null}
     * @param fluidId the namespaced id of the fluid occupying this block, or {@code null} if
     *                none. Reported verbatim as the platform names it — normalising
     *                {@code flowing_water} to water is classification and is not done here
     * @param climbable whether the platform considers this block climbable, such as a ladder
     *                  or a vine
     * @param gravity whether this block is affected by gravity, such as sand or gravel
     * @throws IllegalArgumentException if {@code id}, {@code stateKey} or
     *         {@code collisionBoxes} is {@code null}, or if {@code collisionBoxes} is not a
     *         whole number of six-tuples
     */
    public BlockDescription(String id,
                            String stateKey,
                            double[] collisionBoxes,
                            String fluidId,
                            boolean climbable,
                            boolean gravity) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (stateKey == null) {
            throw new IllegalArgumentException("stateKey must not be null");
        }
        if (collisionBoxes == null) {
            throw new IllegalArgumentException("collisionBoxes must not be null; use an empty array for no collision");
        }
        if (collisionBoxes.length % 6 != 0) {
            throw new IllegalArgumentException(
                "collisionBoxes must be a whole number of six-tuples, but had length " + collisionBoxes.length);
        }
        this.id = id;
        this.stateKey = stateKey;
        this.collisionBoxes = collisionBoxes.clone();
        this.fluidId = fluidId;
        this.climbable = climbable;
        this.gravity = gravity;
    }

    /** @return the namespaced registry name; never {@code null} */
    public String id() {
        return id;
    }

    /** @return the state key, which begins with {@link #id()}; never {@code null} */
    public String stateKey() {
        return stateKey;
    }

    /**
     * @return a fresh copy of the flattened collision boxes; never {@code null}, possibly empty
     */
    public double[] collisionBoxes() {
        return collisionBoxes.clone();
    }

    /** @return the occupying fluid's namespaced id, or {@code null} if there is none */
    public String fluidId() {
        return fluidId;
    }

    /** @return whether the platform considers this block climbable */
    public boolean climbable() {
        return climbable;
    }

    /** @return whether this block is affected by gravity */
    public boolean gravity() {
        return gravity;
    }
}
