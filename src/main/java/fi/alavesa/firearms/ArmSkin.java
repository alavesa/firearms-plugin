package fi.alavesa.firearms;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Color;
import org.bukkit.entity.Player;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The player's own skin on the gun's first-person arms, with ONE resource pack for everyone (the BetterModel
 * trick): the arm is built from one tiny quad per skin pixel, every quad has its own `tintindex`, the item
 * definition declares a `custom_model_data` tint per index, and the plugin writes the player's skin pixel
 * colours into the held gun's `custom_model_data.colors`. The pack only ships a white pixel texture.
 *
 * This class is the single source of truth for the PIXEL ORDER: the generator (geometry + tint indices) and
 * the plugin (colour list) both iterate {@link #pixels(boolean)} the same way.
 */
public final class ArmSkin {

    /** One skin pixel on the arm: which face of the 4x12x4 arm box, its local column/row, and skin uv. */
    public record Pixel(String face, int col, int row, int u, int v) { }

    /** Faces rendered on each arm (what you actually see in first person). */
    private static final String[] FACES = {"front", "outer", "inner", "top", "back"};

    /** Enumerate the pixels of one arm in the fixed tint order. Skin layout = 64x64 Java skin.
     *  right arm: top(44,16) bottom(48,16) outer(40,20) front(44,20) inner(48,20) back(52,20)
     *  left  arm: top(36,48) bottom(40,48) outer(32,52) front(36,52) inner(40,52) back(44,52)  (mirrored use) */
    public static List<Pixel> pixels(boolean right) {
        List<Pixel> out = new ArrayList<>();
        int ox = right ? 40 : 32, oy = right ? 16 : 48;
        for (String face : FACES) {
            int w = face.equals("top") ? 4 : 4, h = face.equals("top") ? 4 : 12;
            int u0, v0;
            switch (face) {
                case "top"   -> { u0 = ox + 4; v0 = oy; }
                case "outer" -> { u0 = ox; v0 = oy + 4; }
                case "front" -> { u0 = ox + 4; v0 = oy + 4; }
                case "inner" -> { u0 = ox + 8; v0 = oy + 4; }
                default      -> { u0 = ox + 12; v0 = oy + 4; }   // back
            }
            for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) out.add(new Pixel(face, c, r, u0 + c, v0 + r));
        }
        return out;
    }

    public static int pixelCount() { return pixels(true).size(); }

    // ------------------------------------------------------------------ skin colours per player

    private static final Map<UUID, int[]> cache = new ConcurrentHashMap<>();          // uuid -> 64x64 ARGB
    private static final Map<UUID, CompletableFuture<Void>> loading = new ConcurrentHashMap<>();
    private static int[] steve;

    /** Start fetching this player's skin (async, once). `then` runs on the main thread when it is ready. */
    public static void load(FirearmsPlugin plugin, Player p, Runnable then) {
        UUID id = p.getUniqueId();
        if (cache.containsKey(id)) { then.run(); return; }
        if (loading.containsKey(id)) return;
        PlayerProfile profile = p.getPlayerProfile();
        PlayerTextures tex = profile.getTextures();
        URL url = tex.getSkin();
        CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
            int[] px = null;
            try {
                if (url != null) {
                    BufferedImage img = ImageIO.read(url);
                    if (img != null) px = toArgb(img);
                }
            } catch (Exception ignored) { }
            if (px == null) px = steve(plugin);
            cache.put(id, px);
        });
        loading.put(id, f);
        f.whenComplete((v, t) -> {
            loading.remove(id);
            plugin.getServer().getScheduler().runTask(plugin, then);
        });
    }

    public static boolean ready(UUID id) { return cache.containsKey(id); }
    public static void forget(UUID id) { cache.remove(id); loading.remove(id); }

    /** The colour list for a gun's arms (right arm pixels then left arm pixels), or null if not loaded. */
    public static List<Color> colors(UUID id, boolean leftToo) {
        int[] px = cache.get(id);
        if (px == null) return null;
        List<Color> out = new ArrayList<>();
        for (Pixel p : pixels(true)) out.add(color(px, p));
        if (leftToo) for (Pixel p : pixels(false)) out.add(color(px, p));
        return out;
    }

    /** Colours for an arbitrary (u,v) pixel order - the hands modelled in a .bbmodel. */
    public static List<Color> colorsFor(UUID id, List<int[]> order) {
        int[] px = cache.get(id);
        if (px == null) return null;
        List<Color> out = new ArrayList<>();
        for (int[] uv : order) {
            int u = Math.max(0, Math.min(63, uv[0])), v = Math.max(0, Math.min(63, uv[1]));
            int argb = px[v * 64 + u];
            out.add(((argb >>> 24) & 255) < 16 ? Color.fromRGB(0xC58C5E) : Color.fromRGB((argb >> 16) & 255, (argb >> 8) & 255, argb & 255));
        }
        return out;
    }

    private static Color color(int[] px, Pixel p) {
        int argb = px[p.v() * 64 + p.u()];
        if (((argb >>> 24) & 255) < 16) {
            // transparent pixel in a 64x32 legacy skin's unused area etc.: fall back to a skin-tone
            return Color.fromRGB(0xC58C5E);
        }
        return Color.fromRGB((argb >> 16) & 255, (argb >> 8) & 255, argb & 255);
    }

    private static int[] toArgb(BufferedImage img) {
        int[] out = new int[64 * 64];
        int w = Math.min(64, img.getWidth()), h = Math.min(64, img.getHeight());
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) out[y * 64 + x] = img.getRGB(x, y);
        if (img.getHeight() <= 32) {
            // legacy 64x32 skin: no left arm region - mirror the right arm into the left arm slots
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) out[(48 + y) * 64 + 32 + x] = out[(16 + y) * 64 + 40 + x];
        }
        return out;
    }

    /** Default Steve-like arm colours (used when a skin can't be fetched, e.g. offline-mode servers). */
    private static synchronized int[] steve(FirearmsPlugin plugin) {
        if (steve != null) return steve;
        int[] px = new int[64 * 64];
        java.util.Arrays.fill(px, 0xFFC58C5E);                       // skin
        for (int y = 24; y < 32; y++) for (int x = 40; x < 56; x++) px[y * 64 + x] = 0xFF00A0A8;   // teal sleeve
        for (int y = 56; y < 64; y++) for (int x = 32; x < 48; x++) px[y * 64 + x] = 0xFF00A0A8;
        steve = px;
        return px;
    }

    /** Parse the skin "model" (slim/classic) out of the profile, for completeness. */
    public static boolean slim(Player p) {
        try {
            PlayerTextures t = p.getPlayerProfile().getTextures();
            return t.getSkinModel() == PlayerTextures.SkinModel.SLIM;
        } catch (Exception e) { return false; }
    }

    static JsonObject json(String s) { return JsonParser.parseString(s).getAsJsonObject(); }
}
