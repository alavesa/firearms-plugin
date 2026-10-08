package fi.alavesa.firearms;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.file.YamlConfiguration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * .bbmodel -> resource pack. Drop Blockbench files into plugins/Firearms/models/ named after the gun / mag /
 * ammo model (guns.yml `model:`), run /firearms pack, and a complete pack zip is written:
 *   - geometry + embedded textures become a Java item model per file;
 *   - EVERY Blockbench animation is baked into single frames automatically (no hand-made frames): bone
 *     rotation/position keyframes are sampled every `anim.frame-ticks` ticks, the root bone's motion goes
 *     into the first-person display transform (exact), child bones are baked into the cubes (Java cubes can
 *     only rotate on one axis in 22.5 steps, so those are snapped);
 *   - animation names decide the clip: fire/shoot, reload, equip/draw, pump/rack;
 *   - the custom_model_data select for the base item, pack.mcmeta, placeholders for anything without a file,
 *     and models/anim-index.yml so the plugin knows each clip's frame count.
 */
public final class PackGenerator {

    public record Result(int models, int frames, int placeholders, List<String> warnings, File zip) { }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String NS = "firearms";

    private final FirearmsPlugin plugin;
    private final Registry registry;
    private final List<String> warnings = new ArrayList<>();
    private final Map<String, byte[]> files = new LinkedHashMap<>();       // zip path -> bytes
    private final Map<String, String> cmdToModel = new LinkedHashMap<>();  // custom_model_data -> model path
    private final Map<String, Map<String, int[]>> animIndex = new LinkedHashMap<>();
    private int frames = 0, placeholders = 0, models = 0;
    private final Map<String, String> vestCmdToModel = new LinkedHashMap<>();   // vests live on the armour item
    private final List<String> report = new ArrayList<>();

    /** A resource-location-safe path for a model name. Minecraft rejects the WHOLE items file if any case
     *  points at a path with an upper-case letter, a space or other illegal character - every model then
     *  turns purple/black. The custom_model_data STRING may stay as written; only the path is sanitised. */
    static String path(String name) {
        String p = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
        return p.isEmpty() ? "model" : p;
    }

