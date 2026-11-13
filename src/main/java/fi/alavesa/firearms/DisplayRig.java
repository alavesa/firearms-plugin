package fi.alavesa.firearms;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.DataInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The first-person gun as a rig of client-side item_display entities, one per part, riding the player and
 * billboarded to the camera, so the client places them in front of the eyes itself every frame and blends each
 * pose change over a tick: the animation is as smooth as the client's frame rate, and the pack carries only the
 * part geometry (no frames). Poses come from models/poses.bin, written by /firearms pack for guns in display mode.
 * <p>
 * Maths (verified in the 26.3 client): a CENTER-billboarded display's local frame is the camera frame (+Z
 * forward, +X left, +Y up); the item display renderer then turns the item 180 degrees about Y and draws the
 * item model centred. Vanilla draws a first-person item at view-space (0.56, -0.52, -0.72) after the item's own
 * firstperson_righthand transform, with the model centred at (8,8,8). The rig reproduces exactly that chain, and
 * corrects for the entity riding at the player's passenger point (0.18 blocks above the eyes when standing).
 */
final class DisplayRig {
    static final class ModelPoses {
        String[] partCmd; int[] partVariant; double[] partK; double[][] partPivot;
        double[] fpRotation, fpTranslation, fpScale;
        float[][] rest;
        final Map<String, List<float[][]>> clips = new LinkedHashMap<>();
    }
    private static final class Active {
        String model; ModelPoses poses; GunType gun;
        List<NmsDisplay.Part> parts = new ArrayList<>();   // index = part index, null = not shown for this player (other hand variant)
        float[][] current;                                 // the pose being shown
        double[][][] lastSent;                             // per part: last transform sent (null = hidden)
        String clip; int frame = -1; double stretch = 1; int startTick; Runnable then;
        boolean aiming, spawned;
        boolean shownAiming;                               // the state the client was last sent (H = the player's hip pose, A = the fixed aim pose)
        double lastPitch = 1e9; boolean lastSneak;
        double scale = -1;                                 // the rig scale last sent (display.rig.scale .. scale-open, by the wall check)
    }

    private final FirearmsPlugin plugin;
    private final Registry registry;
    private final Map<String, ModelPoses> models = new HashMap<>();
    private final Map<UUID, Active> active = new HashMap<>();

    DisplayRig(FirearmsPlugin plugin, Registry registry) { this.plugin = plugin; this.registry = registry; }

