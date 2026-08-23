package dev.continuo.movement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The movements a search may use, and the heuristic multiplier they imply.
 *
 * <p><b>The two travel together on purpose.</b> The failure this type exists to prevent is a
 * movement set and a multiplier drifting apart — a search filtering by one set while scaling its
 * heuristic by another set's minimum silently stops returning shortest paths. A type that cannot
 * hand out one without the other makes that unrepresentable.
 *
 * <p>Immutable. The multiplier is computed once, at construction.
 */
public final class ActiveMovements {

    private final List<IMovementType> movements;
    private final double cheapestAxisStep;

    /**
     * @param movements the active movements, in the order the search must expand them; copied
     * @throws IllegalStateException if empty — a search with no movements has no multiplier, and
     *                               returning an arbitrary one would hide the mistake
     */
    ActiveMovements(List<IMovementType> movements) {
        if (movements.isEmpty()) {
            throw new IllegalStateException(
                "no movement is active for these capabilities; a search with no movements has no "
                    + "cheapest axis step and could not be admissible");
        }
        this.movements = Collections.unmodifiableList(new ArrayList<IMovementType>(movements));

        double cheapest = Double.POSITIVE_INFINITY;
        for (int i = 0; i < this.movements.size(); i++) {
            double declared = this.movements.get(i).minCostPerAxisStep();
            if (declared < cheapest) {
                cheapest = declared;
            }
        }
        this.cheapestAxisStep = cheapest;
    }

    /** @return the active movements in expansion order, unmodifiable; never empty */
    public List<IMovementType> movements() {
        return movements;
    }

    /**
     * The heuristic's multiplier: the smallest cost any active movement can charge for one axis
     * step.
     *
     * <p><b>This is what makes A* admissible, and it is now structural rather than numeric.</b>
     * Because it is a minimum over exactly the movements the search will use, every movement
     * satisfies {@code cost >= axisSpan * cheapestAxisStep} by definition. C1 could only assert
     * that as a checked property of a closed cost table; adding a cheap wide movement used to
     * break admissibility silently and now merely loosens the heuristic.
     *
     * @return the multiplier, in ticks per axis step; always positive
     */
    public double cheapestAxisStep() {
        return cheapestAxisStep;
    }
}
