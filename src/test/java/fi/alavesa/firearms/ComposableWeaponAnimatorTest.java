package fi.alavesa.firearms;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The pure maths of the compositor: easing, stepping, quaternion composition and the layer about the hand point. */
class ComposableWeaponAnimatorTest {

    @Test
    void easeIsCubicInOutWithExactEnds() {
        assertEquals(0, ComposableWeaponAnimator.ease(0), 1e-12);
        assertEquals(1, ComposableWeaponAnimator.ease(1), 1e-12);
        assertEquals(0.5, ComposableWeaponAnimator.ease(0.5), 1e-12);
        assertEquals(4 * 0.25 * 0.25 * 0.25, ComposableWeaponAnimator.ease(0.25), 1e-12);
        assertTrue(ComposableWeaponAnimator.ease(0.1) < 0.1, "slow start");
        assertTrue(ComposableWeaponAnimator.ease(0.9) > 0.9, "slow end");
        assertEquals(0, ComposableWeaponAnimator.ease(-3), 1e-12);
        assertEquals(1, ComposableWeaponAnimator.ease(7), 1e-12);
    }

    @Test
    void stepReachesTheTargetInExactlyTheConfiguredTicksAndNeverOvershoots() {
        double p = 0;
        int n = 0;
        while (p < 1) { p = ComposableWeaponAnimator.step(p, 1, 4); n++; assertTrue(p <= 1); }
        assertEquals(4, n);
        while (p > 0) { p = ComposableWeaponAnimator.step(p, 0, 4); n++; assertTrue(p >= 0); }
        assertEquals(8, n);
        // a transition reversed half way goes back from where it is, no snap
        p = ComposableWeaponAnimator.step(ComposableWeaponAnimator.step(0, 1, 4), 1, 4);
        assertEquals(0.5, p, 1e-12);
        assertEquals(0.25, ComposableWeaponAnimator.step(p, 0, 4), 1e-12);
    }

    @Test
    void smoothingFactorIsInUnitRange() {
        assertEquals(1, ComposableWeaponAnimator.smoothing(0), 1e-12);
        double k = ComposableWeaponAnimator.smoothing(3);
        assertTrue(k > 0.28 && k < 0.29, "1 - e^(-1/3) = 0.2835: " + k);
    }

    @Test
    void quatXYZMatchesTheDisplayRotationOrder() {
        // X then Y then Z, as rotXYZ in DisplayRig / the pack generator: compare on a probe vector
        double[] deg = {20, -35, 50};
        Quaternionf q = ComposableWeaponAnimator.quatXYZ(deg);
        double[][] m = DisplayRig.rotXYZ(deg);
        Vector3f v = new Vector3f(0.3f, -0.7f, 0.2f);
        Vector3f byQ = q.transform(new Vector3f(v));
        double[] byM = { m[0][0]*v.x + m[0][1]*v.y + m[0][2]*v.z, m[1][0]*v.x + m[1][1]*v.y + m[1][2]*v.z, m[2][0]*v.x + m[2][1]*v.y + m[2][2]*v.z };
        assertEquals(byM[0], byQ.x, 1e-5); assertEquals(byM[1], byQ.y, 1e-5); assertEquals(byM[2], byQ.z, 1e-5);
    }

    @Test
    void layerTurnsAboutThePivotNotTheCamera() {
        ComposableWeaponAnimator.State st = new ComposableWeaponAnimator.State();
        st.rotation.rotateZ((float) Math.toRadians(40));          // the crouch cant
        Vector3f pivot = new Vector3f(0.56f, -0.52f, -0.72f);        // the hand point
        ComposableWeaponAnimator anim = new ComposableWeaponAnimator(null);
        Matrix4f L = anim.layer(st, pivot, false);
        // the pivot itself does not move
        Vector3f p = L.transformPosition(new Vector3f(pivot));
        assertEquals(pivot.x, p.x, 1e-5); assertEquals(pivot.y, p.y, 1e-5); assertEquals(pivot.z, p.z, 1e-5);
        // a point one block to the right of the pivot rolls up and left: + about Z carries +X toward +Y
        Vector3f r = L.transformPosition(new Vector3f(pivot).add(1, 0, 0));
        assertEquals(pivot.x + Math.cos(Math.toRadians(40)), r.x, 1e-5);
        assertEquals(pivot.y + Math.sin(Math.toRadians(40)), r.y, 1e-5);
        // the muzzle (ahead of the pivot along -Z) stays ahead: a roll about the view axis does not move the aim point
        Vector3f mz = anim.carry(st, pivot, new Vector3f(pivot).add(0, 0, -1));
        assertEquals(pivot.x, mz.x, 1e-5); assertEquals(pivot.y, mz.y, 1e-5); assertEquals(pivot.z - 1, mz.z, 1e-5);
    }

    @Test
    void offsetAddsAfterTheRotationAndRestingDetectsIdentity() {
        ComposableWeaponAnimator anim = new ComposableWeaponAnimator(null);
        ComposableWeaponAnimator.State st = new ComposableWeaponAnimator.State();
        assertTrue(anim.resting(st));
        st.offset.set(0.01f, -0.02f, 0.03f);
        assertFalse(anim.resting(st));
        Vector3f pivot = new Vector3f(1, 2, 3);
        Vector3f p = anim.layer(st, pivot, false).transformPosition(new Vector3f(pivot));
        assertEquals(1.01, p.x, 1e-5); assertEquals(1.98, p.y, 1e-5); assertEquals(3.03, p.z, 1e-5);
        // the stabiliser is applied about the camera, in front of everything
        st.offset.zero();
        st.stabiliser.translation(0, 0.1f, 0);
        assertFalse(anim.resting(st));
        Vector3f q = anim.layer(st, pivot, true).transformPosition(new Vector3f(pivot));
        assertEquals(2.1, q.y, 1e-5);
        Vector3f noStab = anim.carry(st, pivot, new Vector3f(pivot));
        assertEquals(2.0, noStab.y, 1e-5);
    }

    @Test
    void sprintCarryBlendsBySlerpBetweenIdentityAndThePose() {
        Quaternionf full = ComposableWeaponAnimator.quatXYZ(new double[]{12, 28, 10});
        Quaternionf half = new Quaternionf().slerp(full, 0.5f);
        assertEquals(full.angle() / 2, half.angle(), 1e-4, "half the weight = half the angle about the same axis");
        Quaternionf none = new Quaternionf().slerp(full, 0f);
        assertEquals(1, Math.abs(none.w), 1e-6);
    }
}
