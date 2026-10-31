package fi.alavesa.firearms;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A Java element must draw a cube exactly where Blockbench rotates it, for every rotation an element can hold. */
class CubeMathTest {
    static final double[][] HOLDABLE = {
        {0, 0, 0}, {0, 22.5, 0}, {-45, 0, 0}, {0, 0, 45}, {90, 0, 0}, {0, -90, 0}, {180, 0, 0}, {90, 90, 0},
        {90, 22.5, 0}, {0, 90, 45}, {45, 90, 0}, {22.5, 0, 90}, {180, 22.5, 0}, {-90, 0, -22.5}, {0, 180, 22.5}, {90, 0, 45}
    };

    @Test
    void everyHoldableRotationIsDrawnExactly() {
        for (double[] r : HOLDABLE) assertTrue(CubeMath.maxError(r) < 1e-6, "rotation " + r[0] + "," + r[1] + "," + r[2] + " error " + CubeMath.maxError(r));
    }

    @Test
    void mixedRotationsNeedTheConjugatedAxis() {
        // [90, 22.5, 0]: the residual is a z rotation in the cube's own frame, but after the 90-degree x turn the
        // element must rotate about y. The old code wrote the residual's own axis - a corner lands pixels away.
        double[] r = {90, 22.5, 0};
        CubeMath.Element e = CubeMath.element(r);
        double[] q = CubeMath.euler3(CubeMath.mul3(CubeMath.transpose3(e.perm()), CubeMath.rot3(r)));
        int naiveAxis = 0;
        for (int i = 1; i < 3; i++) if (Math.abs(q[i]) > Math.abs(q[naiveAxis])) naiveAxis = i;
        assertNotEquals(naiveAxis, e.axis(), "this rotation is exactly the case where the axes differ");
        double[] v = {8, 8, 8};
        double[] naive = CubeMath.apply(new CubeMath.Element(e.perm(), naiveAxis, Math.round(q[naiveAxis] / 22.5) * 22.5), v);
        double[] truth = CubeMath.apply(e, v);
        double d = Math.hypot(Math.hypot(naive[0] - truth[0], naive[1] - truth[1]), naive[2] - truth[2]);
        assertTrue(d > 1.0, "the old axis choice moved the corner by only " + d);
    }

    @Test
    void freeRotationsAreReportedAsInexact() {
        assertFalse(CubeMath.exact(new double[]{0, 30, 0}));
        assertFalse(CubeMath.exact(new double[]{22.5, 22.5, 0}));
        assertTrue(CubeMath.exact(new double[]{0, 22.5, 0}));
        assertTrue(CubeMath.exact(new double[]{90, 45, 0}));
    }
}
