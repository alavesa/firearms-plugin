package fi.alavesa.firearms;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TrackTest {
    private static Track.Key k(double t, double v, Track.Mode m) { return new Track.Key(t, new double[]{v, 0, 0}, m); }

    @Test
    void linearLerpsBetweenNeighboursAndHoldsOutside() {
        Track tr = new Track(List.of(k(1, 10, Track.Mode.LINEAR), k(0, 0, Track.Mode.LINEAR)), 0);   // unsorted on purpose
        assertEquals(0, tr.at(-1)[0], 1e-9);
        assertEquals(5, tr.at(0.5)[0], 1e-9);
        assertEquals(10, tr.at(1)[0], 1e-9);
        assertEquals(10, tr.at(3)[0], 1e-9);
    }

    @Test
    void stepHoldsTheEarlierKeyUntilTheNext() {
        Track tr = new Track(List.of(k(0, 0, Track.Mode.STEP), k(1, 10, Track.Mode.LINEAR)), 0);
        assertEquals(0, tr.at(0.99)[0], 1e-9);
        assertEquals(10, tr.at(1)[0], 1e-9);
    }

    @Test
    void catmullRomPassesThroughKeysAndOvershootsSmoothly() {
        Track tr = new Track(List.of(k(0, 0, Track.Mode.CATMULLROM), k(1, 10, Track.Mode.CATMULLROM), k(2, 0, Track.Mode.CATMULLROM)), 0);
        assertEquals(0, tr.at(0)[0], 1e-9);
        assertEquals(10, tr.at(1)[0], 1e-9);
        assertEquals(0, tr.at(2)[0], 1e-9);
        // the spline's tangent at the middle key is (p2 - p0)/2 = 0: a smooth peak, symmetric either side
        assertEquals(tr.at(0.75)[0], tr.at(1.25)[0], 1e-9);
        // between 0 and 1 the smooth curve is NOT the straight line (uniform Catmull-Rom with duplicated start point)
        assertNotEquals(5.0, tr.at(0.5)[0], 1e-6);
        assertEquals(Track.catmullRom(0.5, 0, 0, 10, 0), tr.at(0.5)[0], 1e-9);
    }

    @Test
    void emptyTrackIsTheChannelsNeutralValue() {
        assertArrayEquals(new double[]{1, 1, 1}, Track.empty(1).at(0.3), 1e-9);
        assertArrayEquals(new double[]{0, 0, 0}, Track.empty(0).at(0.3), 1e-9);
    }

    @Test
    void bezierWithoutHandlesEndsOnItsKeys() {
        Track tr = new Track(List.of(k(0, 0, Track.Mode.BEZIER), k(2, 4, Track.Mode.BEZIER)), 0);
        assertEquals(0, tr.at(0)[0], 1e-9);
        assertEquals(4, tr.at(2)[0], 1e-9);
        assertEquals(2, tr.at(1)[0], 1e-6);   // flat handles: symmetric S-curve through the middle
    }
}
