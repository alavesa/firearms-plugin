package fi.alavesa.firearms;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The hierarchical additive transform compositor of the display rig. Nothing here switches between states: every tick
 * each layer is weighted by its own smoothed weight and the layers are multiplied, so aiming, crouching, sprinting,
 * walking and a playing clip all act at the same time and blend into each other without a snap.
 * <pre>
 *   view = stabiliser * T(pivot + offset) * R(q) * T(-pivot) * hand(aimT) * part(keyframe)
 *          \_______/   \_________________________________/   \________/   \____________/
 *          counter       locomotion sway + sprint carry +     hip <-> aim  the bone's own
 *          view-bob      crouch cant, about the hand point     (eased)      clip matrix
 * </pre>
 * {@code hand(aimT)} is {@link DisplayRig}'s hip-to-aim chain, driven by the eased progress of this class
 * ({@link #aimT}); {@code part} comes from models/poses.bin and keeps playing (inspect, reload...) whatever the outer
 * layers do, which is why an inspect carries on while the gun glides onto the sights. Rotations are composed as
 * quaternions and blended with slerp, translations with lerp; weights move with a cubic ease-in-out over configurable
 * tick counts (config.yml {@code sway:} and per gun {@code sway:} in guns.yml).
 */
final class ComposableWeaponAnimator {
    /** Per-player layer state. All weights live in [0, 1]. */
    static final class State {
        double aimProgress;            // 0 = hip, 1 = sights, linear in time; the eased value is aimT()
        double cantProgress;           // crouch cant, linear in time
        double sprintW, walkW, runW;   // smoothed weights
        double walkPhase, breathPhase; // radians
        double speed;                  // smoothed horizontal blocks/tick
        Vector lastPos; int lastTick = -1;
        final VanillaSwaySuppressor.BobEstimator bob = new VanillaSwaySuppressor.BobEstimator();
        // the composed layer
        final Vector3f offset = new Vector3f();
        final Quaternionf rotation = new Quaternionf();
        final Matrix4f stabiliser = new Matrix4f();
        double lastDist;
    }

    /** What the rest of the plugin knows about the player this tick. */
    record Inputs(boolean aiming, boolean sneaking, boolean sprinting, boolean firedRecently, boolean clipPlaying, int pingMs) { }

    private final FirearmsPlugin plugin;
    private final Map<UUID, State> states = new HashMap<>();

    ComposableWeaponAnimator(FirearmsPlugin plugin) { this.plugin = plugin; }

    State state(Player p) { return states.computeIfAbsent(p.getUniqueId(), k -> new State()); }
    State peek(UUID id) { return states.get(id); }
    void forget(UUID id) { states.remove(id); }
    void clearAll() { states.clear(); }

    // ------------------------------------------------------------------ config

    /** A number from the gun's own sway: section when it has it, else config.yml sway.<key>, else the default. */
    double cfg(GunType gun, String key, double def) {
        ConfigurationSection s = gun == null ? null : gun.sway();
        if (s != null && s.contains(key)) return s.getDouble(key);
        return plugin.getConfig().getDouble("sway." + key, def);
    }
    boolean cfgBool(GunType gun, String key, boolean def) {
        ConfigurationSection s = gun == null ? null : gun.sway();
        if (s != null && s.contains(key)) return s.getBoolean(key);
        return plugin.getConfig().getBoolean("sway." + key, def);
    }
    double[] cfg3(GunType gun, String key, double[] def) {
        ConfigurationSection s = gun == null ? null : gun.sway();
        List<Double> l = s != null && s.contains(key) ? s.getDoubleList(key) : plugin.getConfig().getDoubleList("sway." + key);
        return l != null && l.size() == 3 ? new double[]{l.get(0), l.get(1), l.get(2)} : def;
    }

    /** Ticks a hip <-> sights transition takes: guns.yml aim-transition-time-ms / aim-transition-ticks (also under sway:),
     *  else config sway.aim-transition-*, else display.rig.aim-ticks. */
    int aimTicks(GunType gun) {
        ConfigurationSection s = gun == null ? null : gun.sway();
        if (s != null) {
            if (s.contains("aim-transition-time-ms")) return Math.max(1, (int) Math.round(s.getDouble("aim-transition-time-ms") / 50.0));
            if (s.contains("aim-transition-ticks")) return Math.max(1, s.getInt("aim-transition-ticks"));
        }
        var c = plugin.getConfig();
        if (c.contains("sway.aim-transition-time-ms")) return Math.max(1, (int) Math.round(c.getDouble("sway.aim-transition-time-ms") / 50.0));
        if (c.contains("sway.aim-transition-ticks")) return Math.max(1, c.getInt("sway.aim-transition-ticks"));
        return Math.max(1, c.getInt("display.rig.aim-ticks", 4));
    }

    boolean enabled() { return plugin.getConfig().getBoolean("sway.enabled", true); }

    // ------------------------------------------------------------------ maths

    /** Cubic ease-in-out on [0, 1]. */
    static double ease(double t) {
        t = Math.max(0, Math.min(1, t));
        return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    }
    private static double clamp01(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
    /** Move a linear progress toward its target by one tick of a `ticks`-long transition. */
    static double step(double progress, double target, int ticks) {
        double d = 1.0 / Math.max(1, ticks);
        return target > progress ? Math.min(target, progress + d) : Math.max(target, progress - d);
    }
    /** Exponential smoothing factor for a time constant in ticks. */
    static double smoothing(double tauTicks) { return tauTicks <= 0 ? 1 : 1 - Math.exp(-1.0 / tauTicks); }
    /** Rotation from XYZ Euler angles in degrees: the same product Rx * Ry * Rz as {@link DisplayRig#rotXYZ} (the display
     *  rotation convention the pack generator writes with). */
    static Quaternionf quatXYZ(double[] deg) {
        return new Quaternionf().rotateX((float) Math.toRadians(deg[0])).rotateY((float) Math.toRadians(deg[1])).rotateZ((float) Math.toRadians(deg[2]));
    }

    /** The eased hip (0) to sights (1) progress. */
    double aimT(State st) { return ease(st.aimProgress); }
    double aimT(Player p) { State st = states.get(p.getUniqueId()); return st == null ? 0 : aimT(st); }
    /** The eased crouch-cant weight, already faded out by aiming (the sights always win). */
    double cantT(State st) { return ease(st.cantProgress) * (1 - aimT(st)); }

    // ------------------------------------------------------------------ per tick

    /** Advance every layer one tick from what the server sees of the player. */
    void tick(Player p, GunType gun, State st, Inputs in) {
        int now = plugin.getServer().getCurrentTick();
        // ---- movement estimate: the server sees positions, not the client's velocity
        Vector pos = p.getLocation().toVector();
        double dist = 0;
        if (st.lastPos != null && st.lastTick == now - 1) {
            double dx = pos.getX() - st.lastPos.getX(), dz = pos.getZ() - st.lastPos.getZ();
            dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > 3) dist = 0;   // a teleport is not a step
        }
        st.lastPos = pos; st.lastTick = now; st.lastDist = dist;
        st.speed += (dist - st.speed) * 0.35;
        st.bob.tick(dist, p.isOnGround(), p.isSwimming(), p.isDead(), cfg(gun, "stabilizer.walk-distance-factor", 0.6), cfg(gun, "stabilizer.smoothing", 0.3));

        // ---- stance progress (time based, eased when read)
        st.aimProgress = step(st.aimProgress, in.aiming ? 1 : 0, aimTicks(gun));
        st.cantProgress = step(st.cantProgress, in.sneaking && cfgBool(gun, "cant.enabled", true) ? 1 : 0, (int) Math.round(cfg(gun, "cant.ticks", aimTicks(gun))));
        boolean carry = in.sprinting && !in.aiming && !in.firedRecently && !in.clipPlaying && cfgBool(gun, "sprint.enabled", true);
        st.sprintW += ((carry ? 1 : 0) - st.sprintW) * smoothing(cfg(gun, "sprint.ticks", 6) / 3.0);

        // ---- locomotion weights from the speed estimate
        double walkSpeed = Math.max(0.01, cfg(gun, "walk.speed", 0.12)), runSpeed = Math.max(walkSpeed + 0.01, cfg(gun, "run.speed", 0.24));
        double walkT = clamp01(st.speed / walkSpeed), runT = clamp01((st.speed - walkSpeed) / (runSpeed - walkSpeed));
        if (in.sprinting && st.speed > walkSpeed * 0.5) runT = Math.max(runT, 0.5);   // the server's own flag: a sprinter is running even when the position stream lags
        double k = smoothing(cfg(gun, "blend-ticks", 3));
        st.walkW += (walkT - st.walkW) * k;
        st.runW += (runT - st.runW) * k;

        // ---- phases: the walk phase is distance driven (vanilla's walkDist * pi), so it stays in step with the camera bob
        st.walkPhase += dist * cfg(gun, "walk.phase-per-block", 0.6 * Math.PI);
        if (st.walkPhase > 2 * Math.PI) st.walkPhase -= 2 * Math.PI;
        st.breathPhase += 2 * Math.PI / Math.max(8, cfg(gun, "idle.period-ticks", 64));
        if (st.breathPhase > 2 * Math.PI) st.breathPhase -= 2 * Math.PI;

        compose(gun, st, in);
    }

    /** Blend the layers into one offset + rotation (about the hand point) and the stabiliser matrix. */
    private void compose(GunType gun, State st, Inputs in) {
        Vector3f off = st.offset.zero();
        Quaternionf rot = st.rotation.identity();
        if (!enabled()) { st.stabiliser.identity(); return; }
        double aimT = aimT(st), cantE = cantT(st);
        double swayScale = (1 - aimT * (1 - cfg(gun, "aim-multiplier", 0.25))) * (1 - cantE * (1 - cfg(gun, "cant.sway-multiplier", 0.6)));
        double loco = Math.min(1, st.walkW + st.runW);

        // idle breathing: slow pitch / vertical drift, fading out as the player starts to move
        double idleA = (1 - loco) * swayScale * cfg(gun, "idle.scale", 1.0);
        if (idleA > 1e-4) {
            double[] ia = cfg3(gun, "idle.amplitude", new double[]{0.0015, 0.004, 0.0});
            off.add((float) (ia[0] * Math.sin(st.breathPhase * 0.5) * idleA), (float) (ia[1] * Math.sin(st.breathPhase) * idleA), (float) (ia[2] * Math.sin(st.breathPhase) * idleA));
            rot.rotateX((float) Math.toRadians(cfg(gun, "idle.pitch", 0.35) * Math.sin(st.breathPhase + 0.6) * idleA));
            rot.rotateZ((float) Math.toRadians(cfg(gun, "idle.roll", 0.15) * Math.sin(st.breathPhase * 0.5) * idleA));
        }
        // walking / running: a figure-8 (x = A cos t, y = B sin 2t) with roll on the lateral and pitch on the vertical beat
        double wA = st.walkW * (1 - st.runW) * swayScale * cfg(gun, "walk.scale", 1.0), rA = st.runW * swayScale * cfg(gun, "run.scale", 1.0);
        if (wA + rA > 1e-4) {
            double[] wa = cfg3(gun, "walk.amplitude", new double[]{0.012, 0.008, 0.0}), ra = cfg3(gun, "run.amplitude", new double[]{0.024, 0.016, 0.006});
            double c = Math.cos(st.walkPhase), s1 = Math.sin(st.walkPhase), s2 = Math.sin(2 * st.walkPhase);
            off.add((float) ((wa[0] * wA + ra[0] * rA) * c), (float) ((wa[1] * wA + ra[1] * rA) * s2), (float) ((wa[2] * wA + ra[2] * rA) * s2));
            rot.rotateZ((float) Math.toRadians((cfg(gun, "walk.roll", 1.2) * wA + cfg(gun, "run.roll", 2.5) * rA) * s1));
            rot.rotateX((float) Math.toRadians((cfg(gun, "walk.pitch", 0.8) * wA + cfg(gun, "run.pitch", 1.6) * rA) * s2));
        }
        // sprint carry: the gun pulled in and turned across the chest / muzzle up (per gun), eased by the sprint weight
        double sE = ease(st.sprintW);
        if (sE > 1e-4) {
            double[] so = cfg3(gun, "sprint.offset", new double[]{0.08, -0.10, 0.12}), sr = cfg3(gun, "sprint.rotation", new double[]{12, 28, 10});
            off.add((float) (so[0] * sE), (float) (so[1] * sE), (float) (so[2] * sE));
            rot.mul(new Quaternionf().slerp(quatXYZ(sr), (float) sE));
        }
        // crouch: the tactical side cant, a roll about the view axis at the hand point plus a shift inward
        if (cantE > 1e-4) {
            double[] co = cfg3(gun, "cant.offset", new double[]{-0.07, 0.02, 0.0});
            off.add((float) (co[0] * cantE), (float) (co[1] * cantE), (float) (co[2] * cantE));
            rot.mul(new Quaternionf().rotateZ((float) Math.toRadians(cfg(gun, "cant.degrees", 40) * cantE)));
        }
        // the counter view-bob: the client's predicted bobView, inverted
        if (cfgBool(gun, "stabilizer.enabled", true) && st.bob.active()) {
            double lead = cfg(gun, "stabilizer.lead-ticks", -1);
            if (lead < 0) lead = 1 + Math.min(10, in.pingMs() / 50.0);
            st.stabiliser.set(st.bob.counter(lead, Math.max(0, Math.min(1.5, cfg(gun, "stabilizer.strength", 1.0)))));
        } else st.stabiliser.identity();
    }

    // ------------------------------------------------------------------ outputs

    /** The composed layer in view space (+X right, +Y up, +Z toward the camera): rotation and offset about `pivot`, with
     *  the view-bob counter in front when `withStabiliser`. Identity for a player without state. */
    Matrix4f layer(State st, Vector3f pivot, boolean withStabiliser) {
        Matrix4f m = new Matrix4f();
        if (st == null) return m;
        m.translate(pivot.x + st.offset.x, pivot.y + st.offset.y, pivot.z + st.offset.z).rotate(st.rotation).translate(-pivot.x, -pivot.y, -pivot.z);
        return withStabiliser ? new Matrix4f(st.stabiliser).mul(m) : m;
    }

    /** A view-space point (right, up, back) carried through the sway + cant + sprint layers (not the stabiliser: it
     *  cancels the camera's motion, which the world around the point undergoes too). The muzzle for tracers and flashes. */
    Vector3f carry(State st, Vector3f pivot, Vector3f viewPoint) {
        if (st == null) return new Vector3f(viewPoint);
        return layer(st, pivot, false).transformPosition(new Vector3f(viewPoint));
    }

    /** Is every layer at rest (nothing worth a send)? */
    boolean resting(State st) {
        return st.offset.lengthSquared() < 1e-10 && Math.abs(st.rotation.w) > 0.9999999 && st.stabiliser.equals(new Matrix4f(), 1e-7f);
    }
}
