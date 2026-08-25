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
    private final HeuristicRates rates;

    /**
     * @param movements the active movements, in the order the search must expand them; copied
     * @throws IllegalStateException if empty — a search with no movements has no multiplier, and
     *                               returning an arbitrary one would hide the mistake
     */
    ActiveMovements(List<IMovementType> movements) {
        if (movements.isEmpty()) {
            throw new IllegalStateException(
                "no movement is active for these capabilities; a search with no movements has no "
                    + "rates to derive and could not be admissible");
        }
        this.movements = Collections.unmodifiableList(new ArrayList<IMovementType>(movements));

        double horizontal = Double.POSITIVE_INFINITY;
        double vertical = Double.POSITIVE_INFINITY;
        for (int i = 0; i < this.movements.size(); i++) {
            IMovementType type = this.movements.get(i);
            double declaredHorizontal = type.minCostPerHorizontalUnit();
            if (declaredHorizontal < horizontal) {
                horizontal = declaredHorizontal;
            }
            double declaredVertical = type.minCostPerVerticalStep();
            if (declaredVertical < vertical) {
                vertical = declaredVertical;
            }
        }
        this.rates = new HeuristicRates(horizontal, vertical);
    }

    /** @return the active movements in expansion order, unmodifiable; never empty */
    public List<IMovementType> movements() {
        return movements;
    }

    /**
     * The rates the heuristic scales its distance estimate by.
     *
     * <p><b>This is what makes A* admissible, and it is structural rather than numeric.</b> Each
     * rate is a minimum over exactly the movements the search will use, so every movement
     * satisfies {@code cost >= horizontal × octileUnits(dx, dz)} and
     * {@code cost >= vertical × |dy|} by definition, and therefore bounds the estimate's decrease
     * across any edge it offers. Adding a cheap wide movement merely loosens the heuristic rather
     * than breaking it.
     *
     * @return the rates; never {@code null}
     */
    public HeuristicRates rates() {
        return rates;
    }
}
