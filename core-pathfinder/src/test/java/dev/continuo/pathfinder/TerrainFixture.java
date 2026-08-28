package dev.continuo.pathfinder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Loads the committed real-terrain probe dumps.
 *
 * <p>A dump needs no conversion: the probe emits exactly {@link FixtureWorld}'s format, and
 * {@code //} lines are skipped by the parser. The {@code *} and {@code +} overlays parse back as
 * air, which is correct, because they mark feet positions that were air.
 *
 * <p><b>Two of these fixtures carry caveats recorded in the design, section 7.1.</b>
 * {@code a-big-obstacle} does not reproduce its captured in-game route, because 397 unnamed cells
 * sit near the optimal one; it is a valid world but never evidence about a real run.
 * {@code e-long-range} is clamped, so its goal lies outside the map and {@code goal()} is null --
 * supply the goal by hand from the clamp notice inside the file.
 */
final class TerrainFixture {

    private TerrainFixture() {
    }

    static FixtureWorld load(String name) {
        InputStream in = TerrainFixture.class.getResourceAsStream("/terrain/" + name);
        if (in == null) {
            throw new IllegalArgumentException("no such terrain fixture on the classpath: " + name);
        }
        try {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n = in.read(buf);
                while (n > 0) {
                    out.write(buf, 0, n);
                    n = in.read(buf);
                }
                return FixtureWorld.parse(new String(out.toByteArray(), "UTF-8"));
            } finally {
                in.close();
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not read terrain fixture " + name, e);
        }
    }
}
