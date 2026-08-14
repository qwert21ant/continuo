package dev.continuo.core;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Everything the core knows about one block state.
 *
 * <p>Produced by {@link BlockClassifier} from a {@code BlockDescription} and the per-version
 * override table, never by an adapter. Keeping this type in {@code dev.continuo.core} rather
 * than in the SPI is what spares every future adapter from having to speak the core's
 * classification vocabulary.
 *
 * <p>Immutable, and interned per block state by {@link BlockLookup}.
 */
public final class BlockData {

    /** The value for a position that could not be read. */
    public static final BlockData UNKNOWN =
        new BlockData(BlockShape.UNKNOWN, 0.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class));

    private final BlockShape shape;
    private final double collisionTop;
    private final Fluid fluid;
    private final Set<BlockTag> tags;

    /**
     * @param shape the collision category; never {@code null}
     * @param collisionTop the highest Y any collision box reaches, in block-relative
     *                     coordinates: {@code 0} for no collision, {@code 1.0} for a full
     *                     cube, {@code 1.5} for a typical fence
     * @param fluid the occupying fluid; never {@code null}, use {@link Fluid#NONE}
     * @param tags the semantic tags; never {@code null}, copied on construction
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public BlockData(BlockShape shape, double collisionTop, Fluid fluid, EnumSet<BlockTag> tags) {
        if (shape == null) {
            throw new IllegalArgumentException("shape must not be null");
        }
        if (fluid == null) {
            throw new IllegalArgumentException("fluid must not be null; use Fluid.NONE");
        }
        if (tags == null) {
            throw new IllegalArgumentException("tags must not be null; use EnumSet.noneOf(BlockTag.class)");
        }
        this.shape = shape;
        this.collisionTop = collisionTop;
        this.fluid = fluid;
        this.tags = Collections.unmodifiableSet(EnumSet.copyOf(tags));
    }

    /** @return the collision category; never {@code null} */
    public BlockShape shape() {
        return shape;
    }

    /** @return the highest Y any collision box reaches; {@code 0} if there is no collision */
    public double collisionTop() {
        return collisionTop;
    }

    /** @return the occupying fluid; never {@code null} */
    public Fluid fluid() {
        return fluid;
    }

    /** @return the tags, unmodifiable; never {@code null} */
    public Set<BlockTag> tags() {
        return tags;
    }

    /**
     * @param tag the tag to test for
     * @return whether this block carries that tag
     */
    public boolean has(BlockTag tag) {
        return tags.contains(tag);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BlockData)) {
            return false;
        }
        BlockData other = (BlockData) o;
        return shape == other.shape
            && Double.compare(collisionTop, other.collisionTop) == 0
            && fluid == other.fluid
            && tags.equals(other.tags);
    }

    @Override
    public int hashCode() {
        int result = shape.hashCode();
        result = 31 * result + Double.valueOf(collisionTop).hashCode();
        result = 31 * result + fluid.hashCode();
        result = 31 * result + tags.hashCode();
        return result;
    }

    /**
     * A stable one-line form carrying every field.
     *
     * <p>Load-bearing: the cross-adapter parity dump is a text diff of this, so a change here
     * invalidates every checked-in dump file.
     *
     * @return {@code "SHAPE top=N fluid=F tags=[A, B]"}
     */
    @Override
    public String toString() {
        return shape + " top=" + collisionTop + " fluid=" + fluid + " tags=" + tags;
    }
}
