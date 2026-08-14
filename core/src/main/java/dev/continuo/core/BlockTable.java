package dev.continuo.core;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The per-version overrides the classifier cannot derive from geometry.
 *
 * <p>Small by design — a few dozen rows, not thousands. A block with no row is the normal
 * case, classified from its collision boxes and description flags alone; rows exist only for
 * facts geometry cannot carry, such as soul sand slowing you down while being shaped exactly
 * like stone.
 *
 * <p>Two sections: {@code blocks} keyed by a block's registry id for whole-block rules, and
 * {@code states} keyed by a state key for the rare state-specific override. States win.
 */
public final class BlockTable {

    /** A table with no rows. Everything classifies from geometry. */
    public static final BlockTable EMPTY =
        new BlockTable(Collections.<String, Row>emptyMap(), Collections.<String, Row>emptyMap());

    private final Map<String, Row> blocks;
    private final Map<String, Row> states;

    BlockTable(Map<String, Row> blocks, Map<String, Row> states) {
        this.blocks = Collections.unmodifiableMap(blocks);
        this.states = Collections.unmodifiableMap(states);
    }

    /**
     * @param id a block's namespaced registry name
     * @return the whole-block row, or {@code null} if there is none
     */
    public Row forBlock(String id) {
        return blocks.get(id);
    }

    /**
     * @param stateKey a block state's key
     * @return the state-specific row, or {@code null} if there is none
     */
    public Row forState(String stateKey) {
        return states.get(stateKey);
    }

    /** One override row. Any of its three fields may be absent. */
    public static final class Row {

        private final BlockShape shape;
        private final Fluid fluid;
        private final Set<BlockTag> tags;

        Row(BlockShape shape, Fluid fluid, EnumSet<BlockTag> tags) {
            this.shape = shape;
            this.fluid = fluid;
            this.tags = Collections.unmodifiableSet(EnumSet.copyOf(tags));
        }

        /** @return the shape this row forces, or {@code null} to keep geometry's answer */
        public BlockShape shape() {
            return shape;
        }

        /** @return the fluid this row forces, or {@code null} to keep the derived answer */
        public Fluid fluid() {
            return fluid;
        }

        /**
         * @return tags this row adds, unmodifiable and never {@code null}. Rows only ever
         *         <em>add</em> tags; removal is deliberately unsupported
         */
        public Set<BlockTag> tags() {
            return tags;
        }
    }
}
