package fi.alavesa.firearms;

/**
 * How a Blockbench cube rotation becomes a Java item-model element. A Java element can only be rotated about ONE
 * axis by a multiple of 22.5 degrees within -45..45, so a cube rotation R (Blockbench, ZYX) is split into a signed
 * axis permutation P (multiples of 90 degrees, applied to the box corners and the faces) and a residual rotation
 * Q = P^T R that is written as the element's rotation. Because the element rotation is applied AFTER the permuted
 * box is laid out, it must be the residual CONJUGATED by P: the element rotates about the axis P·e by the same
 * angle, so that R_element · P = P · Q = R. Writing the residual's own axis unconjugated turned every cube whose
 * 90-degree part moved that axis - the "floating / twisted parts" of mixed rotations like [90, 22.5, 0].
 */
public final class CubeMath {
    private CubeMath() { }

    /** The element: permutation P, element rotation axis (0 x, 1 y, 2 z) and angle (degrees, 22.5 steps, |a| <= 45). */
    public record Element(int[][] perm, int axis, double angle) { }

    public static Element element(double[] rotationDeg) {
        double[][] Re = rot3(rotationDeg);
        int[][] Pe = nearestPerm(Re);
        double[] q = euler3(mul3(transpose3(Pe), Re));         // residual in the cube's own frame
        int ca = 0;
        for (int i = 1; i < 3; i++) if (Math.abs(q[i]) > Math.abs(q[ca])) ca = i;
        double cs = Math.max(-45, Math.min(45, Math.round(q[ca] / 22.5) * 22.5));
        // conjugate by P: the residual's axis e_ca lands on P·e_ca (a signed unit axis)
        int axis = ca, sign = 1;
        for (int j = 0; j < 3; j++) if (Pe[j][ca] != 0) { axis = j; sign = Pe[j][ca]; }
        return new Element(Pe, axis, sign * cs);
    }

    /** The element without any residual (a cube whose exact rotation lives elsewhere, e.g. in a display transform). */
    public static Element plain() { return new Element(identityPerm(), 0, 0); }

    /** Where the element puts a point given relative to the cube's origin: R_element · P · v. */
    public static double[] apply(Element e, double[] v) {
        double[] p = { e.perm()[0][0]*v[0]+e.perm()[0][1]*v[1]+e.perm()[0][2]*v[2], e.perm()[1][0]*v[0]+e.perm()[1][1]*v[1]+e.perm()[1][2]*v[2], e.perm()[2][0]*v[0]+e.perm()[2][1]*v[1]+e.perm()[2][2]*v[2] };
        if (e.angle() == 0) return p;
        double[] deg = {0, 0, 0}; deg[e.axis()] = e.angle();
        double[][] R = rot3(deg);
        return new double[]{ R[0][0]*p[0]+R[0][1]*p[1]+R[0][2]*p[2], R[1][0]*p[0]+R[1][1]*p[1]+R[1][2]*p[2], R[2][0]*p[0]+R[2][1]*p[1]+R[2][2]*p[2] };
    }

    /** Largest corner error (model units) between the true rotation and what the element draws, for a unit box. */
    public static double maxError(double[] rotationDeg) {
        Element e = element(rotationDeg);
        double[][] R = rot3(rotationDeg);
        double worst = 0;
        for (int c = 0; c < 8; c++) {
            double[] v = { (c & 1) == 0 ? -8 : 8, (c & 2) == 0 ? -8 : 8, (c & 4) == 0 ? -8 : 8 };
            double[] truth = { R[0][0]*v[0]+R[0][1]*v[1]+R[0][2]*v[2], R[1][0]*v[0]+R[1][1]*v[1]+R[1][2]*v[2], R[2][0]*v[0]+R[2][1]*v[1]+R[2][2]*v[2] };
            double[] got = apply(e, v);
            for (int i = 0; i < 3; i++) worst = Math.max(worst, Math.abs(truth[i] - got[i]));
        }
        return worst;
    }

    /** Can a Java element hold this rotation exactly (after the 90-degree split, one axis at a 22.5 step)? */
    public static boolean exact(double[] rotationDeg) { return maxError(rotationDeg) < 0.05; }

    // ---- matrices (3x3, row-major; rotations ZYX like Blockbench)
    public static double[][] rot3(double[] deg) {
        double x = Math.toRadians(deg[0]), y = Math.toRadians(deg[1]), z = Math.toRadians(deg[2]);
        double[][] rx = {{1,0,0},{0,Math.cos(x),-Math.sin(x)},{0,Math.sin(x),Math.cos(x)}};
        double[][] ry = {{Math.cos(y),0,Math.sin(y)},{0,1,0},{-Math.sin(y),0,Math.cos(y)}};
        double[][] rz = {{Math.cos(z),-Math.sin(z),0},{Math.sin(z),Math.cos(z),0},{0,0,1}};
        return mul3(mul3(rz, ry), rx);
    }
    public static double[][] mul3(double[][] a, double[][] b) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) { double x = 0; for (int k = 0; k < 3; k++) x += a[i][k] * b[k][j]; r[i][j] = x; }
        return r;
    }
    public static double[][] transpose3(int[][] p) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) r[i][j] = p[j][i];
        return r;
    }
    public static int[][] identityPerm() { return new int[][]{{1,0,0},{0,1,0},{0,0,1}}; }
    /** The signed axis permutation (multiples of 90 degrees) nearest to R; identity when R is not near one. */
    public static int[][] nearestPerm(double[][] R) {
        int[][] P = new int[3][3];
        boolean[] usedCol = new boolean[3];
        for (int i = 0; i < 3; i++) {
            int bj = 0;
            for (int j = 1; j < 3; j++) if (Math.abs(R[i][j]) > Math.abs(R[i][bj])) bj = j;
            if (usedCol[bj] || Math.abs(R[i][bj]) < 0.5) return identityPerm();
            usedCol[bj] = true;
            P[i][bj] = R[i][bj] >= 0 ? 1 : -1;
        }
        int det = P[0][0]*(P[1][1]*P[2][2]-P[1][2]*P[2][1]) - P[0][1]*(P[1][0]*P[2][2]-P[1][2]*P[2][0]) + P[0][2]*(P[1][0]*P[2][1]-P[1][1]*P[2][0]);
        return det == 1 ? P : identityPerm();
    }
    /** ZYX Euler angles (degrees) of a rotation matrix. */
    public static double[] euler3(double[][] m) {
        double y = Math.asin(Math.max(-1, Math.min(1, -m[2][0])));
        double x = Math.atan2(m[2][1], m[2][2]);
        double z = Math.atan2(m[1][0], m[0][0]);
        return new double[]{ Math.toDegrees(x), Math.toDegrees(y), Math.toDegrees(z) };
    }
}
