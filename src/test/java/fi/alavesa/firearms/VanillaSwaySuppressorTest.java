package fi.alavesa.firearms;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The vanilla-motion suppressor: the zero-scale pack patch and the mirrored client view-bob. */
class VanillaSwaySuppressorTest {

    @Test
    void firstPersonTransformsGetZeroScaleAndKeepTheirRotation() {
        JsonObject d = JsonParser.parseString("{\"firstperson_righthand\":{\"rotation\":[0,-10,0],\"translation\":[1,2,3],\"scale\":[0.5,0.5,0.5]},\"gui\":{\"scale\":[0.6,0.6,0.6]}}").getAsJsonObject();
        assertEquals(2, VanillaSwaySuppressor.hideVanillaItem(d, true, false));
        assertTrue(VanillaSwaySuppressor.isHidden(d, "firstperson_righthand"));
        assertTrue(VanillaSwaySuppressor.isHidden(d, "firstperson_lefthand"), "a missing hand context is added, hidden");
        assertEquals(-10, d.getAsJsonObject("firstperson_righthand").getAsJsonArray("rotation").get(1).getAsDouble(), 1e-9);
        assertFalse(VanillaSwaySuppressor.isHidden(d, "gui"), "other contexts untouched");
        assertFalse(d.has("thirdperson_righthand"), "third person only when asked");
        assertEquals(4, VanillaSwaySuppressor.hideVanillaItem(d, true, true));
        assertTrue(VanillaSwaySuppressor.isHidden(d, "thirdperson_lefthand"));
        assertEquals(0, VanillaSwaySuppressor.hideVanillaItem(null, true, true));
    }

    @Test
    void bobBuildsUpToTheClientsCapWhileWalkingAndDecaysWhenStill() {
        VanillaSwaySuppressor.BobEstimator b = new VanillaSwaySuppressor.BobEstimator();
        for (int i = 0; i < 40; i++) b.tick(0.216, true, false, false, 0.6, 0);   // vanilla walking speed
        assertEquals(0.1, b.bob, 1e-3, "bob += (min(0.1, speed) - bob) * 0.4 converges on the 0.1 cap");
        assertEquals(40 * 0.216 * 0.6, b.walkDist, 1e-3);
        assertTrue(b.active());
        for (int i = 0; i < 40; i++) b.tick(0, true, false, false, 0.6, 0);
        assertTrue(b.bob < 1e-4);
        assertFalse(b.active());
        // airborne: no bob input even when moving
        VanillaSwaySuppressor.BobEstimator air = new VanillaSwaySuppressor.BobEstimator();
        for (int i = 0; i < 10; i++) air.tick(0.3, false, false, false, 0.6, 0);
        assertEquals(0, air.bob, 1e-9);
    }

    @Test
    void counterCancelsThePredictedBobExactly() {
        VanillaSwaySuppressor.BobEstimator b = new VanillaSwaySuppressor.BobEstimator();
        for (int i = 0; i < 13; i++) b.tick(0.2, true, false, false, 0.6, 0);
        Matrix4f bob = b.bobView(2, 1.0), counter = b.counter(2, 1.0);
        Matrix4f id = new Matrix4f(bob).mul(counter);
        assertTrue(id.equals(new Matrix4f(), 1e-5f), "bob * counter = identity: " + id);
        // the bob really moves things (the test is not vacuous)
        Vector3f p = bob.transformPosition(new Vector3f(0, 0, -1));
        assertTrue(Math.abs(p.x) + Math.abs(p.y + 0) > 1e-3 || Math.abs(p.z + 1) > 1e-4);
        // half strength = half the amplitude of the translation
        Matrix4f half = b.bobView(2, 0.5);
        assertEquals(bob.m30() / 2, half.m30(), 1e-4);
    }

    @Test
    void bobViewFollowsTheClientFormula() {
        VanillaSwaySuppressor.BobEstimator b = new VanillaSwaySuppressor.BobEstimator();
        b.walkDist = 0.25f; b.walkDistO = 0.25f; b.bob = 0.1f; b.bobO = 0.1f; b.lastInput = 0.1f;
        Matrix4f m = b.bobView(0, 1.0);
        double g = -0.25, gp = g * Math.PI, h = 0.1;
        assertEquals(Math.sin(gp) * h * 0.5, m.m30(), 1e-6, "x = sin(g*pi) * bob * 0.5");
        assertEquals(-Math.abs(Math.cos(gp) * h), m.m31(), 1e-6, "y = -|cos(g*pi) * bob|");
        // the rotation part: Rz(sin(gp)*h*3 deg) then Rx(|cos(gp - 0.2)*h| * 5 deg)
        Matrix4f rot = new Matrix4f().rotateZ((float) Math.toRadians(Math.sin(gp) * h * 3)).rotateX((float) Math.toRadians(Math.abs(Math.cos(gp - 0.2) * h) * 5));
        for (int c = 0; c < 3; c++) for (int r = 0; r < 3; r++) assertEquals(rot.get(c, r), m.get(c, r), 1e-6);
    }

    @Test
    void predictionExtrapolatesTheWalkDistanceAtTheCurrentPace() {
        VanillaSwaySuppressor.BobEstimator b = new VanillaSwaySuppressor.BobEstimator();
        for (int i = 0; i < 5; i++) b.tick(0.2, true, false, false, 0.6, 0);
        double[] now = b.predict(0), ahead = b.predict(3);
        assertEquals(now[0] + 3 * 0.2 * 0.6, ahead[0], 1e-6);
        assertTrue(ahead[1] > now[1], "the bob keeps rising toward the cap");
    }
}
