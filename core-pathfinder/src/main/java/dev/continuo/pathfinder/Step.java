package dev.continuo.pathfinder;

import dev.continuo.movement.MovementKind;

/**
 * One move of a path: where it goes, and what kind of movement gets there.
 *
 * <p>Derived when the result is built rather than re-derived by whoever executes it, so the mapping
 * from a position delta to a movement has one home. See {@link MovementKind}.
 */
public final class Step {

    private final Pos to;
    private final MovementKind kind;

    Step(Pos to, MovementKind kind) {
        this.to = to;
        this.kind = kind;
    }

    /** @return where this step ends; never {@code null} */
    public Pos to() {
        return to;
    }

    /**
     * What kind of movement this step is.
     *
     * <p><b>May be {@code null}</b>, meaning no registered movement can emit this step's delta.
     * That is a defect in {@link MovementKind}'s table rather than in the path, and
     * {@code MovementKind}'s own documentation explains why it is reported this way instead of
     * thrown.
     *
     * @return the kind, or {@code null} if the delta cannot be named
     */
    public MovementKind kind() {
        return kind;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Step)) {
            return false;
        }
        Step that = (Step) other;
        return to.equals(that.to) && kind == that.kind;
    }

    @Override
    public int hashCode() {
        return 31 * to.hashCode() + (kind == null ? 0 : kind.hashCode());
    }

    @Override
    public String toString() {
        return kind + " -> " + to;
    }
}
