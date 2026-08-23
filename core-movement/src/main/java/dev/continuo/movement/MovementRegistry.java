package dev.continuo.movement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * The standard registry: insertion-ordered, duplicate-rejecting, capability-filtering.
 *
 * <p>Empty when constructed. Nothing is discovered implicitly — see
 * {@link #discover()} — so a caller always knows exactly which movements a
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
        if (ids.contains(id)) {
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
        ids.add(id);
        registered.add(type);
    }

    /**
     * Finds every {@link IMovementType} on the classpath and registers it.
     *
     * <p><b>Sorted by {@link IMovementType#id()} before registering, and that is not
     * cosmetic.</b> {@link ServiceLoader}'s iteration order is unspecified — in practice it
     * follows classpath order, which varies by environment. The search breaks cost ties by the
     * order neighbours were discovered, so feeding an unspecified order into it would make paths
     * depend on the classpath while every test stayed green wherever it happened to run.
     *
     * <p>Additive: whatever was registered explicitly keeps its position, and discovered
     * movements land after it. That is what lets the built-in movements keep C1's expansion
     * order exactly.
     *
     * @throws IllegalArgumentException if a discovered movement duplicates a registered id, or
     *         declares a non-positive {@link IMovementType#minCostPerAxisStep()}
     */
    public void discover() {
        List<IMovementType> found = new ArrayList<IMovementType>();
        for (IMovementType type : ServiceLoader.load(IMovementType.class)) {
            found.add(type);
        }
        registerAllSorted(found);
    }

    /**
     * Registers a batch in {@code id} order. The seam {@link #discover()} is tested through: a
     * test can hand it a deliberately reversed batch, which no real {@link ServiceLoader} can be
     * made to produce on demand.
     *
     * @param found the movements to register; never {@code null}
     */
    void registerAllSorted(Iterable<IMovementType> found) {
        List<IMovementType> sorted = new ArrayList<IMovementType>();
        for (IMovementType type : found) {
            sorted.add(type);
        }
        Collections.sort(sorted, new Comparator<IMovementType>() {
            @Override
            public int compare(IMovementType left, IMovementType right) {
                return left.id().compareTo(right.id());
            }
        });
        for (int i = 0; i < sorted.size(); i++) {
            register(sorted.get(i));
        }
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
