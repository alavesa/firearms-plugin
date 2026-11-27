package fi.alavesa.firearms;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Keeps vanilla's own first-person motion away from the gun. Two parts:
 * <ol>
 * <li><b>Pack side</b> ({@link #hideVanillaItem}): the stack in the player's hand only exists to carry the gun's data and to
 *     receive clicks - it must never be drawn by the client's hand renderer (which bobs it while walking, dips it on every
 *     stack change and swings it when turning). The display-rig gun already selects {@code minecraft:empty} for the two
 *     first-person contexts; on top of that every first-person (and, when asked, third-person) display transform of the
 *     gun model itself gets {@code "scale": [0, 0, 0]}, so whatever model the client would fall back to has no size.</li>
 * <li><b>Runtime side</b> ({@link BobEstimator}): the rig is a world entity, so the client's view bobbing moves it on the screen
 *     together with the world. The estimator mirrors the client's own bobbing state (walk distance and the "bob" amount,
 *     formulas read from the 26.3 client: {@code ClientAvatarState.updateBob}, {@code GameRenderer.bobView}) from the
 *     movement the server sees, predicts it a ping ahead, and hands the animator the inverse transform, so the gun stays
 *     still on the screen while the world bobs - the base our own procedural sway is laid on.</li>
 * </ol>
 */
final class VanillaSwaySuppressor {
    private VanillaSwaySuppressor() { }

    static final String[] FIRST_PERSON = {"firstperson_righthand", "firstperson_lefthand"};
    static final String[] THIRD_PERSON = {"thirdperson_righthand", "thirdperson_lefthand"};

    /**
     * Zero the item's own display transforms for the given contexts: {@code "scale": [0,0,0]} draws nothing, whatever the
     * client decides to show in the hand slot. Rotation and translation are kept (a zero scale makes them moot anyway).
     * Returns the number of contexts patched.
     */
    static int hideVanillaItem(JsonObject display, boolean firstPerson, boolean thirdPerson) {
        if (display == null) return 0;
        int n = 0;
        if (firstPerson) for (String ctx : FIRST_PERSON) { zero(display, ctx); n++; }
        if (thirdPerson) for (String ctx : THIRD_PERSON) { zero(display, ctx); n++; }
        return n;
    }

    private static void zero(JsonObject display, String ctx) {
        JsonObject t = display.has(ctx) && display.get(ctx).isJsonObject() ? display.getAsJsonObject(ctx) : new JsonObject();
        if (!t.has("rotation")) t.add("rotation", arr(0, 0, 0));
        if (!t.has("translation")) t.add("translation", arr(0, 0, 0));
        t.add("scale", arr(0, 0, 0));
        display.add(ctx, t);
    }

    /** True when a display transform has been zeroed by {@link #hideVanillaItem}. */
    static boolean isHidden(JsonObject display, String ctx) {
        if (display == null || !display.has(ctx)) return false;
        JsonObject t = display.getAsJsonObject(ctx);
        if (!t.has("scale")) return false;
        JsonArray s = t.getAsJsonArray("scale");
        for (int i = 0; i < s.size(); i++) if (Math.abs(s.get(i).getAsDouble()) > 1e-9) return false;
        return true;
    }

    private static JsonArray arr(double... v) { JsonArray a = new JsonArray(); for (double d : v) a.add(d); return a; }

    /**
     * The client's view-bobbing state, mirrored on the server from the movement it sees. The client (26.3,
     * {@code AbstractClientPlayer.aiStep -> ClientAvatarState.updateBob}) does per tick
     * <pre>
     *   f   = onGround && !dead && !swimming ? min(0.1, |horizontal velocity|) : 0
     *   bob += (f - bob) * 0.4
     *   walkDist += horizontal distance moved * walkFactor (0.6 in every version so far)
     * </pre>
     * and {@code GameRenderer.bobView} then applies, with g = -walkDist (interpolated) and b = bob:
     * <pre>
     *   translate( sin(g*PI) * b * 0.5,  -|cos(g*PI) * b|,  0 )
     *   rotate Z   sin(g*PI) * b * 3 degrees
     *   rotate X  |cos(g*PI - 0.2) * b| * 5 degrees
     * </pre>
     * to the whole level - the rig included. {@link #counter} is the inverse of that matrix, predicted {@code lead} ticks
     * ahead at the current pace (the server sees the player a round trip late).
     */
    static final class BobEstimator {
        float walkDist, walkDistO, bob, bobO, lastInput;
        double smoothedDist;

        /** One server tick: `dist` = horizontal blocks moved since the last tick (as the server saw it). */
        void tick(double dist, boolean onGround, boolean swimming, boolean dead, double walkFactor, double smoothing) {
            if (!(dist >= 0) || dist > 3) dist = 0;                      // teleport / NaN: no step
            smoothedDist = smoothing <= 0 ? dist : smoothedDist + (dist - smoothedDist) * (1 - Math.max(0, Math.min(0.95, smoothing)));
            walkDistO = walkDist;
            walkDist += (float) (smoothedDist * walkFactor);
            if (walkDist > 1e6f) { walkDist -= 1e6f; walkDistO -= 1e6f; }  // keep the float precise (the phase is periodic in 2)
            bobO = bob;
            lastInput = onGround && !swimming && !dead ? (float) Math.min(0.1, smoothedDist) : 0f;
            bob += (lastInput - bob) * 0.4f;
        }

        /** Walk distance and bob amount extrapolated `lead` ticks ahead at the current pace. */
        double[] predict(double lead) {
            double wd = walkDist + (walkDist - walkDistO) * Math.max(0, lead);
            double b = bob;
            for (int i = 0; i < (int) Math.round(Math.max(0, lead)); i++) b += (lastInput - b) * 0.4;
            return new double[]{wd, b};
        }

        /** The client's bobView matrix (view space) for the predicted state, with the amplitude scaled by `strength`. */
        Matrix4f bobView(double lead, double strength) {
            double[] s = predict(lead);
            double g = -s[0], b = s[1] * strength;
            if (Math.abs(b) < 1e-6) return new Matrix4f();
            double gp = g * Math.PI;
            float tx = (float) (Math.sin(gp) * b * 0.5), ty = (float) -Math.abs(Math.cos(gp) * b);
            float rz = (float) Math.toRadians(Math.sin(gp) * b * 3.0), rx = (float) Math.toRadians(Math.abs(Math.cos(gp - 0.2) * b) * 5.0);
            return new Matrix4f().translate(tx, ty, 0).rotateZ(rz).rotateX(rx);
        }

        /** The transform that cancels the predicted bobbing: inverse of {@link #bobView}. Identity when still. */
        Matrix4f counter(double lead, double strength) {
            Matrix4f m = bobView(lead, strength);
            return m.invert();
        }

        /** Is the player bobbing at all right now (anything worth sending)? */
        boolean active() { return bob > 1e-4f || lastInput > 1e-4f; }
    }

    /** Config helper: list of 3 doubles or the default. */
    static double[] list3(List<Double> l, double[] def) { return l != null && l.size() == 3 ? new double[]{l.get(0), l.get(1), l.get(2)} : def; }
}