    /** Read models/poses.bin (after /firearms pack and on enable). */
    void load() {
        models.clear();
        File f = new File(new File(plugin.getDataFolder(), "models"), "poses.bin");
        if (!f.isFile()) return;
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(f), 1 << 16))) {
            if (!in.readUTF().equals("FPOSES1")) throw new java.io.IOException("unknown poses.bin version");
            int n = in.readInt();
            for (int m = 0; m < n; m++) {
                ModelPoses mp = new ModelPoses();
                String name = in.readUTF();
                int parts = in.readInt();
                mp.partCmd = new String[parts]; mp.partVariant = new int[parts]; mp.partK = new double[parts]; mp.partPivot = new double[parts][3];
                for (int i = 0; i < parts; i++) { mp.partCmd[i] = in.readUTF(); mp.partVariant[i] = in.readByte(); mp.partK[i] = in.readDouble(); for (int a = 0; a < 3; a++) mp.partPivot[i][a] = in.readDouble(); }
                mp.fpRotation = new double[3]; mp.fpTranslation = new double[3]; mp.fpScale = new double[3];
                for (int a = 0; a < 3; a++) mp.fpRotation[a] = in.readDouble(); for (int a = 0; a < 3; a++) mp.fpTranslation[a] = in.readDouble(); for (int a = 0; a < 3; a++) mp.fpScale[a] = in.readDouble();
                mp.rest = readFrame(in, parts);
                int clips = in.readInt();
                for (int c = 0; c < clips; c++) { String key = in.readUTF(); int frames = in.readInt(); List<float[][]> list = new ArrayList<>(frames); for (int fr = 0; fr < frames; fr++) list.add(readFrame(in, parts)); mp.clips.put(key, list); }
                models.put(name, mp);
            }
            plugin.getLogger().info("Display rig: poses for " + models.keySet());
        } catch (Exception e) { plugin.getLogger().warning("Display rig: could not read models/poses.bin: " + e); }
    }
    private static float[][] readFrame(DataInputStream in, int parts) throws java.io.IOException {
        float[][] out = new float[parts][];
        for (int i = 0; i < parts; i++) if (in.readByte() != 0) { float[] m = new float[12]; for (int k = 0; k < 12; k++) m[k] = in.readFloat(); out[i] = m; }
        return out;
    }

    private static int countNull(float[][] f) { int n = 0; for (float[] x : f) if (x == null) n++; return n; }
    boolean has(String model) { return models.containsKey(model); }
    boolean showing(Player p) { return active.containsKey(p.getUniqueId()); }
    /** Parts spawned for this player's rig (0 = no rig). */
    int partCount(Player p) { Active a = active.get(p.getUniqueId()); if (a == null) return 0; int n = 0; for (NmsDisplay.Part pt : a.parts) if (pt != null) n++; return n; }
    java.util.Set<String> loadedModels() { return models.keySet(); }

    /** Build the rig for this player's gun (called when the gun is selected). */
    void show(Player p, GunType gun, ItemStack held) {
        hide(p);
        ModelPoses mp = models.get(gun.model());
        if (mp == null || !NmsDisplay.available()) return;
        Active a = new Active();
        a.model = gun.model(); a.poses = mp; a.gun = gun;
        boolean slim = ArmSkin.slim(p);
        boolean hasSlim = false; for (int v : mp.partVariant) if (v == 2) hasSlim = true;
        float range = (float) plugin.getConfig().getDouble("display.rig.view-range", 1.0);
        try {
            for (int i = 0; i < mp.partCmd.length; i++) {
                int v = mp.partVariant[i];
                boolean show = v == 0 || v == 3 || v == 4 || (v == 1 && !(slim && hasSlim)) || (v == 2 && slim && hasSlim);
                if (!show) { a.parts.add(null); continue; }
                ItemStack stack = held.clone();
                stack.setAmount(1);
                registry.setModel(stack, mp.partCmd[i]);
                a.parts.add(NmsDisplay.create(p, stack, range));
            }
            a.current = mp.rest;
            a.lastSent = new double[mp.partCmd.length][][];
            List<NmsDisplay.Part> real = new ArrayList<>(); for (NmsDisplay.Part pt : a.parts) if (pt != null) real.add(pt);
            boolean staticTest = plugin.getConfig().getBoolean("display.rig.static-test", false);
            if (staticTest) {
                // DEBUG: every part parked 2 blocks ahead of the eyes, identity transform, not riding - "do the entities show at all?"
                var eye = p.getEyeLocation(); var at = eye.clone().add(eye.getDirection().multiply(2));
                for (NmsDisplay.Part pt : real) { NmsDisplay.moveTo(pt, at.getX(), at.getY(), at.getZ()); NmsDisplay.transform(pt, identity(), 0); }
                plugin.getLogger().info("Display rig STATIC TEST for " + p.getName() + ": " + real.size() + " part(s) at " + at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ());
            } else send(p, a, 0, true);
            for (NmsDisplay.Part pt : real) NmsDisplay.spawn(p, pt);
            if (!staticTest) NmsDisplay.ride(p, real);
            a.spawned = true;
            if (staticTest) a.lastPitch = -1e9;   // never re-sent
            active.put(p.getUniqueId(), a);
            if (plugin.getConfig().getBoolean("display.rig.debug", false)) plugin.getLogger().info("Display rig for " + p.getName() + ": " + real.size() + " part(s), ids " + NmsDisplay.ids(real).subList(0, Math.min(5, real.size())) + "..., hidden in rest pose: " + countNull(mp.rest));
        } catch (Exception e) {
            plugin.getLogger().warning("Display rig for " + p.getName() + " failed: " + e);
            try { List<NmsDisplay.Part> real = new ArrayList<>(); for (NmsDisplay.Part pt : a.parts) if (pt != null) real.add(pt); NmsDisplay.remove(p, real); } catch (Exception ignored) { }
        }
    }

    void hide(Player p) {
        Active a = active.remove(p.getUniqueId());
        if (a == null) return;
        try { List<NmsDisplay.Part> real = new ArrayList<>(); for (NmsDisplay.Part pt : a.parts) if (pt != null) real.add(pt); NmsDisplay.remove(p, real); } catch (Exception ignored) { }
    }

    /** Play a clip on the rig: one pose per tick from the file, stretched to totalTicks when > 0; `then` runs at the end. */
    void play(Player p, String clip, int totalTicks, Runnable then) {
        Active a = active.get(p.getUniqueId());
        if (a == null) { if (then != null) then.run(); return; }
        List<float[][]> frames = a.poses.clips.get(clip);
        if (frames == null || frames.isEmpty()) { if (then != null) then.run(); return; }
        a.clip = clip; a.frame = 0; a.startTick = plugin.getServer().getCurrentTick();
        a.stretch = totalTicks > 0 ? (double) totalTicks / Math.max(1, frames.size() - 1) : 1.0;
        a.then = then;
        a.current = frames.get(0);
        send(p, a, 1, false);
    }

    /** Re-send every part with the current settings (after /viewmodel). */
    void refresh(Player p) {
        Active a = active.get(p.getUniqueId());
        if (a == null) return;
        GunType fresh = registry.gun(a.gun.id());   // a reload (aim-pose edit) made new GunType objects: read the new values
        if (fresh != null) a.gun = fresh;
        send(p, a, 2, true);
    }

    void stopClip(Player p) { Active a = active.get(p.getUniqueId()); if (a != null) { a.clip = null; a.then = null; } }

    void aim(Player p, boolean on) { Active a = active.get(p.getUniqueId()); if (a != null) a.aiming = on; }
    boolean playing(Player p) { Active a = active.get(p.getUniqueId()); return a != null && a.clip != null; }

    /** Every tick: advance clips, then send what changed (also when the pitch moved: the rig rides above the eyes). */
    void tick() {
        int now = plugin.getServer().getCurrentTick();
        for (var e : new ArrayList<>(active.entrySet())) {
            Player p = plugin.getServer().getPlayer(e.getKey());
            Active a = e.getValue();
            if (p == null || !p.isOnline()) { active.remove(e.getKey()); continue; }
            GunType heldNow = registry.gunOf(p.getInventory().getItemInMainHand());
            if (heldNow == null || !heldNow.id().equals(a.gun.id())) { hide(p); continue; }   // by id: a registry reload makes new GunType objects
            int duration = 1;
            if (a.clip != null) {
                List<float[][]> frames = a.poses.clips.get(a.clip);
                int idx = (int) Math.floor((now - a.startTick) / a.stretch);
                if (idx >= frames.size()) {
                    a.clip = null; Runnable then = a.then; a.then = null;
                    a.current = a.aiming && a.poses.clips.containsKey("aim") ? a.poses.clips.get("aim").get(0) : a.poses.rest;
                    duration = 2;
                    if (then != null) then.run();
                } else if (idx != a.frame) { a.frame = idx; a.current = frames.get(idx); duration = Math.max(1, (int) Math.round(a.stretch)); }
                else duration = 0;
            } else {
                float[][] want = a.aiming && a.poses.clips.containsKey("aim") ? a.poses.clips.get("aim").get(0) : a.poses.rest;
                if (want != a.current) { a.current = want; duration = aimTicks(); }
                else duration = 0;
            }
            // H <-> A: the client interpolates every part (position and scale linearly, rotation as a quaternion) from
            // where it is to the other state over aim-ticks, whatever the player's own /viewmodel hip offset is
            boolean switchAim = a.aiming != a.shownAiming;
            if (switchAim) { a.shownAiming = a.aiming; duration = Math.max(duration, aimTicks()); }
            // Rig scale: only as small as the wall in front needs. Any scale gives the same picture from a still camera, so
            // the change is invisible - but the smaller the rig, the more the camera's own motion (view bobbing, the pitch
            // correction arriving a tick late) moves it on screen: at 0.4 everywhere (0.31) walking and looking up/down
            // swung the gun 2.5x as much as at full size. Shrinks within a tick, grows back over scale-ticks.
            double want = wantedScale(p, a.aiming);
            if (a.scale < 0 || Math.abs(want - a.scale) >= 0.05 || (want != a.scale && (want == scaleMin() || want == scaleOpen()))) {
                boolean shrink = a.scale >= 0 && want < a.scale;
                a.scale = want;
                if (!switchAim) duration = Math.max(duration, shrink ? 1 : Math.max(1, plugin.getConfig().getInt("display.rig.scale-ticks", 3)));
                switchAim = true;   // every part
            }
            send(p, a, duration, switchAim);
        }
    }

    /** Send the current pose: every part whose transform changed (or all when `all`). duration 0 = only things that
     *  moved because the camera pitch changed, sent without blending. */
    private void send(Player p, Active a, int duration, boolean all) {
        double pitch = p.getLocation().getPitch();
        boolean sneak = p.isSneaking();
        if (a.lastPitch == -1e9) return;   // debug tests: frozen
        boolean cameraMoved = Math.abs(pitch - a.lastPitch) > 0.5 || sneak != a.lastSneak;
        if (!all && duration == 0 && !cameraMoved) return;
        a.lastPitch = pitch; a.lastSneak = sneak;
        double[][] offC = eyeOffsetInCamera(pitch, sneak);
        // H = the gun's hip pose + this player's /viewmodel offset; A = the fixed aim pose for everyone (guns.yml aim-pose,
        // on top of the aim clip's end pose), with no player offset, so a customised H never moves the shared sight line
        Viewmodels.Setting vm = a.aiming ? new Viewmodels.Setting() : plugin.viewmodels() != null ? plugin.viewmodels().of(p) : new Viewmodels.Setting();
        double[][] hand = handChain(a.poses, vm, a.aiming, a.gun.aimPose());
        for (int i = 0; i < a.parts.size(); i++) {
            NmsDisplay.Part part = a.parts.get(i);
            if (part == null) continue;
            float[] m = a.current[i];
            double[][] tf;
            if (m == null) tf = hiddenTransform();
            else tf = transformFor(a.poses, i, m, hand, offC, a.scale < 0 ? scaleMin() : a.scale);
            if (!all && same(tf, a.lastSent[i])) continue;
            a.lastSent[i] = tf;
            if (all && i < 6 && plugin.getConfig().getBoolean("display.rig.debug", false))
                plugin.getLogger().info(String.format("rig part %d (%s, variant %d): %s translation [%.3f %.3f %.3f] scale [%.3f %.3f %.3f] pitch %.1f offC y %.3f z %.3f", i, a.poses.partCmd[i], a.poses.partVariant[i], m == null ? "HIDDEN" : "", tf[0][3], tf[1][3], tf[2][3],
                    Math.sqrt(tf[0][0]*tf[0][0]+tf[1][0]*tf[1][0]+tf[2][0]*tf[2][0]), Math.sqrt(tf[0][1]*tf[0][1]+tf[1][1]*tf[1][1]+tf[2][1]*tf[2][1]), Math.sqrt(tf[0][2]*tf[0][2]+tf[1][2]*tf[1][2]+tf[2][2]*tf[2][2]), pitch, offC[1][0], offC[2][0]));
            try { NmsDisplay.transform(part, tf, cameraMoved && duration == 0 ? pitchBlend() : duration); if (!all || a.spawned) NmsDisplay.update(p, part); }
            catch (Exception e) { plugin.getLogger().warning("Display rig update failed: " + e); }
        }
    }

    // ---------------------------------------------------------------- the maths

    /** T(hand) T(tr/16) R(rot) S(sc) T(-0.5): the gun's own first-person transform, then vanilla's hand placement. */
    private double[][] handChain(ModelPoses mp, Viewmodels.Setting vm, boolean aiming, org.bukkit.configuration.ConfigurationSection aimPose) {
        // hip: vanilla's hand point (0.56 right, 0.52 down, 0.72 ahead); aim: the gun centred under the eye line (display.rig.aim-hand),
        // on top of which the model's aim clip (if any) and the gun's aim-pose adjust apply
        double[] h = aiming ? list3("display.rig.aim-hand", new double[]{0.06, -0.235, -1.1}) : list3("display.rig.hand", new double[]{0.56, -0.52, -0.72});
        double[] extra = aiming ? new double[]{0, 0, 0} : list3("display.rig.offset", new double[]{0, 0, 0});
        // Aiming zooms the client's field of view (the aim.speed-multiplier: fov x (mult + 1) / 2), and the rig is a world
        // entity, so it would be magnified with the world. Everything in the aim placement is pushed out by the inverse, so
        // the gun keeps its screen size and spot: aim-hand is given in un-zoomed view units.
        double zoomComp = aiming ? 2.0 / (1.0 + Math.max(0.05, plugin.getConfig().getDouble("aim.speed-multiplier", 0.35))) : 1.0;
        double[][] t = translate((h[0] + extra[0] + vm.x / 16.0) * zoomComp, (h[1] + extra[1] + vm.y / 16.0) * zoomComp, (h[2] + extra[2] - vm.z / 16.0) * zoomComp);
        if (aiming && aimPose != null) {
            // the gun's aim-state adjust (guns.yml aim-pose / `/firearms pose <gun> aim ...`): offset in sixteenths of a block
            // (right, up, forward), rotation in degrees and a scale, applied about the hand point in view space
            double[] off = vec3(aimPose, "offset", new double[]{0, 0, 0}), rot = vec3(aimPose, "rotation", new double[]{0, 0, 0});
            double sc0 = aimPose.getDouble("scale", 1.0);
            double[] hz = { h[0] * zoomComp, h[1] * zoomComp, h[2] * zoomComp };
            double[][] adj = mul(mul(mul(translate(hz[0] + off[0] / 16.0 * zoomComp, hz[1] + off[1] / 16.0 * zoomComp, hz[2] - off[2] / 16.0 * zoomComp), rotXYZ(rot)), scale(sc0, sc0, sc0)), translate(-hz[0], -hz[1], -hz[2]));
            t = mul(adj, t);
        }
        double[][] tr = translate(mp.fpTranslation[0] / 16, mp.fpTranslation[1] / 16, mp.fpTranslation[2] / 16);
        // the model's first-person rotation is the hip cant (barrel pointing inward); on the sights the gun runs parallel to the view
        double[][] r = aiming && plugin.getConfig().getBoolean("display.rig.aim-straight", true) ? identity() : rotXYZ(mp.fpRotation);
        double[][] sc = scale(mp.fpScale[0], mp.fpScale[1], mp.fpScale[2]);
        return mul(mul(mul(mul(t, tr), r), sc), translate(-0.5, -0.5, -0.5));
    }
    private double scaleMin() { return Math.max(0.1, Math.min(1.0, plugin.getConfig().getDouble("display.rig.scale", 0.4))); }
    private double scaleOpen() { return Math.max(scaleMin(), Math.min(1.0, plugin.getConfig().getDouble("display.rig.scale-open", 1.0))); }
    /** Ticks the client blends a pitch-only correction over (0 = at once: the least lag, the most stepping). */
    private int pitchBlend() { return Math.max(0, plugin.getConfig().getInt("display.rig.pitch-blend-ticks", 0)); }

    /** The largest rig scale that keeps the gun out of the block in front: rays along the view and toward the hand,
     *  each compared with how far the full-size gun reaches that way (display.rig.reach-hip / reach-aim). */
    private double wantedScale(Player p, boolean aiming) {
        double lo = scaleMin(), hi = scaleOpen();
        if (hi <= lo || !plugin.getConfig().getBoolean("display.rig.wall-check", true)) return lo;
        double reach = aiming ? plugin.getConfig().getDouble("display.rig.reach-aim", 1.9) : plugin.getConfig().getDouble("display.rig.reach-hip", 1.3);
        org.bukkit.Location eye = p.getEyeLocation();
        org.bukkit.util.Vector f = eye.getDirection();
        double yaw = Math.toRadians(eye.getYaw());
        org.bukkit.util.Vector right = new org.bukkit.util.Vector(-Math.cos(yaw), 0, -Math.sin(yaw));
        org.bukkit.util.Vector up = right.clone().crossProduct(f);
        double[] h = list3("display.rig.hand", new double[]{0.56, -0.52, -0.72});
        org.bukkit.util.Vector toHand = right.clone().multiply(h[0]).add(up.multiply(h[1])).add(f.clone().multiply(-h[2])).normalize();
        double rs = hi;
        for (org.bukkit.util.Vector dir : aiming ? new org.bukkit.util.Vector[]{f} : new org.bukkit.util.Vector[]{f, toHand}) {
            var hit = p.getWorld().rayTraceBlocks(eye, dir, reach * hi, org.bukkit.FluidCollisionMode.NEVER, true);
            if (hit == null) continue;
            rs = Math.min(rs, (hit.getHitPosition().distance(eye.toVector()) - 0.05) / reach);
        }
        return Math.max(lo, Math.min(hi, rs));
    }
    private int aimTicks() { return Math.max(1, plugin.getConfig().getInt("display.rig.aim-ticks", 4)); }
    private static double[] vec3(org.bukkit.configuration.ConfigurationSection c, String key, double[] def) {
        List<Double> l = c.getDoubleList(key);
        return l.size() == 3 ? new double[]{l.get(0), l.get(1), l.get(2)} : def;
    }
    /** Re-send every rig (after an aim-pose edit). */
    void refreshAll() { for (Player p : plugin.getServer().getOnlinePlayers()) refresh(p); }

    /** The entity transformation that puts part i where the item renderer would draw it, in the display's own frame. */
    private double[][] transformFor(ModelPoses mp, int i, float[] m, double[][] hand, double[][] offC, double rs) {
        double[][] M = {{m[0], m[1], m[2], m[3]}, {m[4], m[5], m[6], m[7]}, {m[8], m[9], m[10], m[11]}, {0, 0, 0, 1}};
        double[] pv = mp.partPivot[i];
        double[] Pw = { M[0][0]*pv[0]+M[0][1]*pv[1]+M[0][2]*pv[2]+M[0][3], M[1][0]*pv[0]+M[1][1]*pv[1]+M[1][2]*pv[2]+M[1][3], M[2][0]*pv[0]+M[2][1]*pv[1]+M[2][2]*pv[2]+M[2][3] };
        double k = mp.partK[i];
        // gun(blocks) = Pw/16 + (M3/k) r, r = the item model centred at the entity origin
        double[][] g = {{M[0][0]/k, M[0][1]/k, M[0][2]/k, Pw[0]/16}, {M[1][0]/k, M[1][1]/k, M[1][2]/k, Pw[1]/16}, {M[2][0]/k, M[2][1]/k, M[2][2]/k, Pw[2]/16}, {0, 0, 0, 1}};
        double[][] view = mul(hand, g);                     // view space: +X right, +Y up, -Z forward
        // The whole rig is shrunk toward the eye (display.rig.scale): a uniform scale about the camera leaves the picture on
        // screen exactly the same (perspective), but the model then lives inside the player's own space instead of
        // reaching 0.7 blocks into the wall in front - the "gun through walls" of 0.30.
        if (rs != 1.0) view = mul(scale(rs, rs, rs), view);
        // A CENTER-billboarded display's local frame IS view space (+X right, +Y up, +Z toward the camera) - measured on a
        // real 26.3 client (0.30): a translation of (0.6, -0.2, -1.0) shows up right, slightly low, one block ahead. The item
        // renderer then turns the item 180 degrees about Y before drawing it, so the item-space geometry gets that flip.
        double[][] tf = mul(view, rotY180());
        for (int a = 0; a < 3; a++) tf[a][3] -= offC[a][0];
        return tf;
    }

    private double[][] hiddenTransform() { double[][] t = identity(); t[0][0] = t[1][1] = t[2][2] = 0; return t; }

    /** Where the rig's origin (the passenger point) sits relative to the eyes, in the camera frame. */
    private double[][] eyeOffsetInCamera(double pitchDeg, boolean sneak) {
        double up = sneak ? plugin.getConfig().getDouble("display.rig.eye-offset-sneak", 0.23) : plugin.getConfig().getDouble("display.rig.eye-offset", 0.18);
        // camera frame = rotationYXZ(180 - yaw, -pitch, 0) applied to (0,0,1)=forward; world up in that frame depends on pitch only
        double p = Math.toRadians(-pitchDeg);
        // rotate world (0, up, 0) into the camera frame: inverse of the X rotation by -pitch
        double y = up * Math.cos(p), z = -up * Math.sin(p);
        return new double[][]{{0}, {y}, {z}};
    }

    private static boolean same(double[][] a, double[][] b) {
        if (b == null) return false;
        for (int i = 0; i < 3; i++) for (int j = 0; j < 4; j++) if (Math.abs(a[i][j] - b[i][j]) > 1e-4) return false;
        return true;
    }
    private double[] list3(String key, double[] def) {
        List<Double> l = plugin.getConfig().getDoubleList(key);
        return l.size() == 3 ? new double[]{l.get(0), l.get(1), l.get(2)} : def;
    }
    static double[][] identity() { return new double[][]{{1,0,0,0},{0,1,0,0},{0,0,1,0},{0,0,0,1}}; }
    static double[][] translate(double x, double y, double z) { double[][] m = identity(); m[0][3] = x; m[1][3] = y; m[2][3] = z; return m; }
    static double[][] scale(double x, double y, double z) { double[][] m = identity(); m[0][0] = x; m[1][1] = y; m[2][2] = z; return m; }
    static double[][] rotY180() { return new double[][]{{-1,0,0,0},{0,1,0,0},{0,0,-1,0},{0,0,0,1}}; }
    /** Display rotations are XYZ Euler (the client applies X, then Y, then Z). */
    static double[][] rotXYZ(double[] deg) {
        double x = Math.toRadians(deg[0]), y = Math.toRadians(deg[1]), z = Math.toRadians(deg[2]);
        double[][] rx = {{1,0,0,0},{0,Math.cos(x),-Math.sin(x),0},{0,Math.sin(x),Math.cos(x),0},{0,0,0,1}};
        double[][] ry = {{Math.cos(y),0,Math.sin(y),0},{0,1,0,0},{-Math.sin(y),0,Math.cos(y),0},{0,0,0,1}};
        double[][] rz = {{Math.cos(z),-Math.sin(z),0,0},{Math.sin(z),Math.cos(z),0,0},{0,0,1,0},{0,0,0,1}};
        return mul(mul(rx, ry), rz);   // the same product the pack generator writes display rotations with
    }
    static double[][] mul(double[][] a, double[][] b) {
        double[][] r = new double[4][4];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) { double s = 0; for (int k = 0; k < 4; k++) s += a[i][k] * b[k][j]; r[i][j] = s; }
        return r;
    }
}
