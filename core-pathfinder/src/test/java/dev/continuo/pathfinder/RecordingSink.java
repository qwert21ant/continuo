package dev.continuo.pathfinder;

import java.util.ArrayList;
import java.util.List;

/** Captures offers in the order they are made, so tests can assert order as well as content. */
final class RecordingSink implements MoveSink {

    private final List<Pos> positions = new ArrayList<Pos>();
    private final List<Double> costs = new ArrayList<Double>();

    @Override
    public void offer(int x, int y, int z, double cost) {
        positions.add(new Pos(x, y, z));
        costs.add(Double.valueOf(cost));
    }

    List<Pos> positions() {
        return positions;
    }

    double costOf(Pos pos) {
        int index = positions.indexOf(pos);
        if (index < 0) {
            throw new AssertionError("no offer for " + pos + "; offers were " + positions);
        }
        return costs.get(index).doubleValue();
    }

    int size() {
        return positions.size();
    }
}
