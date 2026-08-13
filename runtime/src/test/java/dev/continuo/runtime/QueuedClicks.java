package dev.continuo.runtime;

/** A {@link ClickSource} backed by a settable count, mirroring how a keybind queues clicks. */
final class QueuedClicks implements ClickSource {

    private int queued;

    void queue(int count) {
        queued += count;
    }

    @Override
    public boolean consumeClick() {
        if (queued <= 0) {
            return false;
        }
        queued--;
        return true;
    }
}
