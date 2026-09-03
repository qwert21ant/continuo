package dev.continuo.engine;

import dev.continuo.core.Yaw;
import dev.continuo.pathfinder.PathResults;
import dev.continuo.pathfinder.Pos;
import dev.continuo.platform.Input;
import dev.continuo.testkit.FakeActuator;
import dev.continuo.testkit.FakePlayerView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathExecutorDriveTest {

    private final FakeActuator actuator = new FakeActuator();
    private final FakePlayerView player = new FakePlayerView();
    private final RecordingLog log = new RecordingLog();
    private final PathExecutor executor =
        new PathExecutor(new EmptyWorld(), actuator, player, log);

    private void standOn(int x, int y, int z, boolean onGround) {
        player.set(x + 0.5, y, z + 0.5, player.yaw(), 0.0f, onGround);
    }

    /** The last state each input was written to on this tick. */
    private Map<Input, Boolean> written() {
        Map<Input, Boolean> state = new EnumMap<Input, Boolean>(Input.class);
        for (FakeActuator.Call call : actuator.calls()) {
            state.put(call.input, Boolean.valueOf(call.pressed));
        }
        return state;
    }

    private void followFlat(int length) {
        List<Pos> path = new ArrayList<Pos>();
        for (int x = 0; x <= length; x++) {
            path.add(new Pos(x, 64, 0));
        }
        executor.follow(path, PathResults.stepsOf(path));
    }

    @Test
    void anIdleExecutorWritesNothingAtAll() {
        // Rule 4's second clause, and the one an implementation is most likely to get wrong: a
        // core that held every input false each tick would fight the user's own keyboard whenever
        // the bot is not running.
        standOn(0, 64, 0, true);

        executor.tick(player);

        assertEquals(0, actuator.callCount(), "an idle core writes nothing, not even false");
        assertEquals(0, actuator.lookCalls().size());
        assertFalse(executor.active());
    }

    @Test
    void aDrivingTickStatesEverySevenInputs() {
        // Rule 4's first clause. Stating the full set is what makes a screen opening cost one lost
        // tick instead of truncating the walk silently.
        followFlat(10);
        standOn(0, 64, 0, true);

        executor.tick(player);

        assertEquals(Input.values().length, written().size(),
            "every Input constant must be written every driving tick");
    }

    @Test
    void traverseHoldsForwardAndNothingElse() {
        followFlat(10);
        standOn(0, 64, 0, true);

        executor.tick(player);

        Map<Input, Boolean> state = written();
        assertEquals(Boolean.TRUE, state.get(Input.FORWARD));
        assertEquals(Boolean.FALSE, state.get(Input.JUMP));
        assertEquals(Boolean.FALSE, state.get(Input.SPRINT));
        assertEquals(Boolean.FALSE, state.get(Input.SNEAK));
        assertEquals(Boolean.FALSE, state.get(Input.LEFT));
        assertEquals(Boolean.FALSE, state.get(Input.RIGHT));
        assertEquals(Boolean.FALSE, state.get(Input.BACK));
    }

    @Test
    void ascendPressesJumpOnlyWhileOnTheGround() {
        // Spec section 3.9: both versions gate the jump on their own ground flag AND arm a
        // ten-tick cooldown that is cleared the moment the key is released. Holding JUMP through
        // an ascend therefore throttles a staircase to one block per ten ticks. Conditioning on
        // onGround() produces the release without any memory.
        List<Pos> path = new ArrayList<Pos>();
        path.add(new Pos(0, 64, 0));
        path.add(new Pos(1, 65, 0));
        path.add(new Pos(2, 66, 0));
        executor.follow(path, PathResults.stepsOf(path));

        standOn(0, 64, 0, true);
        executor.tick(player);
        assertEquals(Boolean.TRUE, written().get(Input.JUMP), "on the ground, jump");

        actuator.clear();
        player.set(0.5, 64.6, 0.5, player.yaw(), 0.0f, false);
        executor.tick(player);
        assertEquals(Boolean.FALSE, written().get(Input.JUMP),
            "airborne, release -- which is what clears the ten-tick cooldown");
    }

    @Test
    void descendNeverPressesJump() {
        List<Pos> path = new ArrayList<Pos>();
        path.add(new Pos(0, 64, 0));
        path.add(new Pos(1, 61, 0));
        executor.follow(path, PathResults.stepsOf(path));
        standOn(0, 64, 0, true);

        executor.tick(player);

        assertEquals(Boolean.FALSE, written().get(Input.JUMP),
            "jumping off a ledge overshoots the landing the search chose");
    }

    @Test
    void itFacesTheDestinationOfTheStepItIsDriving() {
        followFlat(10);
        standOn(0, 64, 0, true);

        executor.tick(player);

        assertEquals(1, actuator.lookCalls().size());
        float expected = Yaw.toward(player.x(), player.z(), 1, 0);
        // Through the humanizer, so the written yaw is a bounded step toward the target rather
        // than the target itself. From yaw 0 toward -90 that is -30.
        assertEquals(-HumanizedActuator.MAX_DEG_PER_TICK, actuator.lookCalls().get(0).yaw, 0.001f,
            "a turn toward " + expected + " arrives at the rate limit, not all at once");
    }

    @Test
    void aDiagonalIsWalkedByFacingItRatherThanByStrafing() {
        List<Pos> path = new ArrayList<Pos>();
        path.add(new Pos(0, 64, 0));
        path.add(new Pos(1, 64, 1));
        executor.follow(path, PathResults.stepsOf(path));
        standOn(0, 64, 0, true);

        executor.tick(player);

        Map<Input, Boolean> state = written();
        assertEquals(Boolean.FALSE, state.get(Input.LEFT));
        assertEquals(Boolean.FALSE, state.get(Input.RIGHT));
        assertEquals(Boolean.TRUE, state.get(Input.FORWARD));
    }

    @Test
    void arrivingReleasesEveryInputOnceAndGoesIdle() {
        followFlat(2);
        standOn(0, 64, 0, true);
        executor.tick(player);

        actuator.clear();
        standOn(2, 64, 0, true);
        executor.tick(player);

        Map<Input, Boolean> state = written();
        assertEquals(Input.values().length, state.size(), "the release states the whole set");
        for (Input input : Input.values()) {
            assertEquals(Boolean.FALSE, state.get(input), input + " must be released on arrival");
        }
        assertFalse(executor.active());

        actuator.clear();
        executor.tick(player);
        assertEquals(0, actuator.callCount(), "and then it is idle, writing nothing");
    }

    @Test
    void stopReleasesEveryInputOnceAndGoesIdle() {
        followFlat(10);
        standOn(0, 64, 0, true);
        executor.tick(player);
        actuator.clear();

        executor.stop();

        Map<Input, Boolean> state = written();
        assertEquals(Input.values().length, state.size());
        for (Input input : Input.values()) {
            assertEquals(Boolean.FALSE, state.get(input));
        }
        assertFalse(executor.active());
    }

    @Test
    void stopWhileIdleWritesNothing() {
        // Rule 2 makes stop idempotent, and AdapterRuntime calls it on every level transition --
        // including the ordinary world load where nothing was ever running. Releasing there would
        // stamp on the user's keyboard on every dimension change.
        executor.stop();

        assertEquals(0, actuator.callCount());
    }

    @Test
    void anUnnameableStepStopsRatherThanGuessing() {
        List<Pos> path = new ArrayList<Pos>();
        path.add(new Pos(0, 64, 0));
        path.add(new Pos(4, 68, 3));
        executor.follow(path, PathResults.stepsOf(path));
        standOn(0, 64, 0, true);

        executor.tick(player);

        assertFalse(executor.active());
        assertTrue(log.messages().toString().contains("cannot be named"),
            "the log must name the delta, since this means MovementKind's table has a hole");
    }
}
