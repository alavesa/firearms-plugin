package fi.alavesa.firearms;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The hip-to-aim step blends part poses as rotation + scale + translation, never by averaging matrix entries. */
class PoseBlendTest {
    static float[] rotZ(double deg, float s, float tx) {
        float c = (float) Math.cos(Math.toRadians(deg)) * s, n = (float) Math.sin(Math.toRadians(deg)) * s;
        return new float[]{c, -n, 0, tx, n, c, 0, 0, 0, 0, s, 0};
    }

    @Test
    void endsAreExact() {
        float[] a = rotZ(0, 1, 0), b = rotZ(90, 2, 4);
        assertArrayEquals(a, DisplayRig.lerpPose(a, b, 0), 1e-5f);
        assertArrayEquals(b, DisplayRig.lerpPose(a, b, 1), 1e-5f);
    }

    @Test
    void halfwayIsARotationNotAShrink() {
        float[] mid = DisplayRig.lerpPose(rotZ(0, 1, 0), rotZ(90, 1, 0), 0.5f);
        assertArrayEquals(rotZ(45, 1, 0), mid, 1e-5f);   // averaging entries would give a 0.71-scaled matrix
    }

    @Test
    void mirroredPartsArePickedNotBlended() {
        float[] a = rotZ(0, 1, 0), b = rotZ(0, 1, 0);
        b[0] = -1;   // mirrored on x
        assertSame(a, DisplayRig.lerpPose(a, b, 0.4f));
        assertSame(b, DisplayRig.lerpPose(a, b, 0.6f));
    }
}
