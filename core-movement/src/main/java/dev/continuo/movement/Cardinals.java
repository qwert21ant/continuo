package dev.continuo.movement;

/**
 * The four cardinal steps, in the order every movement offers them.
 *
 * <p><b>Load-bearing.</b> A\* breaks cost ties by the order neighbours were discovered, so this
 * order is what makes a path reproducible rather than merely optimal. A test pins a golden path
 * against it.
 *
 * <p>Exposed as accessors rather than as an {@code int[][]} constant on {@link IMovementType}
 * deliberately. An array on a public interface is a mutable global, and this is a plugin API:
 * a movement from another jar could reorder it and silently break the determinism every built-in
 * movement depends on.
 */
public final class Cardinals {

    /** North, east, south, west as {@code {dx, dz}}. */
    private static final int[][] STEPS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    private Cardinals() {
    }

    /** @return how many cardinal steps there are */
    public static int count() {
        return STEPS.length;
    }

    /**
     * @param index a step index, from 0 to {@link #count()} minus one
     * @return that step's X offset
     */
    public static int dx(int index) {
        return STEPS[index][0];
    }

    /**
     * @param index a step index, from 0 to {@link #count()} minus one
     * @return that step's Z offset
     */
    public static int dz(int index) {
        return STEPS[index][1];
    }
}
