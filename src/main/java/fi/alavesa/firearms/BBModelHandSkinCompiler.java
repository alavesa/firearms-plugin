package fi.alavesa.firearms;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Hands modelled in a weapon .bbmodel, painted with the holder's own skin.
 * <p>
 * <b>What a 26.3 client can and cannot do.</b> The only item renderer that draws a player's skin is the player head
 * ({@code PlayerHeadSpecialRenderer}: the special model renderers in the 26.3 client are banner, bell, book, chest, conduit,
 * copper golem statue, decorated pot, end cube, player head, shield, shulker box, skull, trident). It draws the HEAD cube
 * of the profile's skin and nothing else: there is no item model type whose texture is "the holder's skin", so a cuboid
 * model with UVs on the arm region of a 64x64 skin cannot be fed a player profile. The one mechanism that does reach the
 * holder's skin on arbitrary geometry is the one this plugin has used since 0.21 ({@link ArmSkin}): every skin pixel of the
 * hand becomes its own quad with its own tint index, and the plugin writes the holder's skin pixels - fetched from their
 * live {@code PlayerProfile} textures - into the stack's {@code custom_model_data.colors}. One pack for everybody.
 * <p>
 * This class is the compiler in front of that pipeline:
 * <ul>
 * <li>{@link #isHandName}/{@link #sideOf}: bones and cubes named {@code left_hand}, {@code right_hand}, {@code left_arm},
 *     {@code l_hand}, {@code hand_r}... (plus config {@code hands.group-keywords}) are hands, and which hand.</li>
 * <li>{@link #remapFaces}: a hand cube whose faces are NOT already on a skin template (TaCZ exports texture the hands from
 *     the gun atlas) gets its six faces re-pointed at the standard arm region of a 64x64 skin - right arm
 *     {@code [40,16]-[56,32]} + sleeve {@code [40,32]-[56,48]}, left arm {@code [32,48]-[48,64]} + sleeve
 *     {@code [48,48]-[64,64]}, slim 3-px-wide variants included - oriented from the cube's own geometry
 *     ({@link #orient}: the long axis is the arm, the end nearer the gun is the hand).</li>
 * <li>{@link #template}: the opaque 64x64 placeholder used as the pixel template for such hands (the real skin pixels
 *     arrive at runtime; a transparent sleeve pixel falls back to the skin underneath, see {@link ArmSkin#colorsFor}).</li>
 * <li>{@link #bind}: runtime - the holder's profile skin onto the gun stack the rig's hand parts are cloned from.</li>
 * </ul>
 */
final class BBModelHandSkinCompiler {
    private BBModelHandSkinCompiler() { }

    enum Side { LEFT, RIGHT }
    /** The six faces of a Minecraft arm box as laid out in the skin. */
    enum ArmFace { OUTER, FRONT, INNER, BACK, SHOULDER, HAND }

    /** Bone / cube names that are hands, before the configurable keywords. */
    static final List<String> DEFAULT_ALIASES = List.of(
        "left_hand", "right_hand", "left_arm", "right_arm", "l_hand", "r_hand", "lhand", "rhand", "hand_l", "hand_r",
        "arm_l", "arm_r", "l_arm", "r_arm", "lefthand", "righthand", "leftarm", "rightarm", "hand", "arm");

    static String normalise(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    /** Is this bone/cube name a hand? Aliases match as whole words ("arm" does not match "armor"/"alarm"). */
    static boolean isHandName(String name, List<String> extraKeywords) {
        String n = normalise(name);
        if (n.isEmpty()) return false;
        List<String> words = new ArrayList<>(DEFAULT_ALIASES);
        if (extraKeywords != null) for (String k : extraKeywords) words.add(normalise(k));
        String padded = "_" + n + "_";
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (padded.contains("_" + w + "_")) return true;
            // "lefthand2", "hand01": a word followed by digits only
            if (n.matches("(.*_)?" + java.util.regex.Pattern.quote(w) + "\\d*(_.*)?")) return true;
        }
        return false;
    }

    /** Words a hand bone's name may consist of (plus digits and the configured keywords). */
    private static final java.util.Set<String> HAND_TOKENS = java.util.Set.of("hand", "hands", "arm", "arms", "forearm", "left", "right", "l", "r",
        "fp", "first", "person", "firstperson", "slim", "classic", "wide", "finger", "fingers", "thumb", "palm", "wrist", "sleeve", "overlay");

    /**
     * Strict detection for the automatic remap: the WHOLE name is made of hand words - "left_hand", "righthand2",
     * "hand_l", "arm", "left_arm_slim" - and not "gun_and_righthand", "righthand_pos" or "thirdperson_hand" (TaCZ exports
     * keep the entire gun under a group named like that; remapping all of it onto the skin would be every cube of the gun).
     */
    static boolean isHandBoneName(String name, List<String> extraKeywords) {
        String n = normalise(name);
        if (n.isEmpty()) return false;
        String spaced = n.replaceAll("(left|right)(hand|arm)", "$1_$2").replaceAll("(hand|arm)(l|r)(\\d*)$", "$1_$2$3");
        List<String> extra = new ArrayList<>();
        if (extraKeywords != null) for (String k : extraKeywords) extra.add(normalise(k));
        boolean sawHand = false;
        for (String tok : spaced.split("_")) {
            String t = tok.replaceAll("\\d+$", "");
            if (t.isEmpty()) continue;                       // a bare number
            boolean keyword = extra.contains(t);
            if (!HAND_TOKENS.contains(t) && !keyword) return false;
            if (t.equals("hand") || t.equals("hands") || t.equals("arm") || t.equals("arms") || t.equals("forearm") || keyword) sawHand = true;
        }
        return sawHand;
    }

    /** Which hand, from the cube's and its parents' names (nearest first). RIGHT when nothing says left. */
    static Side sideOf(List<String> chainNearestFirst) {
        for (String raw : chainNearestFirst) {
            String n = "_" + normalise(raw) + "_";
            if (n.contains("_left_") || n.contains("_l_") || n.contains("lefthand") || n.contains("leftarm") || n.matches(".*_l\\d*_.*") || n.contains("_left") || n.contains("left_")) return Side.LEFT;
            if (n.contains("_right_") || n.contains("_r_") || n.contains("righthand") || n.contains("rightarm") || n.contains("_right") || n.contains("right_")) return Side.RIGHT;
        }
        return Side.RIGHT;
    }

    /** A second-layer (sleeve) cube by name. */
    static boolean isSleeveName(String name) {
        String n = "_" + normalise(name) + "_";
        return n.contains("sleeve") || n.contains("overlay") || n.contains("_layer2_") || n.contains("outer_layer") || n.contains("_outer_");
    }

    // ------------------------------------------------------------------ the 64x64 skin layout

    /**
     * The skin rectangle [u1, v1, u2, v2] of one arm face. Classic arms are 4 px wide, slim 3 px; sleeves are the second
     * layer, +16 down (right) / +16 right (left). Right arm columns: 40 outer, 44 front, 48 inner, 52 back; top row 16
     * shoulder (44) + hand (48). Left arm columns at 32: inner (its right side), 36 front, 40 outer, 44 back; row 48.
     */
    static int[] uv(Side side, boolean slim, boolean sleeve, ArmFace face) {
        int w = slim ? 3 : 4;                 // arm width (front/back/shoulder/hand)
        int ox, oy;                           // origin of the arm's 16x16 (12 tall) region
        if (side == Side.RIGHT) { ox = 40; oy = 16; if (sleeve) oy += 16; }
        else { ox = 32; oy = 48; if (sleeve) ox += 16; }
        // column layout: [4 side][w][4 side][w]
        int cA = ox, cB = ox + 4, cC = ox + 4 + w, cD = ox + 8 + w;
        int[] r = switch (face) {
            case SHOULDER -> new int[]{cB, oy, cB + w, oy + 4};
            case HAND     -> new int[]{cC, oy, cC + w, oy + 4};
            case FRONT    -> new int[]{cB, oy + 4, cB + w, oy + 16};
            case BACK     -> new int[]{cD, oy + 4, cD + w, oy + 16};
            // the first side column is the arm's RIGHT side: outer for the right arm, inner for the left
            case OUTER    -> side == Side.RIGHT ? new int[]{cA, oy + 4, cA + 4, oy + 16} : new int[]{cC, oy + 4, cC + 4, oy + 16};
            case INNER    -> side == Side.RIGHT ? new int[]{cC, oy + 4, cC + 4, oy + 16} : new int[]{cA, oy + 4, cA + 4, oy + 16};
        };
        return r;
    }

    /** Is a (u, v) skin pixel in a sleeve region, and if so where is the skin pixel under it? null = not a sleeve pixel. */
    static int[] sleeveBase(int u, int v) {
        if (u >= 40 && u < 56 && v >= 32 && v < 48) return new int[]{u, v - 16};   // right sleeve over right arm
        if (u >= 48 && u < 64 && v >= 48 && v < 64) return new int[]{u - 16, v};   // left sleeve over left arm
        return null;
    }

    // ------------------------------------------------------------------ geometry -> arm faces

    private static final String[] FACE = {"north", "south", "east", "west", "up", "down"};
    private static final int[][] DIR   = {{0,0,-1},{0,0,1},{1,0,0},{-1,0,0},{0,1,0},{0,-1,0}};
    private static final int[][] FUP   = {{0,1,0},{0,1,0},{0,1,0},{0,1,0},{0,0,-1},{0,0,1}};
    private static final int[][] FRIGHT= {{-1,0,0},{1,0,0},{0,0,-1},{0,0,1},{1,0,0},{1,0,0}};

    /** Which world direction (unit, axis-aligned) is the hand end, the outer side and the front of this arm cube. */
    static final class Orientation {
        int[] hand = {0, 0, -1}, outer = {1, 0, 0}, front = {0, 1, 0};
        /** The arm face every Minecraft face of the cube shows. */
        Map<String, ArmFace> faces() {
            Map<String, ArmFace> m = new LinkedHashMap<>();
            for (int i = 0; i < 6; i++) {
                int[] d = DIR[i];
                ArmFace f;
                if (same(d, hand)) f = ArmFace.HAND; else if (same(d, neg(hand))) f = ArmFace.SHOULDER;
                else if (same(d, outer)) f = ArmFace.OUTER; else if (same(d, neg(outer))) f = ArmFace.INNER;
                else if (same(d, front)) f = ArmFace.FRONT; else f = ArmFace.BACK;
                m.put(FACE[i], f);
            }
            return m;
        }
    }

    /**
     * Read the arm's orientation off a cube: the longest edge is the arm, the end nearer {@code gunCentre} (the centroid of
     * the gun's own cubes) is the hand; of the other two axes the one nearest world X is the outer/inner pair (outer = the
     * player's right for the right hand, left for the left), the last one is front/back with front = up (an arm raised to
     * hold a gun forward shows the front of the arm on top), or toward the muzzle (-Z) for a vertical arm.
     */
    static Orientation orient(double[] from, double[] to, double[] gunCentre, Side side) {
        Orientation o = new Orientation();
        double[] len = new double[3], centre = new double[3];
        for (int i = 0; i < 3; i++) { len[i] = Math.abs(to[i] - from[i]); centre[i] = (to[i] + from[i]) / 2; }
        int arm = 0; for (int i = 1; i < 3; i++) if (len[i] > len[arm]) arm = i;
        if (len[arm] < 1e-6) return o;
        int handSign = gunCentre == null ? (arm == 1 ? -1 : -1) : (gunCentre[arm] >= centre[arm] ? 1 : -1);
        if (gunCentre != null && Math.abs(gunCentre[arm] - centre[arm]) < 1e-6) handSign = arm == 2 ? -1 : (arm == 1 ? 1 : 1);
        o.hand = axis(arm, handSign);
        int outerAxis = arm != 0 ? 0 : 2;                                  // X unless the arm runs along X, then Z
        int outerSign = outerAxis == 0 ? (side == Side.RIGHT ? 1 : -1) : 1;
        o.outer = axis(outerAxis, outerSign);
        int frontAxis = 3 - arm - outerAxis;                               // the remaining axis
        int frontSign = frontAxis == 1 ? 1 : frontAxis == 2 ? -1 : 1;
        o.front = axis(frontAxis, frontSign);
        return o;
    }

    private static int[] axis(int a, int sign) { int[] v = new int[3]; v[a] = sign; return v; }
    /** The cube's size along an axis-aligned unit direction. */
    private static double extent(double[] from, double[] to, int[] dir) { int a = dir[0] != 0 ? 0 : dir[1] != 0 ? 1 : 2; return Math.abs(to[a] - from[a]); }
    private static boolean same(int[] a, int[] b) { return a[0] == b[0] && a[1] == b[1] && a[2] == b[2]; }
    private static int[] neg(int[] v) { return new int[]{-v[0], -v[1], -v[2]}; }

    /**
     * The cube's faces re-pointed at the skin: every face gets {@code texture = textureRef}, the arm UV of its arm face
     * and a {@code rotation} so the skin's "down the arm" runs toward the hand end. Faces the cube did not have stay
     * absent (a hand sunk into the grip has no visible palm). {@code side}/{@code slim}/{@code sleeve} pick the region.
     */
    static JsonObject remapFaces(JsonObject faces, Orientation o, Side side, boolean slim, boolean sleeve, String textureRef) {
        return remapFaces(faces, o, side, slim, sleeve, textureRef, null, null);
    }

    /**
     * As above, with the UV rectangle of every face sized to the cube: a face w x h model pixels large gets a w x h pixel
     * slice of its arm face (never more than the arm face itself), anchored at the hand end, so a finger cube costs a
     * handful of skin-pixel quads and a whole 4x12x4 arm box the full region. Null from/to = the full region for every face.
     */
    static JsonObject remapFaces(JsonObject faces, Orientation o, Side side, boolean slim, boolean sleeve, String textureRef, double[] from, double[] to) {
        Map<String, ArmFace> which = o.faces();
        JsonObject out = new JsonObject();
        for (int i = 0; i < 6; i++) {
            String f = FACE[i];
            if (faces != null && !faces.has(f)) continue;
            ArmFace af = which.get(f);
            int[] uv = uv(side, slim, sleeve, af);
            JsonObject face = new JsonObject();
            // rotate the face's uv so its v axis (texture down) points toward the hand: each 90-degree step turns
            // (u, v) -> (v, -u), the same rule the pixel exploder applies (end faces keep their rotation)
            int[] aU = FRIGHT[i].clone(), aV = neg(FUP[i]);
            int rot = 0;
            if (af != ArmFace.HAND && af != ArmFace.SHOULDER) {
                for (int r = 0; r < 4; r++) {
                    if (same(aV, o.hand)) { rot = r * 90; break; }
                    int[] t = aU; aU = aV; aV = neg(t);
                }
            }
            if (from != null && to != null) {
                // size the slice to the cube's face: w along the (rotated) u axis, h along v, from the hand end up
                int rw = uv[2] - uv[0], rh = uv[3] - uv[1];
                int w = Math.max(1, Math.min(rw, (int) Math.round(extent(from, to, aU)))), h = Math.max(1, Math.min(rh, (int) Math.round(extent(from, to, aV))));
                int u1 = uv[0] + (rw - w) / 2, v2 = uv[3];
                uv = new int[]{u1, v2 - h, u1 + w, v2};
            }
            JsonArray a = new JsonArray(); a.add(uv[0]); a.add(uv[1]); a.add(uv[2]); a.add(uv[3]);
            face.add("uv", a);
            face.addProperty("texture", textureRef);
            if (rot != 0) face.addProperty("rotation", rot);
            out.add(f, face);
        }
        return out;
    }

    /** An opaque 64x64 placeholder skin (Steve-ish tones, sleeves included) - the pixel template for auto-remapped hands:
     *  every arm and sleeve pixel exists, so every quad is generated; the holder's real pixels arrive at runtime. */
    static BufferedImage template() {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) img.setRGB(x, y, 0xFFC58C5E);
        for (int y = 32; y < 48; y++) for (int x = 40; x < 56; x++) img.setRGB(x, y, 0xFF00A0A8);   // right sleeve
        for (int y = 48; y < 64; y++) for (int x = 48; x < 64; x++) img.setRGB(x, y, 0xFF00A0A8);   // left sleeve
        return img;
    }

    // ------------------------------------------------------------------ runtime

    /** Put the holder's live profile skin on the gun stack the rig's hand parts are cloned from (colours + the slim flag).
     *  False when the skin is still being fetched - then the arms are refreshed once it has arrived. */
    static boolean bind(FirearmsPlugin plugin, Registry registry, Player holder, ItemStack gun, GunType type) {
        if (holder == null || gun == null || type == null) return false;
        if (!ArmSkin.ready(holder.getUniqueId())) {
            ArmSkin.load(plugin, holder, () -> { if (holder.isOnline() && plugin.controller() != null) plugin.controller().refreshArms(holder); });
            return false;
        }
        return registry.applySkin(gun, type, holder);
    }

    /** Drop the cached skin and fetch it again from the profile (after a skin change). */
    static void rebind(FirearmsPlugin plugin, Player holder) {
        ArmSkin.forget(holder.getUniqueId());
        ArmSkin.load(plugin, holder, () -> { if (holder.isOnline() && plugin.controller() != null) plugin.controller().refreshArms(holder); });
    }
}
