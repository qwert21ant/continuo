package dev.continuo.movement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The standard registry: insertion-ordered, duplicate-rejecting, capability-filtering.
 *
 * <p>Empty when constructed. Nothing is discovered implicitly — see
 * {@code MovementRegistry#discover()} — so a caller always knows exactly which movements a
 * search can use.
 */
public final class MovementRegistry implements IMovementRegistry {

    private final List<IMovementType> registered = new ArrayList<IMovementType>();
    private final Set<String> ids = new HashSet<String>();

    @Override
    public void register(IMovementType type) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        String id = type.id();
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException(
                "a movement must have a non-empty id; " + type.getClass().getName() + " has none");
        }
        if (!ids.add(id)) {
            throw new IllegalArgumentException("a movement with id " + id + " is already"
                + " registered; two movements answering to one id would make the registry's"
                + " deduplication and its discovery order both undefined");
        }
        double declared = type.minCostPerAxisStep();
        if (!(declared > 0.0)) {
            throw new IllegalArgumentException("movement " + id + " declares a"
                + " minCostPerAxisStep of " + declared + "; it must be positive, or it would drag"
                + " the heuristic's multiplier to zero and turn A* into an exhaustive search");
        }
        registered.add(type);
    }

    @Override
    public ActiveMovements activeFor(CapabilitySet caps) {
        if (caps == null) {
            throw new IllegalArgumentException("caps must not be null; use CapabilitySet.none()");
        }
        List<IMovementType> active = new ArrayList<IMovementType>(registered.size());
        for (int i = 0; i < registered.size(); i++) {
            IMovementType type = registered.get(i);
            if (caps.grants(type.requires())) {
                active.add(type);
            }
        }
        return new ActiveMovements(active);
    }
}
