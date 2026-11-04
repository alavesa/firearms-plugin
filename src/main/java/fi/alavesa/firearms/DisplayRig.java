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
        double lastPitch = 1e9; boolean lastSneak;
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
            send(p, a, 0, true);
            for (NmsDisplay.Part pt : real) NmsDisplay.spawn(p, pt);
            NmsDisplay.ride(p, real);
            a.spawned = true;
            active.put(p.getUniqueId(), a);
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
    void refresh(Player p) { Active a = active.get(p.getUniqueId()); if (a != null) send(p, a, 2, true); }

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
            if (registry.gunOf(p.getInventory().getItemInMainHand()) != a.gun) { hide(p); continue; }
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
                if (want != a.current) { a.current = want; duration = plugin.getConfig().getInt("display.rig.aim-ticks", 3); }
                else duration = 0;
            }
            send(p, a, duration, false);
        }
    }

    /** Send the current pose: every part whose transform changed (or all when `all`). duration 0 = only things that
     *  moved because the camera pitch changed, sent without blending. */
    private void send(Player p, Active a, int duration, boolean all) {
        double pitch = p.getLocation().getPitch();
        boolean sneak = p.isSneaking();
        boolean cameraMoved = Math.abs(pitch - a.lastPitch) > 0.5 || sneak != a.lastSneak;
        if (!all && duration == 0 && !cameraMoved) return;
        a.lastPitch = pitch; a.lastSneak = sneak;
        double[][] offC = eyeOffsetInCamera(pitch, sneak);
        double[][] hand = handChain(a.poses, plugin.viewmodels() != null ? plugin.viewmodels().of(p) : new Viewmodels.Setting());
        for (int i = 0; i < a.parts.size(); i++) {
            NmsDisplay.Part part = a.parts.get(i);
            if (part == null) continue;
            float[] m = a.current[i];
            double[][] tf;
            if (m == null) tf = hiddenTransform();
            else tf = transformFor(a.poses, i, m, hand, offC);
            if (!all && same(tf, a.lastSent[i])) continue;
            a.lastSent[i] = tf;
            try { NmsDisplay.transform(part, tf, cameraMoved && duration == 0 ? 1 : duration); if (!all || a.spawned) NmsDisplay.update(p, part); }
            catch (Exception e) { plugin.getLogger().warning("Display rig update failed: " + e); }
        }
    }

    // ---------------------------------------------------------------- the maths

    /** T(hand) T(tr/16) R(rot) S(sc) T(-0.5): the gun's own first-person transform, then vanilla's hand placement. */
    private double[][] handChain(ModelPoses mp, Viewmodels.Setting vm) {
        double[] h = list3("display.rig.hand", new double[]{0.56, -0.52, -0.72});
        double[] extra = list3("display.rig.offset", new double[]{0, 0, 0});
        // the player's own /viewmodel offset (right, up, forward -> view -z), in sixteenths of a block
        double[][] t = translate(h[0] + extra[0] + vm.x / 16.0, h[1] + extra[1] + vm.y / 16.0, h[2] + extra[2] - vm.z / 16.0);
        double[][] tr = translate(mp.fpTranslation[0] / 16, mp.fpTranslation[1] / 16, mp.fpTranslation[2] / 16);
        double[][] r = rotXYZ(mp.fpRotation);
        double[][] sc = scale(mp.fpScale[0], mp.fpScale[1], mp.fpScale[2]);
        return mul(mul(mul(mul(t, tr), r), sc), translate(-0.5, -0.5, -0.5));
    }

    /** The entity transformation that puts part i where the item renderer would draw it, in the display's own frame. */
    private double[][] transformFor(ModelPoses mp, int i, float[] m, double[][] hand, double[][] offC) {
        double[][] M = {{m[0], m[1], m[2], m[3]}, {m[4], m[5], m[6], m[7]}, {m[8], m[9], m[10], m[11]}, {0, 0, 0, 1}};
        double[] pv = mp.partPivot[i];
        double[] Pw = { M[0][0]*pv[0]+M[0][1]*pv[1]+M[0][2]*pv[2]+M[0][3], M[1][0]*pv[0]+M[1][1]*pv[1]+M[1][2]*pv[2]+M[1][3], M[2][0]*pv[0]+M[2][1]*pv[1]+M[2][2]*pv[2]+M[2][3] };
        double k = mp.partK[i];
        // gun(blocks) = Pw/16 + (M3/k) r, r = the item model centred at the entity origin
        double[][] g = {{M[0][0]/k, M[0][1]/k, M[0][2]/k, Pw[0]/16}, {M[1][0]/k, M[1][1]/k, M[1][2]/k, Pw[1]/16}, {M[2][0]/k, M[2][1]/k, M[2][2]/k, Pw[2]/16}, {0, 0, 0, 1}};
        double[][] view = mul(hand, g);                     // view space: +X right, +Y up, -Z forward
        double[][] flip = rotY180();                        // camera/display frame: +X left, +Z forward; the item renderer flips once more
        double[][] tf = mul(mul(flip, view), flip);
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
