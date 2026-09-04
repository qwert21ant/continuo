package dev.continuo.movement;

/**
 * What one step of a path is, derived from the positions it joins.
 *
 * <p><b>A path carries no movement identity of its own.</b> {@link MoveSink#offer} takes no movement
 * argument and a search result is a bare list of positions, so an executor either re-derives what
 * each step was or the search threads an edge identity through its hottest interface. D2 chose
 * derivation, on the evidence that the five registered movements emit <b>pairwise disjoint</b> delta
 * sets that <b>jointly cover</b> everything the registry can produce — so the derivation is lossless
 * rather than a guess.
 *
 * <p><b>That property is not permanent, and it is guarded rather than assumed.</b> A diagonal
 * ascend, a three-block parkour, a ladder climb or a water move would break it. The guard is a test
 * that expands every registered {@link IMovementType} and asserts totality, disjointness, and
 * agreement between each offer's derived kind and the producing type's own {@link
 * IMovementType#id()} — which is what {@link #movementId()} exists for. Adding such a movement fails
 * the build at the point of addition rather than mis-executing silently in a client.
 */
public enum MovementKind {

    /** Walking one block to a cardinal neighbour at the same height. */
    TRAVERSE("walk.traverse"),

    /** Walking one block diagonally on the level. */
    DIAGONAL("walk.diagonal"),

    /** Jumping up one block onto a cardinal neighbour. */
    ASCEND("walk.ascend"),

    /** Walking off a ledge to a cardinal neighbour and falling to the first floor below. */
    DESCEND("walk.descend"),

    /** Sprint-jumping a one-block gap to the block beyond, on the level. */
    PARKOUR("walk.parkour");

    private final String movementId;

    MovementKind(String movementId) {
        this.movementId = movementId;
    }

    /**
     * The {@link IMovementType#id()} of the movement this names.
     *
     * @return the id; never {@code null}
     */
    public String movementId() {
        return movementId;
    }

    /**
     * The kind of movement that joins two positions, or {@code null} if none does.
     *
     * <p><b>{@code null} is returned rather than thrown</b>, because this is called while building
     * every search result including the probe's, and a result that cannot be constructed is worse
     * than a step an executor refuses to drive. A caller that meets {@code null} has found a defect
     * in this table, not in its own input.
     *
     * @param dx the destination's X minus the origin's
     * @param dy the destination's Y minus the origin's
     * @param dz the destination's Z minus the origin's
     * @return the kind, or {@code null} if no registered movement can emit that delta
     */
    public static MovementKind forDelta(int dx, int dy, int dz) {
        int ax = dx < 0 ? -dx : dx;
        int az = dz < 0 ? -dz : dz;
        if (dy == 0) {
            if (ax + az == 1) {
                return TRAVERSE;
            }
            if (ax == 1 && az == 1) {
                return DIAGONAL;
            }
            if ((ax == 2 && az == 0) || (ax == 0 && az == 2)) {
                return PARKOUR;
            }
            return null;
        }
        if (ax + az != 1) {
            return null;
        }
        if (dy == 1) {
            return ASCEND;
        }
        return dy < 0 && dy >= -MovementCosts.MAX_SAFE_FALL ? DESCEND : null;
    }
}
