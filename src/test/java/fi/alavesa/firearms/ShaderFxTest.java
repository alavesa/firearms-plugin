package fi.alavesa.firearms;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The recoil/sway curve and the signal tint shared between DisplayRig (hands, server) and shaders/item.vsh (GPU). */
class ShaderFxTest {
    /** What the vertex shader does with the dye: 0xA5 GG BB, BB >= 0xC0. */
    private static double[] decode(int rgb) {
        double r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        if (Math.abs(r - 165) > 0.6 || b < 191.5) return null;
        return new double[]{ Math.floor(g + 0.5), Math.max(0, Math.min(1, (Math.floor(b + 0.5) - 192) / 63.0)) };
    }

    @Test void signalRoundTrip() {
        for (long tick : new long[]{0, 1, 255, 256, 23999, 24000 + 7}) for (double st : new double[]{0, 0.15, 0.5, 1}) {
            double[] d = decode(DisplayRig.signalRgb(tick, st));
            assertNotNull(d);
            assertEquals(((tick % 256) + 256) % 256, d[0], 0.0);
            assertEquals(st, d[1], 1.0 / 63 + 1e-9);
        }
        assertNull(decode(0xFFFFFF));            // no dye = white = no signal
        assertNull(decode(0xA50000));            // right red, wrong blue
        assertNull(decode(0xC58C5E));            // the default skin tint
    }

    @Test void kickStartsAndEndsAtZero() {
        assertEquals(0, DisplayRig.kick(0, 1), 1e-12);
        assertEquals(0, DisplayRig.kick(DisplayRig.FX_RECOIL_TICKS, 1), 1e-12);
        assertEquals(0, DisplayRig.kick(3, 0), 1e-12);
        assertTrue(DisplayRig.kick(2, 1) > 0.5);
        assertTrue(DisplayRig.kick(2, 0.5) < DisplayRig.kick(2, 1));
    }

    @Test void idleMatrixIsNearlyIdentityAndKickPullsBack() {
        double[][] idle = DisplayRig.fxMatrix(1000, Long.MIN_VALUE, 0);
        for (int i = 0; i < 3; i++) assertEquals(1, idle[i][i], 0.001);
        assertEquals(0, idle[2][3], 1e-9);
        double[][] shot = DisplayRig.fxMatrix(1002, 1000, 1);
        assertTrue(shot[2][3] > 0.01, "the gun comes toward the camera (+z in view space)");
        assertTrue(shot[1][2] < -0.01, "and tips up");
        double[][] over = DisplayRig.fxMatrix(1000 + DisplayRig.FX_RECOIL_TICKS + 1, 1000, 1);
        assertEquals(0, over[2][3], 1e-9);
    }
}
