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

        // items/<base>.json: pick the model by custom_model_data string.
        String base = registry.base().getKey().getKey();
        JsonArray cases = new JsonArray();
        for (var e : cmdToModel.entrySet()) {
            JsonObject c = new JsonObject();
            c.addProperty("when", e.getKey());
            JsonObject m = new JsonObject();
            m.addProperty("type", "minecraft:model");
            m.addProperty("model", NS + ":item/" + e.getValue());
            c.add("model", m);
            cases.add(c);
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
        put("assets/minecraft/items/" + base + ".json", GSON.toJson(root));

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
                files.put("assets/" + NS + "/textures/item/" + name + ".png", png);
                tex.addProperty("0", NS + ":item/" + name);
                tex.addProperty("particle", NS + ":item/" + name);
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
        put("assets/" + NS + "/models/item/" + name + ".json", GSON.toJson(model));
        cmdToModel.put(name, name);
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
                files.put("assets/" + NS + "/textures/item/" + name + "/" + tname + ".png", Base64.getDecoder().decode(src.substring(src.indexOf("base64,") + 7)));
            } else {
                warnings.add(name + ": texture " + tname + " has no embedded image");
            }
            textures.addProperty(String.valueOf(ti), NS + ":item/" + name + "/" + tname);
            if (ti == 0) textures.addProperty("particle", NS + ":item/" + name + "/" + tname);
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
        Bone rootBone = top.size() == 1 && top.get(0).children.size() + top.get(0).elements.size() > 0 && cubes.values().stream().allMatch(c -> c.bone != null) ? top.get(0) : null;

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

        // --- rest model
        JsonObject rest = bake(name, cubes, bones, rootBone, textures, texIndex, texRes, display, null, 0, S);
        put("assets/" + NS + "/models/item/" + name + ".json", GSON.toJson(rest));
        cmdToModel.put(name, name);

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
                JsonObject frame = bake(name, cubes, bones, rootBone, textures, texIndex, texRes, display, clip, t, S);
                String fname = name + "_" + key + "_" + i;
                put("assets/" + NS + "/models/item/" + fname + ".json", GSON.toJson(frame));
                cmdToModel.put(fname, fname);
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

    /** Build one model: rest pose (clip == null) or the clip sampled at time t. */
    private JsonObject bake(String name, Map<String, Cube> cubes, Map<String, Bone> bones, Bone rootBone, JsonObject textures,
                            Map<String, Integer> texIndex, List<double[]> texRes, JsonObject display, Clip clip, double t, double scale) {
        // per-bone local transform at time t
        Map<Bone, double[][]> local = new HashMap<>();
        double[] rootRot = {0, 0, 0}, rootPos = {0, 0, 0};
        for (Bone b : bones.values()) {
            double[] rot = b.rotation.clone(), pos = {0, 0, 0};
            if (clip != null) {
                double[] ar = sample(clip.rot.get(b.uuid), t), ap = sample(clip.pos.get(b.uuid), t);
                if (b == rootBone) { rootRot = ar; rootPos = new double[]{ap[0] * scale, ap[1] * scale, ap[2] * scale}; }
                else { for (int i = 0; i < 3; i++) { rot[i] += ar[i]; pos[i] += ap[i] * scale; } }
            }
            local.put(b, mul(mul(translate(b.origin[0] + pos[0], b.origin[1] + pos[1], b.origin[2] + pos[2]), rotZYX(rot)), translate(-b.origin[0], -b.origin[1], -b.origin[2])));
        }
        Map<Bone, double[][]> world = new HashMap<>();
        JsonArray elements = new JsonArray();
        for (Cube c : cubes.values()) {
            double[][] M = c.bone == null ? identity() : worldOf(c.bone, local, world);
            double[] center = {(c.from[0] + c.to[0]) / 2, (c.from[1] + c.to[1]) / 2, (c.from[2] + c.to[2]) / 2};
            double[] nc = apply(M, center);
            double[] size = {c.to[0] - c.from[0], c.to[1] - c.from[1], c.to[2] - c.from[2]};
            double[] from = {nc[0] - size[0] / 2, nc[1] - size[1] / 2, nc[2] - size[2] / 2};
            double[] to = {nc[0] + size[0] / 2, nc[1] + size[1] / 2, nc[2] + size[2] / 2};
            double[] eul = euler(M);
            double[] total = {eul[0] + c.rotation[0], eul[1] + c.rotation[1], eul[2] + c.rotation[2]};
            int axis = 0;
            for (int i = 1; i < 3; i++) if (Math.abs(total[i]) > Math.abs(total[axis])) axis = i;
            double snapped = Math.max(-45, Math.min(45, Math.round(total[axis] / 22.5) * 22.5));
            JsonObject rotation = null;
            if (snapped != 0) {
                rotation = new JsonObject();
                double[] origin = c.bone == null ? c.origin : apply(M, c.origin);
                // element rotates about its own origin at rest; under a bone, about the bone-moved origin
                rotation.add("origin", arr(origin));
                rotation.addProperty("axis", axis == 0 ? "x" : axis == 1 ? "y" : "z");
                rotation.addProperty("angle", snapped);
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
        JsonObject model = new JsonObject();
        model.add("textures", textures);
        model.add("elements", elements);
        JsonObject disp = display.deepCopy();
        if (clip != null && rootBone != null) {
            for (String hand : new String[]{"firstperson_righthand", "firstperson_lefthand"}) {
                JsonObject h = disp.has(hand) ? disp.getAsJsonObject(hand) : transform(new double[]{0, 0, 0}, new double[]{0, 0, 0}, new double[]{1, 1, 1});
                double[] r = vec(h.get("rotation")), tr = vec(h.get("translation")), sc = vec(h.get("scale"));
                h.add("rotation", arr(r[0] + rootRot[0], r[1] + rootRot[1], r[2] + rootRot[2]));
                h.add("translation", arr(tr[0] + rootPos[0] * sc[0], tr[1] + rootPos[1] * sc[1], tr[2] + rootPos[2] * sc[2]));
                disp.add(hand, h);
            }
        }
        model.add("display", disp);
        return model;
    }

    private static JsonObject displayFrom(JsonObject bbDisplay, double comp) {
        JsonObject d = defaultDisplay(comp);
        for (var en : bbDisplay.entrySet()) {
            if (!en.getValue().isJsonObject()) continue;
            JsonObject o = en.getValue().getAsJsonObject();
            double[] r = o.has("rotation") ? vec(o.get("rotation")) : new double[]{0, 0, 0};
            double[] tr = o.has("translation") ? vec(o.get("translation")) : new double[]{0, 0, 0};
            double[] sc = o.has("scale") ? vec(o.get("scale")) : new double[]{1, 1, 1};
            for (int i = 0; i < 3; i++) sc[i] = Math.min(4, sc[i] * comp);
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
