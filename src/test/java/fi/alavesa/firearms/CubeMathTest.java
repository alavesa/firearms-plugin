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

    @Test
    void plainElementAcceptsOnlyOneAxisAt22Point5Steps() {
        assertTrue(CubeMath.plainElement(new double[]{0, 0, 0}));
        assertTrue(CubeMath.plainElement(new double[]{0, -22.5, 0}));
        assertTrue(CubeMath.plainElement(new double[]{45, 0, 0}));
        assertFalse(CubeMath.plainElement(new double[]{90, 0, 0}));      // 90: a permutation, not an element rotation
        assertFalse(CubeMath.plainElement(new double[]{0, 0, 30}));
        assertFalse(CubeMath.plainElement(new double[]{22.5, 22.5, 0}));  // two axes
        assertFalse(CubeMath.plainElement(new double[]{7, 0, 0}));
    }

    @Test
    void shiftForPivotPutsACubeWhereItsOwnRotationWould() {
        double[] rot = {18, 0, -30};
        double[] origin = {3, 9, -4}, pivot = {-2, 7, 1};
        double[] sh = CubeMath.shiftForPivot(rot, origin, pivot);
        double[][] R = CubeMath.rot3(rot);
        double[] corner = {4.5, 10, -2};
        // truth: rotate the corner about the cube's own origin
        double[] d = {corner[0] - origin[0], corner[1] - origin[1], corner[2] - origin[2]};
        double[] truth = new double[3];
        for (int i = 0; i < 3; i++) truth[i] = R[i][0]*d[0] + R[i][1]*d[1] + R[i][2]*d[2] + origin[i];
        // emitted: the shifted corner rotated about the part pivot
        double[] u = {corner[0] + sh[0] - pivot[0], corner[1] + sh[1] - pivot[1], corner[2] + sh[2] - pivot[2]};
        double[] got = new double[3];
        for (int i = 0; i < 3; i++) got[i] = R[i][0]*u[0] + R[i][1]*u[1] + R[i][2]*u[2] + pivot[i];
        assertArrayEquals(truth, got, 1e-9);
        assertArrayEquals(new double[]{0, 0, 0}, CubeMath.shiftForPivot(rot, origin, origin), 1e-12);
    }
}