    public PackGenerator(FirearmsPlugin plugin, Registry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    public Result generate() throws IOException {
        File dir = new File(plugin.getDataFolder(), "models");
        dir.mkdirs();
        Set<String> done = new LinkedHashSet<>();
        for (GunType g : registry.guns()) model(dir, g.model(), "gun", done);
        for (MagType m : registry.mags()) model(dir, m.model(), "mag", done);
        for (AmmoType a : registry.ammos()) model(dir, a.model(), "ammo", done);
        model(dir, plugin.getConfig().getString("craters.model", "crater"), "crater", done);

        for (ArmorType v : registry.vests()) vest(dir, v, done);
        File customFlash = new File(dir, "muzzle_flash.png");
        files.put("assets/" + NS + "/textures/item/muzzle_flash.png", customFlash.exists() ? Files.readAllBytes(customFlash.toPath()) : flashPng());

        // items/<base>.json (guns, mags, ammo, crater) and items/<armour>.json (vests): model by custom_model_data string.
        itemsFile(registry.base().getKey().getKey(), cmdToModel, true);
        itemsFile(registry.vestBase().getKey().getKey(), vestCmdToModel, false);
        report.add(0, "Firearms pack report - " + new java.util.Date());
        report.add(1, "custom_model_data string  ->  model path");
        report.add("");
        report.add("files in " + dir.getPath() + ":");
        File[] listing = dir.listFiles();
        if (listing != null) for (File f : listing) report.add("  " + f.getName() + (f.getName().endsWith(".bbmodel") && !f.getName().equals(f.getName().toLowerCase(Locale.ROOT)) ? "   <- NOTE: upper-case letters; the gun's model: must match this name exactly" : ""));
        report.add("");
        report.add("warnings:");
        if (warnings.isEmpty()) report.add("  none"); else for (String w : warnings) report.add("  ! " + w);
        put("pack-report.txt", String.join("\n", report));
        try { Files.writeString(new File(plugin.getDataFolder(), "pack-report.txt").toPath(), String.join("\n", report)); } catch (IOException ignored) { }

        JsonObject pack = new JsonObject();
        JsonObject p = new JsonObject();
        p.addProperty("description", "Firearms (generated)");
        int min = plugin.getConfig().getInt("pack.min-format", 46), max = plugin.getConfig().getInt("pack.max-format", 999);
        p.addProperty("pack_format", min);
        p.addProperty("min_format", min);
        p.addProperty("max_format", max);
        pack.add("pack", p);
        put("pack.mcmeta", GSON.toJson(pack));

        // anim-index.yml for the plugin
        YamlConfiguration idx = new YamlConfiguration();
        for (var m : animIndex.entrySet()) for (var c : m.getValue().entrySet()) {
            idx.set(m.getKey() + "." + c.getKey() + ".frames", c.getValue()[0]);
            idx.set(m.getKey() + "." + c.getKey() + ".frame-ticks", c.getValue()[1]);
        }
        idx.save(new File(dir, "anim-index.yml"));

        File zip = new File(plugin.getDataFolder(), "Firearms-pack.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            for (var e : files.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return new Result(models, frames, placeholders, warnings, zip);
    }

    private void put(String path, String text) { files.put(path, text.getBytes(StandardCharsets.UTF_8)); }

    private void itemsFile(String base, Map<String, String> map, boolean noSwapAnimation) {
        JsonArray cases = new JsonArray();
        Set<String> seen = new LinkedHashSet<>();
        for (var e : map.entrySet()) {
            if (!seen.add(e.getKey())) continue;                  // duplicate "when" would be rejected by the client
            // Final safety net: a single bad case makes the client drop the WHOLE items file (every model purple).
            if (!e.getValue().matches("[a-z0-9_./-]+") || !files.containsKey("assets/" + NS + "/models/item/" + e.getValue() + ".json")) {
                warnings.add("case '" + e.getKey() + "' skipped - model path '" + e.getValue() + "' is invalid or its file was not generated");
                continue;
            }
            JsonObject c = new JsonObject();
            c.addProperty("when", e.getKey());
            JsonObject m = new JsonObject();
            m.addProperty("type", "minecraft:model");
            m.addProperty("model", NS + ":item/" + e.getValue());
            c.add("model", m);
            cases.add(c);
            report.add(base + ": \"" + e.getKey() + "\" -> " + NS + ":item/" + e.getValue());
        }
        JsonObject select = new JsonObject();
        select.addProperty("type", "minecraft:select");
        select.addProperty("property", "minecraft:custom_model_data");
        select.add("cases", cases);
        JsonObject fallback = new JsonObject();
        fallback.addProperty("type", "minecraft:model");
        fallback.addProperty("model", "minecraft:item/" + base);
        select.add("fallback", fallback);
        JsonObject root = new JsonObject();
        root.add("model", select);
        // The client plays the hand-swap dip whenever the held stack changes (every shot changes the ammo
        // count on the durability bar, every clip frame changes the model). This root property turns it off,
        // so the gun stays on screen and the baked fire/reload frames are actually visible.
        if (noSwapAnimation) root.addProperty("hand_animation_on_swap", false);
        put("assets/minecraft/items/" + base + ".json", GSON.toJson(root));
    }

    /** Vest item icon: its .bbmodel if present, else a generated flat icon (worn look = the dyed chestplate). */
    private void vest(File dir, ArmorType v, Set<String> done) throws IOException {
        String name = v.model();
        if (!done.add(name)) return;
        File bb = new File(dir, name + ".bbmodel");
        if (bb.exists()) {
            try {
                Map<String, String> saved = new LinkedHashMap<>(cmdToModel);
                convert(bb, name);
                for (var e : cmdToModel.entrySet()) if (!saved.containsKey(e.getKey())) vestCmdToModel.put(e.getKey(), e.getValue());
                cmdToModel.clear(); cmdToModel.putAll(saved);
                models++;
                return;
            } catch (Exception ex) {
                warnings.add(name + ".bbmodel could not be converted (" + ex.getMessage() + ") - placeholder used");
            }
        }
        String p = path(name);
        File custom = new File(dir, name + ".png");
        files.put("assets/" + NS + "/textures/item/" + p + ".png", custom.exists() ? Files.readAllBytes(custom.toPath()) : vestPng(v.color()));
        JsonObject model = new JsonObject();
        model.addProperty("parent", "minecraft:item/generated");
        JsonObject tex = new JsonObject();
        tex.addProperty("layer0", NS + ":item/" + p);
        model.add("textures", tex);
        put("assets/" + NS + "/models/item/" + p + ".json", GSON.toJson(model));
        vestCmdToModel.put(name, p);
        placeholders++;
    }

    private static byte[] vestPng(int rgb) throws IOException {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        for (int y = 2; y < 15; y++) for (int x = 2; x < 14; x++) {
            boolean neck = y < 5 && x > 5 && x < 10, arm = y < 6 && (x < 4 || x > 11);
            if (neck || arm) continue;
            int shade = (x == 2 || x == 13 || y == 14 || (y == 6 && (x < 4 || x > 11))) ? 60 : 100;
            img.setRGB(x, y, (255 << 24) | (r * shade / 100 << 16) | (g * shade / 100 << 8) | (b * shade / 100));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private void model(File dir, String name, String kind, Set<String> done) throws IOException {
        if (name == null || name.isEmpty() || !done.add(name)) return;
        File bb = new File(dir, name + ".bbmodel");
        if (bb.exists()) {
            try {
                convert(bb, name);
                models++;
                return;
            } catch (Exception ex) {
                warnings.add(name + ".bbmodel could not be converted (" + ex.getMessage() + ") - placeholder used");
            }
        }
        placeholder(dir, name, kind);
        placeholders++;
    }

    // ------------------------------------------------------------------ placeholders

    private void placeholder(File dir, String name, String kind) throws IOException {
        JsonObject model = new JsonObject();
        JsonObject tex = new JsonObject();
        JsonArray elements = new JsonArray();
        switch (kind) {
            case "crater" -> {
                File custom = new File(dir, name + ".png");
                byte[] png = custom.exists() ? Files.readAllBytes(custom.toPath()) : craterPng();
                files.put("assets/" + NS + "/textures/item/" + path(name) + ".png", png);
                tex.addProperty("0", NS + ":item/" + path(name));
                tex.addProperty("particle", NS + ":item/" + path(name));
                elements.add(cube(new double[]{0, 0, 7.9}, new double[]{16, 16, 8.1}, "#0", new double[]{0, 0, 16, 16}, null));
            }
            case "mag" -> {
                tex.addProperty("0", "minecraft:block/iron_block");
                tex.addProperty("particle", "minecraft:block/iron_block");
                elements.add(cube(new double[]{6.5, 3, 7}, new double[]{9.5, 13, 9}, "#0", new double[]{0, 0, 4, 10}, null));
            }
            case "ammo" -> {
                tex.addProperty("0", "minecraft:block/gold_block");
                tex.addProperty("particle", "minecraft:block/gold_block");
                elements.add(cube(new double[]{7, 5, 7.5}, new double[]{9, 11, 8.5}, "#0", new double[]{0, 0, 2, 6}, null));
            }
            default -> {
                tex.addProperty("0", "minecraft:block/polished_blackstone");
                tex.addProperty("1", "minecraft:block/iron_block");
                tex.addProperty("particle", "minecraft:block/polished_blackstone");
                elements.add(cube(new double[]{7, 7, -2}, new double[]{9, 10, 16}, "#0", new double[]{0, 0, 2, 16}, null));    // body
                elements.add(cube(new double[]{7.3, 3, 10}, new double[]{8.7, 7, 12.5}, "#1", new double[]{0, 0, 2, 4}, null)); // grip
                elements.add(cube(new double[]{7.5, 8.5, -6}, new double[]{8.5, 9.5, -2}, "#1", new double[]{0, 0, 1, 4}, null)); // barrel
            }
        }
        model.add("textures", tex);
        model.add("elements", elements);
        model.add("display", defaultDisplay(1.0));
        put("assets/" + NS + "/models/item/" + path(name) + ".json", GSON.toJson(model));
        cmdToModel.put(name, path(name));
    }

    private static JsonObject cube(double[] from, double[] to, String texture, double[] uv, JsonObject rotation) {
        JsonObject e = new JsonObject();
        e.add("from", arr(from));
        e.add("to", arr(to));
        if (rotation != null) e.add("rotation", rotation);
        JsonObject faces = new JsonObject();
        for (String f : new String[]{"north", "south", "east", "west", "up", "down"}) {
            JsonObject face = new JsonObject();
            face.add("uv", arr(uv));
            face.addProperty("texture", texture);
            faces.add(f, face);
        }
        e.add("faces", faces);
        return e;
    }

    private static byte[] flashPng() throws IOException {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            double d = Math.hypot(x - 7.5, y - 7.5) / 7.5;
            int a = d > 1 ? 0 : (int) (255 * Math.pow(1 - d, 1.5));
            int g = 200 + (int) (55 * (1 - d)), b = (int) (120 * (1 - d));
            img.setRGB(x, y, (a << 24) | (255 << 16) | (g << 8) | b);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static byte[] craterPng() throws IOException {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            double d = Math.hypot(x - 7.5, y - 7.5) / 7.5;
            int a = d > 1 ? 0 : (int) (200 * (1 - d * d));
            int v = 20 + (int) (30 * d);
            img.setRGB(x, y, (a << 24) | (v << 16) | (v << 8) | v);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static JsonArray arr(double... v) {
        JsonArray a = new JsonArray();
        for (double d : v) a.add(Math.round(d * 1000.0) / 1000.0);
        return a;
    }

    private static JsonObject transform(double[] rot, double[] tr, double[] sc) {
        JsonObject o = new JsonObject();
        o.add("rotation", arr(rot));
        o.add("translation", arr(tr));
        o.add("scale", arr(sc));
        return o;
    }

    private static JsonObject defaultDisplay(double s) {
        JsonObject d = new JsonObject();
        d.add("firstperson_righthand", transform(new double[]{0, 0, 0}, new double[]{1, 1, 1}, new double[]{0.55 * s, 0.55 * s, 0.55 * s}));
        d.add("firstperson_lefthand", transform(new double[]{0, 0, 0}, new double[]{1, 1, 1}, new double[]{0.55 * s, 0.55 * s, 0.55 * s}));
        d.add("thirdperson_righthand", transform(new double[]{0, 0, 0}, new double[]{0, 2, 1}, new double[]{0.5 * s, 0.5 * s, 0.5 * s}));
        d.add("thirdperson_lefthand", transform(new double[]{0, 0, 0}, new double[]{0, 2, 1}, new double[]{0.5 * s, 0.5 * s, 0.5 * s}));
        d.add("gui", transform(new double[]{30, 135, 0}, new double[]{0, 0, 0}, new double[]{0.6 * s, 0.6 * s, 0.6 * s}));
        d.add("ground", transform(new double[]{0, 0, 0}, new double[]{0, 2, 0}, new double[]{0.4 * s, 0.4 * s, 0.4 * s}));
        d.add("fixed", transform(new double[]{0, 0, 0}, new double[]{0, 0, 0}, new double[]{0.7 * s, 0.7 * s, 0.7 * s}));
        return d;
    }

    // ------------------------------------------------------------------ bbmodel conversion

    private static final class Bone {
        String uuid, name; double[] origin = {0, 0, 0}; double[] rotation = {0, 0, 0};
        Bone parent; List<Bone> children = new ArrayList<>(); List<String> elements = new ArrayList<>();
    }

    private static final class Cube {
        String uuid; double[] from, to, origin = {0, 0, 0}, rotation = {0, 0, 0}; JsonObject faces; Bone bone;
    }

    private static final class Clip {
        String name; double length; Map<String, List<JsonObject>> rot = new HashMap<>(), pos = new HashMap<>(); // bone uuid -> keyframes
    }

    private void convert(File bb, String name) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(bb.toPath())).getAsJsonObject();
        final String mpath = path(name);
        if (!mpath.equals(name)) warnings.add("'" + name + "' is not a valid model path - files written as '" + mpath + "' (the custom_model_data string stays '" + name + "')");
        double resW = 16, resH = 16;
        if (root.has("resolution")) {
            JsonObject r = root.getAsJsonObject("resolution");
            resW = num(r.get("width"), 16); resH = num(r.get("height"), 16);
        }
        // --- textures
        Map<String, Integer> texIndex = new HashMap<>();      // uuid / id -> index
        List<double[]> texRes = new ArrayList<>();
        JsonObject textures = new JsonObject();
        int ti = 0;
        if (root.has("textures")) for (JsonElement te : root.getAsJsonArray("textures")) {
            JsonObject t = te.getAsJsonObject();
            String tname = str(t.get("name"), "tex" + ti).replaceAll("\\.png$", "").replaceAll("[^a-z0-9_]", "_").toLowerCase(Locale.ROOT);
            String src = str(t.get("source"), "");
            if (src.contains("base64,")) {
                files.put("assets/" + NS + "/textures/item/" + mpath + "/" + tname + ".png", Base64.getDecoder().decode(src.substring(src.indexOf("base64,") + 7)));
            } else {
                warnings.add(name + ": texture " + tname + " has no embedded image");
            }
            textures.addProperty(String.valueOf(ti), NS + ":item/" + mpath + "/" + tname);
            if (ti == 0) textures.addProperty("particle", NS + ":item/" + mpath + "/" + tname);
            texIndex.put(str(t.get("uuid"), "u" + ti), ti);
            texIndex.put(str(t.get("id"), String.valueOf(ti)), ti);
            texRes.add(new double[]{ num(t.get("uv_width"), resW), num(t.get("uv_height"), resH) });
            ti++;
        }
        if (ti == 0) { textures.addProperty("0", "minecraft:block/iron_block"); textures.addProperty("particle", "minecraft:block/iron_block"); texRes.add(new double[]{resW, resH}); }

        // --- cubes
        Map<String, Cube> cubes = new LinkedHashMap<>();
        if (root.has("elements")) for (JsonElement ee : root.getAsJsonArray("elements")) {
            JsonObject e = ee.getAsJsonObject();
            if (e.has("type") && !"cube".equals(str(e.get("type"), "cube"))) continue;
            if (e.has("visibility") && !e.get("visibility").getAsBoolean()) continue;
            Cube c = new Cube();
            c.uuid = str(e.get("uuid"), "e" + cubes.size());
            c.from = vec(e.get("from")); c.to = vec(e.get("to"));
            if (e.has("origin")) c.origin = vec(e.get("origin"));
            if (e.has("rotation")) c.rotation = vec(e.get("rotation"));
            c.faces = e.has("faces") ? e.getAsJsonObject("faces") : new JsonObject();
            cubes.put(c.uuid, c);
        }
        // --- bones
        List<Bone> top = new ArrayList<>();
        Map<String, Bone> bones = new HashMap<>();
        if (root.has("outliner")) for (JsonElement oe : root.getAsJsonArray("outliner")) parseOutliner(oe, null, top, bones, cubes);
        Bone rootBone = null;
        int best = 0;
        for (Bone b : top) { int n = countCubes(b); if (n > best) { best = n; rootBone = b; } }

        // --- scale to the Java range [-16, 32]
        double[] mn = {1e9, 1e9, 1e9}, mx = {-1e9, -1e9, -1e9};
        for (Cube c : cubes.values()) for (int i = 0; i < 3; i++) { mn[i] = Math.min(mn[i], Math.min(c.from[i], c.to[i])); mx[i] = Math.max(mx[i], Math.max(c.from[i], c.to[i])); }
        double extent = Math.max(mx[0] - mn[0], Math.max(mx[1] - mn[1], mx[2] - mn[2]));
        double scale = 1.0;
        double[] shift = {0, 0, 0};
        boolean outside = false;
        for (int i = 0; i < 3; i++) if (mn[i] < -16 || mx[i] > 32) outside = true;
        if (outside && extent > 0) {
            scale = Math.min(1.0, 46.0 / extent);
            for (int i = 0; i < 3; i++) shift[i] = 8 - ((mn[i] + mx[i]) / 2) * scale;
            warnings.add(name + ": geometry exceeds the Java model range, scaled by " + Math.round(scale * 100) / 100.0 + " (display scale compensated)");
        }
        final double S = scale; final double[] SH = shift;
        for (Cube c : cubes.values()) { c.from = fit(c.from, S, SH); c.to = fit(c.to, S, SH); c.origin = fit(c.origin, S, SH); }
        for (Bone b : bones.values()) b.origin = fit(b.origin, S, SH);

        // --- display
        JsonObject display = root.has("display") ? displayFrom(root.getAsJsonObject("display"), 1.0 / scale) : defaultDisplay(1.0 / scale);
        org.bukkit.configuration.ConfigurationSection ov = displayOverride(name);
        if (ov != null) {
            for (String key : ov.getKeys(false)) {
                org.bukkit.configuration.ConfigurationSection t = ov.getConfigurationSection(key);
                if (t == null) continue;
                JsonObject cur = display.has(key) ? display.getAsJsonObject(key) : transform(new double[]{0, 0, 0}, new double[]{0, 0, 0}, new double[]{1, 1, 1});
                double[] r = list3(t, "rotation", vec(cur.get("rotation"))), tr = list3(t, "translation", vec(cur.get("translation"))), sc = list3(t, "scale", vec(cur.get("scale")));
                display.add(key, transform(r, tr, sc));
            }
        }
        double[] flashAt = flashSpot(name, cubes);

        // --- rest model
        JsonObject rest = bake(name, cubes, bones, rootBone, textures, texIndex, texRes, display, null, 0, S, null);
        put("assets/" + NS + "/models/item/" + mpath + ".json", GSON.toJson(rest));
        cmdToModel.put(name, mpath);

        // --- animations -> frames
        int frameTicks = Math.max(1, plugin.getConfig().getInt("anim.frame-ticks", 1));
        Map<String, int[]> clips = new LinkedHashMap<>();
        if (root.has("animations")) for (JsonElement ae : root.getAsJsonArray("animations")) {
            JsonObject a = ae.getAsJsonObject();
            Clip clip = new Clip();
            clip.name = str(a.get("name"), "anim");
            clip.length = num(a.get("length"), 0);
            String key = clipKey(clip.name);
            if (clips.containsKey(key)) { warnings.add(name + ": animation '" + clip.name + "' ignored - '" + key + "' already has one"); continue; }
            if (a.has("animators")) for (var en : a.getAsJsonObject("animators").entrySet()) {
                JsonObject an = en.getValue().getAsJsonObject();
                if (!an.has("keyframes")) continue;
                for (JsonElement ke : an.getAsJsonArray("keyframes")) {
                    JsonObject k = ke.getAsJsonObject();
                    String ch = str(k.get("channel"), "");
                    if (ch.equals("rotation")) clip.rot.computeIfAbsent(en.getKey(), x -> new ArrayList<>()).add(k);
                    else if (ch.equals("position")) clip.pos.computeIfAbsent(en.getKey(), x -> new ArrayList<>()).add(k);
                }
            }
            int n = (int) Math.max(1, Math.min(60, Math.ceil(clip.length * 20.0 / frameTicks)));
            for (int i = 1; i <= n; i++) {
                double t = n == 1 ? clip.length : (i - 1) * (clip.length / (n - 1));
                boolean flashFrame = key.equals("fire") && i <= Math.max(0, plugin.getConfig().getInt("flash.frames", 1));
                JsonObject frame = bake(name, cubes, bones, rootBone, textures, texIndex, texRes, display, clip, t, S, flashFrame ? flashAt : null);
                String fname = name + "_" + key + "_" + i;
                put("assets/" + NS + "/models/item/" + mpath + "_" + key + "_" + i + ".json", GSON.toJson(frame));
                cmdToModel.put(fname, mpath + "_" + key + "_" + i);
                frames++;
            }
            clips.put(key, new int[]{n, frameTicks});
        }
        if (!clips.isEmpty()) animIndex.put(name, clips);
    }

    private static String clipKey(String animName) {
        String n = animName.toLowerCase(Locale.ROOT);
        if (n.contains("shoot") || n.contains("fire") || n.contains("recoil")) return "fire";
        if (n.contains("reload")) return "reload";
        if (n.contains("equip") || n.contains("draw") || n.contains("deploy") || n.contains("pull")) return "equip";
        if (n.contains("pump") || n.contains("rack") || n.contains("cock") || n.contains("bolt")) return "pump";
        return n.replaceAll("[^a-z0-9_]", "_");
    }

    private static void parseOutliner(JsonElement oe, Bone parent, List<Bone> top, Map<String, Bone> bones, Map<String, Cube> cubes) {
        if (oe.isJsonPrimitive()) {
            Cube c = cubes.get(oe.getAsString());
            if (c != null && parent != null) { c.bone = parent; parent.elements.add(c.uuid); }
            return;
        }
        JsonObject o = oe.getAsJsonObject();
        Bone b = new Bone();
        b.uuid = str(o.get("uuid"), "b" + bones.size());
        b.name = str(o.get("name"), b.uuid);
        if (o.has("origin")) b.origin = vec(o.get("origin"));
        if (o.has("rotation")) b.rotation = vec(o.get("rotation"));
        b.parent = parent;
        bones.put(b.uuid, b);
        if (parent == null) top.add(b); else parent.children.add(b);
        if (o.has("children")) for (JsonElement ce : o.getAsJsonArray("children")) parseOutliner(ce, b, top, bones, cubes);
    }

    private static double[] fit(double[] v, double s, double[] sh) {
        return new double[]{ v[0] * s + sh[0], v[1] * s + sh[1], v[2] * s + sh[2] };
    }

    /** One baked bone: single-axis snapped rotation about its pivot + exact translation. */
    private static final class Baked {
        int axis = -1; double angle = 0;   // snapped rotation (Java: one axis, multiples of 22.5, |angle| <= 45)
        double[] pivot;                    // where that rotation happens, in parent-translated space
        double[][] noRot;                  // world transform WITHOUT any rotation (translations only)
        double[][] full;                   // world transform with the snapped rotations applied
    }

    private static int countCubes(Bone b) {
        int n = b.elements.size();
        for (Bone c : b.children) n += countCubes(c);
        return n;
    }

    private Baked bakeBone(Bone b, Map<Bone, Baked> cache, Map<Bone, double[]> rot, Map<Bone, double[]> pos, String name, Set<String> warned) {
        Baked done = cache.get(b);
        if (done != null) return done;
        Baked parent = b.parent == null ? null : bakeBone(b.parent, cache, rot, pos, name, warned);
        double[] r = rot.getOrDefault(b, new double[]{0, 0, 0}), p = pos.getOrDefault(b, new double[]{0, 0, 0});
        Baked out = new Baked();
        int axis = 0;
        for (int i = 1; i < 3; i++) if (Math.abs(r[i]) > Math.abs(r[axis])) axis = i;
        double raw = r[axis];
        double snapped = Math.max(-45, Math.min(45, Math.round(raw / 22.5) * 22.5));
        boolean multi = false;
        for (int i = 0; i < 3; i++) if (i != axis && Math.abs(r[i]) > 1) multi = true;
        if (multi && warned.add(b.name + ":multi")) warnings.add(name + ": bone '" + b.name + "' rotates on several axes - Java cubes allow one, using " + "xyz".charAt(axis));
        if (Math.abs(raw) > 46 && warned.add(b.name + ":clamp")) warnings.add(name + ": bone '" + b.name + "' rotates " + Math.round(raw) + " deg - clamped to 45 (Java limit)");
        double[][] parentNoRot = parent == null ? identity() : parent.noRot;
        double[][] parentFull = parent == null ? identity() : parent.full;
        double[][] shift = translate(p[0], p[1], p[2]);
        out.noRot = mul(parentNoRot, shift);
        double[] pivotLocal = {b.origin[0] + p[0], b.origin[1] + p[1], b.origin[2] + p[2]};
        out.pivot = apply(parentNoRot, pivotLocal);
        if (snapped != 0) {
            out.axis = axis; out.angle = snapped;
            double[] e = {0, 0, 0}; e[axis] = snapped;
            out.full = mul(parentFull, mul(mul(translate(pivotLocal[0], pivotLocal[1], pivotLocal[2]), rotZYX(e)), translate(-b.origin[0], -b.origin[1], -b.origin[2])));
        } else {
            out.full = mul(parentFull, shift);
        }
        cache.put(b, out);
        return out;
    }

    /** Build one model: rest pose (clip == null) or the clip sampled at time t. Bone rotations are snapped to
     *  what a Java cube can do (one axis, 22.5 deg steps) and the SAME snapped rotation is applied to every cube
     *  of the bone about the bone's pivot, so parts never drift apart; translations are exact. The root bone's
     *  animated motion goes into the first-person display transform instead (exact, any angle). */
    private JsonObject bake(String name, Map<String, Cube> cubes, Map<String, Bone> bones, Bone rootBone, JsonObject textures,
                            Map<String, Integer> texIndex, List<double[]> texRes, JsonObject display, Clip clip, double t, double scale, double[] flashAt) {
        Map<Bone, double[]> rot = new HashMap<>(), pos = new HashMap<>();
        double[] rootRot = {0, 0, 0}, rootPos = {0, 0, 0};
        for (Bone b : bones.values()) {
            double[] r = b.rotation.clone(), p = {0, 0, 0};
            if (clip != null) {
                double[] ar = sample(clip.rot.get(b.uuid), t), ap = sample(clip.pos.get(b.uuid), t);
                if (b == rootBone) { rootRot = ar; rootPos = new double[]{ap[0] * scale, ap[1] * scale, ap[2] * scale}; }
                else for (int i = 0; i < 3; i++) { r[i] += ar[i]; p[i] += ap[i] * scale; }
            }
            rot.put(b, r); pos.put(b, p);
        }
        Map<Bone, Baked> baked = new HashMap<>();
        Set<String> warned = new LinkedHashSet<>();
        JsonArray elements = new JsonArray();
        for (Cube c : cubes.values()) {
            Baked bb = c.bone == null ? null : bakeBone(c.bone, baked, rot, pos, name, warned);
            // The ONE rotation a Java cube gets: the nearest rotated bone up the chain (angles on the same axis
            // further up are folded in), else the cube's own rest rotation.
            int axis = -1; double angle = 0; double[] pivot = null;
            double[][] place = bb == null ? identity() : bb.noRot;   // translations only; the rotation is re-applied by the element
            for (Bone w = c.bone; w != null; w = w.parent) {
                Baked wb = baked.get(w);
                if (wb == null || wb.axis < 0) continue;
                if (axis < 0) { axis = wb.axis; angle = wb.angle; pivot = wb.pivot; }
                else if (wb.axis == axis) angle = Math.max(-45, Math.min(45, angle + wb.angle));
                else if (warned.add(c.uuid + ":nest")) warnings.add(name + ": nested bones rotate on different axes around '" + w.name + "' - approximated");
            }
            if (c.rotation[0] != 0 || c.rotation[1] != 0 || c.rotation[2] != 0) {
                int ca = 0;
                for (int i = 1; i < 3; i++) if (Math.abs(c.rotation[i]) > Math.abs(c.rotation[ca])) ca = i;
                double cs = Math.max(-45, Math.min(45, Math.round(c.rotation[ca] / 22.5) * 22.5));
                if (axis < 0) { axis = ca; angle = cs; pivot = apply(place, c.origin); }
                else if (ca == axis) angle = Math.max(-45, Math.min(45, angle + cs));
            }
            double[] from = apply(place, c.from), to = apply(place, c.to);
            for (int i = 0; i < 3; i++) if (from[i] > to[i]) { double x = from[i]; from[i] = to[i]; to[i] = x; }
            JsonObject rotation = null;
            if (axis >= 0 && angle != 0) {
                rotation = new JsonObject();
                rotation.add("origin", arr(pivot));
                rotation.addProperty("axis", axis == 0 ? "x" : axis == 1 ? "y" : "z");
                rotation.addProperty("angle", angle);
            }
            boolean inRange = true;
            for (int i = 0; i < 3; i++) if (from[i] < -16 || from[i] > 32 || to[i] < -16 || to[i] > 32) inRange = false;
            if (!inRange) {   // one bad cube would make the client reject the whole model: clamp it instead
                for (int i = 0; i < 3; i++) { from[i] = Math.max(-16, Math.min(32, from[i])); to[i] = Math.max(-16, Math.min(32, to[i])); }
                if (clip == null) warnings.add(name + ": a cube lies outside the Java model range and was clamped");
            }
            JsonObject e = new JsonObject();
            e.add("from", arr(from));
            e.add("to", arr(to));
            if (rotation != null) e.add("rotation", rotation);
            JsonObject faces = new JsonObject();
            for (String f : new String[]{"north", "south", "east", "west", "up", "down"}) {
                if (!c.faces.has(f)) continue;
                JsonObject bf = c.faces.getAsJsonObject(f);
                JsonElement tex = bf.get("texture");
                if (tex == null || tex.isJsonNull()) continue;
                Integer idx = tex.isJsonPrimitive() && tex.getAsJsonPrimitive().isNumber() ? Integer.valueOf(tex.getAsInt()) : texIndex.get(tex.getAsString());
                if (idx == null) idx = 0;
                double[] res = idx < texRes.size() ? texRes.get(idx) : new double[]{16, 16};
                double[] uv = bf.has("uv") ? vec4(bf.get("uv")) : new double[]{0, 0, res[0], res[1]};
                JsonObject face = new JsonObject();
                face.add("uv", arr(uv[0] * 16 / res[0], uv[1] * 16 / res[1], uv[2] * 16 / res[0], uv[3] * 16 / res[1]));
                face.addProperty("texture", "#" + idx);
                if (bf.has("rotation")) face.addProperty("rotation", (int) num(bf.get("rotation"), 0));
                faces.add(f, face);
            }
            e.add("faces", faces);
            elements.add(e);
        }
        JsonObject tex = textures;
        if (flashAt != null) {
            tex = textures.deepCopy();
            tex.addProperty("flash", NS + ":item/muzzle_flash");
            double sz = plugin.getConfig().getDouble("flash.size", 3.0) / 2;
            JsonObject f = cube(new double[]{flashAt[0] - sz, flashAt[1] - sz, flashAt[2] - sz * 1.6}, new double[]{flashAt[0] + sz, flashAt[1] + sz, flashAt[2] + sz * 1.6}, "#flash", new double[]{0, 0, 16, 16}, null);
            f.addProperty("shade", false);
            elements.add(f);
        }
        JsonObject model = new JsonObject();
        model.add("textures", tex);
        model.add("elements", elements);
        JsonObject disp = display.deepCopy();
        if (clip != null && rootBone != null) {
            double[] rs = sign("anim.root-rotation-sign"), ps = sign("anim.root-position-sign");
            for (String hand : new String[]{"firstperson_righthand", "firstperson_lefthand"}) {
                JsonObject h = disp.has(hand) ? disp.getAsJsonObject(hand) : transform(new double[]{0, 0, 0}, new double[]{0, 0, 0}, new double[]{1, 1, 1});
                double[] r = vec(h.get("rotation")), tr = vec(h.get("translation"));
                double mirror = hand.endsWith("lefthand") ? -1 : 1;
                h.add("rotation", arr(r[0] + rootRot[0] * rs[0], r[1] + rootRot[1] * rs[1] * mirror, r[2] + rootRot[2] * rs[2] * mirror));
                h.add("translation", arr(clamp80(tr[0] + rootPos[0] * ps[0] * mirror), clamp80(tr[1] + rootPos[1] * ps[1]), clamp80(tr[2] + rootPos[2] * ps[2])));
                disp.add(hand, h);
            }
        }
        model.add("display", disp);
        return model;
    }

    private double[] sign(String key) {
        List<Double> l = plugin.getConfig().getDoubleList(key);
        return l.size() == 3 ? new double[]{l.get(0), l.get(1), l.get(2)} : new double[]{1, 1, 1};
    }

    private org.bukkit.configuration.ConfigurationSection displayOverride(String model) {
        for (GunType g : registry.guns()) if (g.model().equals(model) && g.display() != null) return g.display();
        return null;
    }

    private static double[] list3(org.bukkit.configuration.ConfigurationSection s, String key, double[] def) {
        List<Double> l = s.getDoubleList(key);
        return l.size() == 3 ? new double[]{l.get(0), l.get(1), l.get(2)} : def;
    }

    /** Flash position in model px: guns.yml `flash: x,y,z`, else the centre of the model's front (north, -Z) face. */
    private double[] flashSpot(String model, Map<String, Cube> cubes) {
        for (GunType g : registry.guns()) if (g.model().equals(model) && g.flashAt() != null) return g.flashAt();
        if (cubes.isEmpty()) return new double[]{8, 8, -2};
        double minZ = 1e9, sx = 0, sy = 0; int n = 0;
        for (Cube c : cubes.values()) minZ = Math.min(minZ, Math.min(c.from[2], c.to[2]));
        for (Cube c : cubes.values()) if (Math.min(c.from[2], c.to[2]) <= minZ + 0.5) { sx += (c.from[0] + c.to[0]) / 2; sy += (c.from[1] + c.to[1]) / 2; n++; }
        return new double[]{ n == 0 ? 8 : sx / n, n == 0 ? 8 : sy / n, minZ - 1.5 };
    }

    private static double clamp80(double v) { return Math.max(-80, Math.min(80, v)); }

    private static JsonObject displayFrom(JsonObject bbDisplay, double comp) {
        JsonObject d = defaultDisplay(comp);
        for (var en : bbDisplay.entrySet()) {
            if (!en.getValue().isJsonObject()) continue;
            JsonObject o = en.getValue().getAsJsonObject();
            double[] r = o.has("rotation") ? vec(o.get("rotation")) : new double[]{0, 0, 0};
            double[] tr = o.has("translation") ? vec(o.get("translation")) : new double[]{0, 0, 0};
            double[] sc = o.has("scale") ? vec(o.get("scale")) : new double[]{1, 1, 1};
            for (int i = 0; i < 3; i++) { sc[i] = Math.min(4, sc[i] * comp); tr[i] = clamp80(tr[i]); }
            d.add(en.getKey(), transform(r, tr, sc));
        }
        return d;
    }

    /** Linear sample of a channel's keyframes at time t (seconds); zeros if none. */
    private static double[] sample(List<JsonObject> keys, double t) {
        if (keys == null || keys.isEmpty()) return new double[]{0, 0, 0};
        keys.sort((a, b) -> Double.compare(num(a.get("time"), 0), num(b.get("time"), 0)));
        JsonObject prev = null, next = null;
        for (JsonObject k : keys) {
            double kt = num(k.get("time"), 0);
            if (kt <= t) prev = k;
            if (kt >= t) { next = k; break; }
        }
        if (prev == null) return point(next);
        if (next == null || prev == next) return point(prev);
        double t0 = num(prev.get("time"), 0), t1 = num(next.get("time"), 0);
        double f = t1 - t0 < 1e-6 ? 0 : (t - t0) / (t1 - t0);
        double[] a = point(prev), b = point(next);
        return new double[]{ a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f };
    }

    private static double[] point(JsonObject k) {
        if (k == null || !k.has("data_points")) return new double[]{0, 0, 0};
        JsonArray dp = k.getAsJsonArray("data_points");
        if (dp.isEmpty()) return new double[]{0, 0, 0};
        JsonObject p = dp.get(0).getAsJsonObject();
        return new double[]{ num(p.get("x"), 0), num(p.get("y"), 0), num(p.get("z"), 0) };
    }

    // ------------------------------------------------------------------ tiny matrix helpers (4x4, row-major)

    private static double[][] identity() { return new double[][]{{1,0,0,0},{0,1,0,0},{0,0,1,0},{0,0,0,1}}; }
    private static double[][] translate(double x, double y, double z) { double[][] m = identity(); m[0][3] = x; m[1][3] = y; m[2][3] = z; return m; }
    private static double[][] rotZYX(double[] deg) {
        double x = Math.toRadians(deg[0]), y = Math.toRadians(deg[1]), z = Math.toRadians(deg[2]);
        double[][] rx = {{1,0,0,0},{0,Math.cos(x),-Math.sin(x),0},{0,Math.sin(x),Math.cos(x),0},{0,0,0,1}};
        double[][] ry = {{Math.cos(y),0,Math.sin(y),0},{0,1,0,0},{-Math.sin(y),0,Math.cos(y),0},{0,0,0,1}};
        double[][] rz = {{Math.cos(z),-Math.sin(z),0,0},{Math.sin(z),Math.cos(z),0,0},{0,0,1,0},{0,0,0,1}};
        return mul(mul(rz, ry), rx);
    }
    private static double[][] mul(double[][] a, double[][] b) {
        double[][] r = new double[4][4];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) { double s = 0; for (int k = 0; k < 4; k++) s += a[i][k] * b[k][j]; r[i][j] = s; }
        return r;
    }
    private static double[] apply(double[][] m, double[] v) {
        return new double[]{ m[0][0]*v[0]+m[0][1]*v[1]+m[0][2]*v[2]+m[0][3], m[1][0]*v[0]+m[1][1]*v[1]+m[1][2]*v[2]+m[1][3], m[2][0]*v[0]+m[2][1]*v[1]+m[2][2]*v[2]+m[2][3] };
    }
    private static double[] euler(double[][] m) {
        double sy = -m[2][0];
        double y = Math.asin(Math.max(-1, Math.min(1, sy)));
        double x = Math.atan2(m[2][1], m[2][2]);
        double z = Math.atan2(m[1][0], m[0][0]);
        return new double[]{ Math.toDegrees(x), Math.toDegrees(y), Math.toDegrees(z) };
    }
    private static double[][] worldOf(Bone b, Map<Bone, double[][]> local, Map<Bone, double[][]> world) {
        double[][] w = world.get(b);
        if (w != null) return w;
        double[][] l = local.getOrDefault(b, identity());
        w = b.parent == null ? l : mul(worldOf(b.parent, local, world), l);
        world.put(b, w);
        return w;
    }

    // ------------------------------------------------------------------ json helpers

    private static double num(JsonElement e, double def) {
        if (e == null || e.isJsonNull()) return def;
        try {
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) return Double.parseDouble(e.getAsString().trim());
            return e.getAsDouble();
        } catch (Exception ex) { return def; }
    }
    private static String str(JsonElement e, String def) { return e == null || e.isJsonNull() ? def : e.getAsString(); }
    private static double[] vec(JsonElement e) {
        if (e == null || !e.isJsonArray()) return new double[]{0, 0, 0};
        JsonArray a = e.getAsJsonArray();
        return new double[]{ num(a.get(0), 0), num(a.get(1), 0), num(a.get(2), 0) };
    }
    private static double[] vec4(JsonElement e) {
        JsonArray a = e.getAsJsonArray();
        return new double[]{ num(a.get(0), 0), num(a.get(1), 0), num(a.get(2), 0), num(a.get(3), 0) };
    }
}
