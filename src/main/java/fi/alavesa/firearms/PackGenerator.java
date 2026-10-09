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

    public record Result(int models, int frames, int placeholders, List<String> warnings, File zip, long bytes, List<String> biggest, long millis) { }
    private final boolean dryRun;
    private final Map<String, Long> bytesPerModel = new LinkedHashMap<>();
    private String currentName;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String NS = "firearms";

    private final FirearmsPlugin plugin;
    private final Registry registry;
    private final List<String> warnings = new ArrayList<>();
    private final Map<String, byte[]> files = new LinkedHashMap<>();       // zip path -> bytes
    private final Map<String, String> cmdToModel = new LinkedHashMap<>();  // custom_model_data -> model path
    private final Map<String, Map<String, int[]>> animIndex = new LinkedHashMap<>();
    private final Map<String, Map<String, List<String>>> clipSoundIndex = new LinkedHashMap<>();
    private int frames = 0, placeholders = 0, models = 0;
    private final Map<String, String> vestCmdToModel = new LinkedHashMap<>();   // vests live on the armour item
    private final Map<String, String> fpVariant = new LinkedHashMap<>();        // cmd string -> first-person model path (gun + arms)
    private final Map<String, Integer> fpTints = new LinkedHashMap<>();         // cmd string -> number of arm tint indices
    private final Map<String, String> iconOf = new LinkedHashMap<>();           // cmd string -> flat GUI icon model path
    private final Map<String, List<String>> soundEvents = new LinkedHashMap<>();// sound event -> files
    private GunType currentGun;
    private final List<String> report = new ArrayList<>();

    /** A resource-location-safe path for a model name. Minecraft rejects the WHOLE items file if any case
     *  points at a path with an upper-case letter, a space or other illegal character - every model then
     *  turns purple/black. The custom_model_data STRING may stay as written; only the path is sanitised. */
    static String path(String name) {
        String p = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
        return p.isEmpty() ? "model" : p;
    }

    public PackGenerator(FirearmsPlugin plugin, Registry registry) { this(plugin, registry, false); }

    /** dryRun = analyse only (/firearms check): nothing is written to disk. */
    public PackGenerator(FirearmsPlugin plugin, Registry registry, boolean dryRun) {
        this.plugin = plugin;
        this.registry = registry;
        this.dryRun = dryRun;
    }

    public Result generate() throws IOException {
        long t0 = System.currentTimeMillis();
        File dir = new File(plugin.getDataFolder(), "models");
        dir.mkdirs();
        Set<String> done = new LinkedHashSet<>();
        for (GunType g : registry.guns()) model(dir, g.model(), "gun", done);
        for (MagType m : registry.mags()) model(dir, m.model(), "mag", done);
        for (AmmoType a : registry.ammos()) model(dir, a.model(), "ammo", done);
        model(dir, plugin.getConfig().getString("craters.model", "crater"), "crater", done);
        Set<String> casingModels = new LinkedHashSet<>();
        for (GunType g : registry.guns()) { String c = registry.casingModel(g); if (c != null) casingModels.add(c); }
        for (String c : casingModels) model(dir, c, "casing", done);
        currentName = null;   // everything below is shared (items file, textures, sounds) - not one model's size
        files.put("assets/" + NS + "/textures/item/arm_pixel.png", whitePng());
        // Flat GUI icons (Roblox-style label cards) for every gun: models/<model>_icon.png if you drew one, else generated.
        if (plugin.getConfig().getBoolean("icons.enabled", true)) for (GunType g : registry.guns()) icon(dir, g);
        if (!soundEvents.isEmpty()) {
            JsonObject sj = new JsonObject();
            for (var e : soundEvents.entrySet()) {
                JsonObject ev = new JsonObject();
                JsonArray arr = new JsonArray();
                for (String f : e.getValue()) arr.add(NS + ":" + f);
                ev.add("sounds", arr);
                sj.add(e.getKey(), ev);
            }
            put("assets/" + NS + "/sounds.json", GSON.toJson(sj));
        }

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
        if (!dryRun) try { Files.writeString(new File(plugin.getDataFolder(), "pack-report.txt").toPath(), String.join("\n", report)); } catch (IOException ignored) { }

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
            Map<String, List<String>> sm = clipSoundIndex.get(m.getKey());
            if (sm != null && sm.containsKey(c.getKey())) idx.set(m.getKey() + "." + c.getKey() + ".sounds", sm.get(c.getKey()));
        }
        File zip = new File(plugin.getDataFolder(), "Firearms-pack.zip");
        long bytes = 0;
        for (byte[] b : files.values()) bytes += b.length;
        List<String> biggest = new ArrayList<>();
        bytesPerModel.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(6)
            .forEach(e -> biggest.add(e.getKey() + " " + (e.getValue() / 1024) + " KB"));
        if (!dryRun) {
            // Write to a temp file and move it into place, so a pack being served/copied is never half-written,
            // and write the animation index ONLY after the zip is complete: an index that is newer than the pack
            // the players have would make clips reference frames that are not in their pack (purple gun while firing).
            File tmp = new File(plugin.getDataFolder(), "Firearms-pack.zip.tmp");
            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(tmp))) {
                for (var e : files.entrySet()) {
                    out.putNextEntry(new ZipEntry(e.getKey()));
                    out.write(e.getValue());
                    out.closeEntry();
                }
            }
            Files.move(tmp.toPath(), zip.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            idx.save(new File(dir, "anim-index.yml"));
        }
        return new Result(models, frames, placeholders, warnings, zip, bytes, biggest, System.currentTimeMillis() - t0);
    }

    private void put(String path, String text) { putBytes(path, text.getBytes(StandardCharsets.UTF_8)); }
    private void putBytes(String path, byte[] data) {
        files.put(path, data);
        if (currentName != null) bytesPerModel.merge(currentName, (long) data.length, Long::sum);
    }

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
            String fp = fpVariant.get(e.getKey());
            String icon = iconOf.get(e.getKey());
            if (icon != null && !files.containsKey("assets/" + NS + "/models/item/" + icon + ".json")) icon = null;
            if (fp != null && !files.containsKey("assets/" + NS + "/models/item/" + fp + ".json")) fp = null;
            if (fp == null && icon != null) {
                JsonObject sel = new JsonObject();
                sel.addProperty("type", "minecraft:select");
                sel.addProperty("property", "minecraft:display_context");
                JsonArray sc = new JsonArray();
                JsonObject gc = new JsonObject();
                gc.addProperty("when", "gui");
                JsonObject im = new JsonObject();
                im.addProperty("type", "minecraft:model");
                im.addProperty("model", NS + ":item/" + icon);
                gc.add("model", im);
                sc.add(gc);
                sel.add("cases", sc);
                sel.add("fallback", m);
                c.add("model", sel);
                report.add(base + ": \"" + e.getKey() + "\" -> " + NS + ":item/" + e.getValue() + "  (gui icon: " + icon + ")");
                cases.add(c);
                continue;
            }
            if (fp != null) {
                // first person -> gun + skin-tinted arms (tint k = custom_model_data.colors[k]); elsewhere the plain gun
                JsonObject armsM = new JsonObject();
                armsM.addProperty("type", "minecraft:model");
                armsM.addProperty("model", NS + ":item/" + fp);
                JsonArray tints = new JsonArray();
                int n = fpTints.getOrDefault(e.getKey(), 0);
                for (int k = 0; k < n; k++) {
                    JsonObject t = new JsonObject();
                    t.addProperty("type", "minecraft:custom_model_data");
                    t.addProperty("index", k);
                    t.addProperty("default", 0xC58C5E);
                    tints.add(t);
                }
                armsM.add("tints", tints);
                JsonObject fpm = new JsonObject();
                fpm.addProperty("type", "minecraft:composite");
                JsonArray parts = new JsonArray();
                parts.add(m.deepCopy());
                parts.add(armsM);
                fpm.add("models", parts);
                JsonObject sel = new JsonObject();
                sel.addProperty("type", "minecraft:select");
                sel.addProperty("property", "minecraft:display_context");
                JsonArray sc = new JsonArray();
                JsonObject fpCase = new JsonObject();
                JsonArray when = new JsonArray();
                when.add("firstperson_righthand");
                when.add("firstperson_lefthand");
                fpCase.add("when", when);
                fpCase.add("model", fpm);
                sc.add(fpCase);
                if (icon != null) {
                    JsonObject gc = new JsonObject();
                    gc.addProperty("when", "gui");
                    JsonObject im = new JsonObject();
                    im.addProperty("type", "minecraft:model");
                    im.addProperty("model", NS + ":item/" + icon);
                    gc.add("model", im);
                    sc.add(gc);
                }
                sel.add("cases", sc);
                sel.add("fallback", m);
                c.add("model", sel);
                report.add(base + ": \"" + e.getKey() + "\" -> " + NS + ":item/" + e.getValue() + "  (first person: " + fp + ", " + n + " skin tints" + (icon != null ? ", gui icon: " + icon : "") + ")");
            } else {
                c.add("model", m);
                report.add(base + ": \"" + e.getKey() + "\" -> " + NS + ":item/" + e.getValue());
            }
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
        currentGun = null;
        currentName = name;
        if (kind.equals("gun")) for (GunType g : registry.guns()) if (g.model().equals(name)) { currentGun = g; break; }
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
            case "casing" -> {
                tex.addProperty("0", "minecraft:block/raw_gold_block");
                tex.addProperty("particle", "minecraft:block/raw_gold_block");
                elements.add(cube(new double[]{7.4, 7, 6}, new double[]{8.6, 8.2, 10}, "#0", new double[]{0, 0, 2, 5}, null));
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
        if (kind.equals("gun") && currentGun != null && registry.armsEnabled(currentGun)) armsVariant(name, path(name), model);
    }

    /** The skin-tinted arm quads as ONE shared model per gun (<path>_arms.json); every frame of the gun is shown
     *  in first person as a composite of [frame, arms], so the arms are not duplicated into hundreds of frames. */
    private void armsVariant(String cmd, String mpath, JsonObject model) {
        String armsPath = mpath + "_arms";
        if (currentGun != null) {
            String gunPath = path(currentGun.model());
            armsPath = gunPath + "_arms";
            if (!files.containsKey("assets/" + NS + "/models/item/" + armsPath + ".json")) {
                JsonObject arms = new JsonObject();
                JsonObject tex = new JsonObject();
                tex.addProperty("skin", NS + ":item/arm_pixel");
                tex.addProperty("particle", NS + ":item/arm_pixel");
                arms.add("textures", tex);
                JsonArray elements = new JsonArray();
                int tint = armElements(elements, 0, true, armCfg("right"));
                if (registry.leftArm(currentGun)) tint = armElements(elements, tint, false, armCfg("left"));
                arms.add("elements", elements);
                arms.add("display", model.has("display") ? model.getAsJsonObject("display").deepCopy() : defaultDisplay(1.0));
                put("assets/" + NS + "/models/item/" + armsPath + ".json", GSON.toJson(arms));
                armTintCount.put(armsPath, tint);
            }
        }
        // The whole-gun (root bone) motion lives in each frame's display transform; the arms must follow it, so each
        // frame gets a tiny wrapper model: parent = the shared arms geometry, display = that frame's display.
        if (model.has("display")) {
            JsonObject wrap = new JsonObject();
            wrap.addProperty("parent", NS + ":item/" + armsPath);
            wrap.add("display", model.getAsJsonObject("display").deepCopy());
            String wrapPath = mpath + "_arms";
            if (!wrapPath.equals(armsPath)) put("assets/" + NS + "/models/item/" + wrapPath + ".json", GSON.toJson(wrap));
            fpVariant.put(cmd, wrapPath);
        } else {
            fpVariant.put(cmd, armsPath);
        }
        fpTints.put(cmd, armTintCount.getOrDefault(armsPath, 0));
    }
    private final Map<String, Integer> armTintCount = new LinkedHashMap<>();

    private org.bukkit.configuration.ConfigurationSection armCfg(String side) {
        if (currentGun != null && currentGun.arms() != null && currentGun.arms().getConfigurationSection(side) != null) return currentGun.arms().getConfigurationSection(side);
        return plugin.getConfig().getConfigurationSection("arms." + side);
    }

    /** One thin quad per skin pixel on the visible faces of a 4x12x4 arm box: hand end at `pos`, extending 12 px
     *  up its own axis, rotated about `pos` (snapped to one Java axis / 22.5 steps). Returns the next tint index. */
    private int armElements(JsonArray elements, int tint, boolean right, org.bukkit.configuration.ConfigurationSection cfg) {
        double[] pos = cfg == null ? new double[]{right ? 10.5 : 5.5, 1, 11.5} : list3(cfg, "pos", new double[]{right ? 10.5 : 5.5, 1, 11.5});
        double[] rot = cfg == null ? new double[]{-45, 0, 0} : list3(cfg, "rotation", new double[]{-45, 0, 0});
        int axis = 0;
        for (int i = 1; i < 3; i++) if (Math.abs(rot[i]) > Math.abs(rot[axis])) axis = i;
        double angle = Math.max(-45, Math.min(45, Math.round(rot[axis] / 22.5) * 22.5));
        JsonObject rotation = null;
        if (angle != 0) {
            rotation = new JsonObject();
            rotation.add("origin", arr(pos));
            rotation.addProperty("axis", axis == 0 ? "x" : axis == 1 ? "y" : "z");
            rotation.addProperty("angle", angle);
        }
        double x0 = pos[0] - 2, y0 = pos[1], z0 = pos[2] - 2;   // box x0..x0+4, y0..y0+12 (hand at y0), z0..z0+4
        for (ArmSkin.Pixel px : ArmSkin.pixels(right)) {
            int c = px.col(), r = px.row();
            double[] from, to; String dir;
            switch (px.face()) {
                case "front" -> { from = new double[]{x0 + c, y0 + 11 - r, z0 - 0.01}; to = new double[]{x0 + c + 1, y0 + 12 - r, z0 + 0.0}; dir = "north"; }
                case "back"  -> { from = new double[]{x0 + 3 - c, y0 + 11 - r, z0 + 4}; to = new double[]{x0 + 4 - c, y0 + 12 - r, z0 + 4.01}; dir = "south"; }
                case "outer" -> { if (right) { from = new double[]{x0 + 4, y0 + 11 - r, z0 + c}; to = new double[]{x0 + 4.01, y0 + 12 - r, z0 + c + 1}; dir = "east"; }
                                  else       { from = new double[]{x0 - 0.01, y0 + 11 - r, z0 + 3 - c}; to = new double[]{x0, y0 + 12 - r, z0 + 4 - c}; dir = "west"; } }
                case "inner" -> { if (right) { from = new double[]{x0 - 0.01, y0 + 11 - r, z0 + 3 - c}; to = new double[]{x0, y0 + 12 - r, z0 + 4 - c}; dir = "west"; }
                                  else       { from = new double[]{x0 + 4, y0 + 11 - r, z0 + c}; to = new double[]{x0 + 4.01, y0 + 12 - r, z0 + c + 1}; dir = "east"; } }
                default      -> { from = new double[]{x0 + c, y0 + 12, z0 + r}; to = new double[]{x0 + c + 1, y0 + 12.01, z0 + r + 1}; dir = "up"; }   // top (shoulder)
            }
            JsonObject e = new JsonObject();
            e.add("from", arr(from));
            e.add("to", arr(to));
            if (rotation != null) e.add("rotation", rotation);
            e.addProperty("shade", false);
            JsonObject faces = new JsonObject();
            JsonObject f = new JsonObject();
            f.add("uv", arr(0, 0, 16, 16));
            f.addProperty("texture", "#skin");
            f.addProperty("tintindex", tint);
            faces.add(dir, f);
            e.add("faces", faces);
            elements.add(e);
            tint++;
        }
        return tint;
    }

    private void icon(File dir, GunType g) throws IOException {
        String mp = path(g.model());
        File custom = new File(dir, g.model() + "_icon.png");
        byte[] png = custom.exists() ? Files.readAllBytes(custom.toPath()) : labelPng(g);
        files.put("assets/" + NS + "/textures/item/icons/" + mp + ".png", png);
        JsonObject model = new JsonObject();
        model.addProperty("parent", "minecraft:item/generated");
        JsonObject tex = new JsonObject();
        tex.addProperty("layer0", NS + ":item/icons/" + mp);
        model.add("textures", tex);
        put("assets/" + NS + "/models/item/" + mp + "_icon.json", GSON.toJson(model));
        for (String cmd : new ArrayList<>(cmdToModel.keySet()))
            if (cmd.equals(g.model()) || cmd.startsWith(g.model() + "_")) iconOf.put(cmd, mp + "_icon");
    }

    /** A 64x64 label card: dark rounded background, the gun's name in big letters (fitted), a thin accent bar. */
    private byte[] labelPng(GunType g) throws IOException {
        String text = g.name().replaceAll("&[0-9a-fk-or]", "").trim().toUpperCase(Locale.ROOT);
        if (text.isEmpty()) text = g.id().toUpperCase(Locale.ROOT);
        int size = 64;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D gfx = img.createGraphics();
        gfx.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        gfx.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        gfx.setColor(new java.awt.Color(28, 30, 36, 235));
        gfx.fillRoundRect(2, 2, size - 4, size - 4, 12, 12);
        gfx.setColor(new java.awt.Color(90, 96, 110, 255));
        gfx.setStroke(new java.awt.BasicStroke(2f));
        gfx.drawRoundRect(2, 2, size - 4, size - 4, 12, 12);
        int accent = 0xE8A33D;
        try { accent = Integer.parseInt(plugin.getConfig().getString("icons.accent", "E8A33D").replace("#", ""), 16); } catch (NumberFormatException ignored) { }
        gfx.setColor(new java.awt.Color(accent));
        gfx.fillRoundRect(10, size - 14, size - 20, 4, 3, 3);
        // split long names onto two lines, fit the font
        String[] lines = text.length() > 9 && text.contains(" ") ? text.split(" ", 2) : new String[]{text};
        gfx.setColor(java.awt.Color.WHITE);
        float fs = lines.length == 1 ? 22f : 16f;
        java.awt.Font font = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, (int) fs);
        for (int i = 0; i < 12; i++) {
            font = font.deriveFont(fs);
            java.awt.FontMetrics fm = gfx.getFontMetrics(font);
            int w = 0; for (String l : lines) w = Math.max(w, fm.stringWidth(l));
            if (w <= size - 12) break;
            fs -= 1.5f;
        }
        gfx.setFont(font);
        java.awt.FontMetrics fm = gfx.getFontMetrics();
        int lineH = fm.getAscent() + 1;
        int totalH = lineH * lines.length;
        int y = (size - 10) / 2 - totalH / 2 + fm.getAscent() - 1;
        for (String l : lines) { gfx.drawString(l, (size - fm.stringWidth(l)) / 2, y); y += lineH; }
        gfx.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static byte[] whitePng() throws IOException {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) img.setRGB(x, y, 0xFFFFFFFF);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
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

    private byte[] flashPng() throws IOException {
        double brightness = Math.max(0.1, Math.min(1.0, plugin.getConfig().getDouble("flash.brightness", 0.65)));
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            double d = Math.hypot(x - 7.5, y - 7.5) / 7.5;
            int a = d > 1 ? 0 : (int) (255 * brightness * Math.pow(1 - d, 1.8));
            int g = 170 + (int) (60 * (1 - d)), b = (int) (70 * (1 - d));
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
        String uuid; double[] from, to, origin = {0, 0, 0}, rotation = {0, 0, 0}; double inflate = 0; JsonObject faces; Bone bone;
    }

    private static final class Clip {
        String name; double length; Map<String, List<JsonObject>> rot = new HashMap<>(), pos = new HashMap<>(); // bone uuid -> keyframes
    }

    private void convert(File bb, String name) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(bb.toPath())).getAsJsonObject();
        final String mpath = path(name);
        int meshes = 0;
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
                putBytes("assets/" + NS + "/textures/item/" + mpath + "/" + tname + ".png", Base64.getDecoder().decode(src.substring(src.indexOf("base64,") + 7)));
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
            if (e.has("type") && !"cube".equals(str(e.get("type"), "cube"))) { meshes++; continue; }
            if (e.has("visibility") && !e.get("visibility").getAsBoolean()) continue;
            Cube c = new Cube();
            c.uuid = str(e.get("uuid"), "e" + cubes.size());
            c.from = vec(e.get("from")); c.to = vec(e.get("to"));
            if (e.has("origin")) c.origin = vec(e.get("origin"));
            if (e.has("inflate")) c.inflate = num(e.get("inflate"), 0);
            if (e.has("rotation")) c.rotation = vec(e.get("rotation"));
            c.faces = e.has("faces") ? e.getAsJsonObject("faces") : new JsonObject();
            cubes.put(c.uuid, c);
        }
        if (meshes > 0) warnings.add(name + ": " + meshes + " MESH element(s) skipped - Java item models only support cubes; convert meshes to cubes in Blockbench");
        if (cubes.isEmpty()) throw new IOException("no cube elements");
        // --- bones
        List<Bone> top = new ArrayList<>();
        Map<String, Bone> bones = new HashMap<>();
        if (root.has("outliner")) for (JsonElement oe : root.getAsJsonArray("outliner")) parseOutliner(oe, null, top, bones, cubes);
        // The "display bone": per animation, the top-level group that is ANIMATED and holds the most cubes. Its motion
        // becomes the first-person display transform (exact). Guns whose animated root was not the biggest group
        // used to get their whole-gun motion baked into cubes (torn apart) - this picks the right bone per clip.
        final List<Bone> topBones = top;

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
        JsonObject rest = bake(name, cubes, bones, null, textures, texIndex, texRes, display, null, 0, S, null);
        put("assets/" + NS + "/models/item/" + mpath + ".json", GSON.toJson(rest));
        cmdToModel.put(name, mpath);
        boolean arms = currentGun != null && registry.armsEnabled(currentGun);
        if (arms) armsVariant(name, mpath, rest);

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
            List<String> clipSounds = new ArrayList<>();
            if (a.has("animators")) for (var en : a.getAsJsonObject("animators").entrySet()) {
                JsonObject an = en.getValue().getAsJsonObject();
                if (!an.has("keyframes")) continue;
                for (JsonElement ke : an.getAsJsonArray("keyframes")) {
                    JsonObject k = ke.getAsJsonObject();
                    String ch = str(k.get("channel"), "");
                    if (ch.equals("rotation")) clip.rot.computeIfAbsent(en.getKey(), x -> new ArrayList<>()).add(k);
                    else if (ch.equals("position")) clip.pos.computeIfAbsent(en.getKey(), x -> new ArrayList<>()).add(k);
                    else if (ch.equals("sound")) {
                        // Blockbench "Effects" animator: {effect: "name", file: "C:/.../name.ogg"}. The audio is NOT
                        // inside the .bbmodel, so it is looked up in models/sounds/<name>.ogg (or the keyframe's path).
                        JsonArray dp = k.has("data_points") ? k.getAsJsonArray("data_points") : new JsonArray();
                        for (JsonElement de : dp) {
                            JsonObject d = de.getAsJsonObject();
                            String file = str(d.get("file"), "");
                            String ev = str(d.get("effect"), "");
                            if (ev.isEmpty() && !file.isEmpty()) ev = new File(file).getName().replaceAll("\\.ogg$", "");
                            if (ev.isEmpty()) continue;
                            String sname = ev.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
                            File ogg = new File(new File(bb.getParentFile(), "sounds"), sname + ".ogg");
                            if (!ogg.exists()) ogg = new File(bb.getParentFile(), sname + ".ogg");
                            if (!ogg.exists() && !file.isEmpty() && new File(file).exists()) ogg = new File(file);
                            if (!ogg.exists()) { warnings.add(name + ": sound '" + ev + "' in animation '" + clip.name + "' - put " + sname + ".ogg into models/sounds/"); continue; }
                            files.put("assets/" + NS + "/sounds/" + sname + ".ogg", Files.readAllBytes(ogg.toPath()));
                            soundEvents.computeIfAbsent(sname, x -> new ArrayList<>());
                            if (!soundEvents.get(sname).contains(sname)) soundEvents.get(sname).add(sname);
                            long tick = Math.round(num(k.get("time"), 0) * 20);
                            clipSounds.add(tick + ":" + NS + ":" + sname);
                        }
                    }
                }
            }
            int maxFrames = Math.max(2, plugin.getConfig().getInt("anim.max-frames", 40));
            int clipTicks = frameTicks;
            int n = (int) Math.max(1, Math.ceil(clip.length * 20.0 / clipTicks));
            if (n > maxFrames) { clipTicks = (int) Math.ceil(clip.length * 20.0 / maxFrames); n = (int) Math.max(1, Math.ceil(clip.length * 20.0 / clipTicks)); }
            for (int i = 1; i <= n; i++) {
                double t = n == 1 ? clip.length : (i - 1) * (clip.length / (n - 1));
                boolean flashFrame = key.equals("fire") && i <= Math.max(0, plugin.getConfig().getInt("flash.frames", 1));
                JsonObject frame = bake(name, cubes, bones, pickRoot(topBones, clip), textures, texIndex, texRes, display, clip, t, S, flashFrame ? flashAt : null);
                String fname = name + "_" + key + "_" + i;
                put("assets/" + NS + "/models/item/" + mpath + "_" + key + "_" + i + ".json", GSON.toJson(frame));
                cmdToModel.put(fname, mpath + "_" + key + "_" + i);
                if (arms) armsVariant(fname, mpath + "_" + key + "_" + i, frame);
                frames++;
            }
            clips.put(key, new int[]{n, clipTicks});
            if (!clipSounds.isEmpty()) clipSoundIndex.computeIfAbsent(name, x -> new LinkedHashMap<>()).put(key, clipSounds);
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
        int axis = -1; double angle = 0;   // residual rotation (one Java axis, 22.5 steps, |a| <= 45) about pivot
        double[] pivot;
        double[][] coarse;                 // world transform with translations + the EXACT 90-degree parts only
        int[][] perm;                      // accumulated signed permutation (orientation) for face remapping
    }

    private static int countCubes(Bone b) {
        int n = b.elements.size();
        for (Bone c : b.children) n += countCubes(c);
        return n;
    }

    /** The bone whose animated motion goes into the display transform for this clip. */
    private static Bone pickRoot(List<Bone> top, Clip clip) {
        Bone best = null; int bestN = 0;
        for (Bone b : top) {
            boolean animated = clip.rot.containsKey(b.uuid) || clip.pos.containsKey(b.uuid);
            if (!animated) continue;
            int n = countCubes(b);
            if (n > bestN) { bestN = n; best = b; }
        }
        if (best == null) for (Bone b : top) { int n = countCubes(b); if (n > bestN) { bestN = n; best = b; } }
        return best;
    }

    /** Any rotation = an exact multiple-of-90 part (a box stays a box: handled by permuting axes) + a small residual
     *  that a Java cube can carry (one axis, 22.5 steps). Blockbench groups/cubes rotated 90, -30, 180... were the
     *  "pieces floating above the gun": they got clamped to 45 before. */
    private Baked bakeBone(Bone b, Map<Bone, Baked> cache, Map<Bone, double[]> rot, Map<Bone, double[]> pos, String name, Set<String> warned) {
        Baked done = cache.get(b);
        if (done != null) return done;
        Baked parent = b.parent == null ? null : bakeBone(b.parent, cache, rot, pos, name, warned);
        double[] r = rot.getOrDefault(b, new double[]{0, 0, 0}), p = pos.getOrDefault(b, new double[]{0, 0, 0});
        double[][] R = rot3(r);
        int[][] P = nearestPerm(R);
        double[] eul = euler3(mul3(transpose3(P), R));
        int axis = 0;
        for (int i = 1; i < 3; i++) if (Math.abs(eul[i]) > Math.abs(eul[axis])) axis = i;
        double raw = eul[axis];
        double snapped = Math.max(-45, Math.min(45, Math.round(raw / 22.5) * 22.5));
        boolean multi = false;
        for (int i = 0; i < 3; i++) if (i != axis && Math.abs(eul[i]) > 3) multi = true;
        if (multi && warned.add(b.name + ":multi")) warnings.add(name + ": group '" + b.name + "' has a rotation on several axes at once - Java cubes keep one, the rest is approximated");
        if (Math.abs(raw - snapped) > 6 && warned.add(b.name + ":snap")) warnings.add(name + ": group '" + b.name + "' rotation " + Math.round(r[axis]) + " deg rounded to the nearest 22.5 step (Java limit)");
        Baked out = new Baked();
        double[][] parentCoarse = parent == null ? identity() : parent.coarse;
        int[][] parentPerm = parent == null ? identityPerm() : parent.perm;
        double[] pivotLocal = {b.origin[0] + p[0], b.origin[1] + p[1], b.origin[2] + p[2]};
        out.pivot = apply(parentCoarse, pivotLocal);
        out.coarse = mul(parentCoarse, mul(mul(translate(pivotLocal[0], pivotLocal[1], pivotLocal[2]), perm4(P)), translate(-b.origin[0], -b.origin[1], -b.origin[2])));
        out.perm = mul3i(parentPerm, P);
        if (snapped != 0) { out.axis = axis; out.angle = snapped; }
        cache.put(b, out);
        return out;
    }

    // --- face conventions (direction, texture up, texture right) for remapping rotated cubes
    private static final String[] FACE = {"north", "south", "east", "west", "up", "down"};
    private static final int[][] DIR   = {{0,0,-1},{0,0,1},{1,0,0},{-1,0,0},{0,1,0},{0,-1,0}};
    private static final int[][] FUP   = {{0,1,0},{0,1,0},{0,1,0},{0,1,0},{0,0,-1},{0,0,1}};
    private static final int[][] FRIGHT= {{-1,0,0},{1,0,0},{0,0,-1},{0,0,1},{1,0,0},{1,0,0}};

    private static int faceIndex(int[] dir) {
        for (int i = 0; i < 6; i++) if (DIR[i][0] == dir[0] && DIR[i][1] == dir[1] && DIR[i][2] == dir[2]) return i;
        return 0;
    }
    private static int[] applyPerm(int[][] P, int[] v) {
        return new int[]{ P[0][0]*v[0]+P[0][1]*v[1]+P[0][2]*v[2], P[1][0]*v[0]+P[1][1]*v[1]+P[1][2]*v[2], P[2][0]*v[0]+P[2][1]*v[1]+P[2][2]*v[2] };
    }
    private static int dot(int[] a, int[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }

    /** Remap a cube's faces after its orientation changed by the signed permutation P: new face name, and the
     *  texture rotation / mirror needed so the texture still reads the same way. */
    private static JsonObject remapFaces(JsonObject faces, int[][] P) {
        boolean identity = true;
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) if (P[i][j] != (i == j ? 1 : 0)) identity = false;
        if (identity) return faces;
        JsonObject out = new JsonObject();
        for (int i = 0; i < 6; i++) {
            if (!faces.has(FACE[i])) continue;
            JsonObject f = faces.getAsJsonObject(FACE[i]).deepCopy();
            int ni = faceIndex(applyPerm(P, DIR[i]));
            int[] up = applyPerm(P, FUP[i]), right = applyPerm(P, FRIGHT[i]);
            int k;   // texture rotation so the old "up" lands where it should
            if (dot(up, FUP[ni]) > 0) k = 0; else if (dot(up, FRIGHT[ni]) > 0) k = 90; else if (dot(up, FUP[ni]) < 0) k = 180; else k = 270;
            int[] expectRight = switch (k) { case 0 -> FRIGHT[ni]; case 90 -> neg(FUP[ni]); case 180 -> neg(FRIGHT[ni]); default -> FUP[ni]; };
            boolean mirror = dot(right, expectRight) < 0;
            int rotation = (int) ((f.has("rotation") ? num(f.get("rotation"), 0) : 0) + k) % 360;
            if (rotation != 0) f.addProperty("rotation", rotation); else f.remove("rotation");
            if (mirror && f.has("uv")) {
                JsonArray uv = f.getAsJsonArray("uv");
                double u1 = num(uv.get(0), 0), u2 = num(uv.get(2), 0);
                JsonArray n = new JsonArray(); n.add(u2); n.add(num(uv.get(1), 0)); n.add(u1); n.add(num(uv.get(3), 0));
                f.add("uv", n);
            }
            out.add(FACE[ni], f);
        }
        return out;
    }
    private static int[] neg(int[] v) { return new int[]{-v[0], -v[1], -v[2]}; }

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
            // The ONE residual rotation a Java cube gets: the nearest rotated bone up the chain (same-axis angles
            // further up are folded in), else the cube's own residual. Exact 90-degree parts are already in `place`.
            int axis = -1; double angle = 0; double[] pivot = null;
            double[][] place = bb == null ? identity() : bb.coarse;
            int[][] perm = bb == null ? identityPerm() : bb.perm;
            for (Bone w = c.bone; w != null; w = w.parent) {
                Baked wb = baked.get(w);
                if (wb == null || wb.axis < 0) continue;
                if (axis < 0) { axis = wb.axis; angle = wb.angle; pivot = wb.pivot; }
                else if (wb.axis == axis) angle = Math.max(-45, Math.min(45, angle + wb.angle));
                else if (warned.add(c.uuid + ":nest")) warnings.add(name + ": nested groups rotate on different axes around '" + w.name + "' - approximated");
            }
            // the cube's own rotation: exact 90-degree part about its origin + residual
            double[][] Re = rot3(c.rotation);
            int[][] Pe = nearestPerm(Re);
            double[] eulE = euler3(mul3(transpose3(Pe), Re));
            int ca = 0;
            for (int i = 1; i < 3; i++) if (Math.abs(eulE[i]) > Math.abs(eulE[ca])) ca = i;
            double cs = Math.max(-45, Math.min(45, Math.round(eulE[ca] / 22.5) * 22.5));
            if (cs != 0) {
                if (axis < 0) { axis = ca; angle = cs; pivot = apply(place, c.origin); }
                else if (ca == axis) angle = Math.max(-45, Math.min(45, angle + cs));
            }
            if (Math.abs(eulE[ca] - cs) > 6 && clip == null && warned.add(c.uuid + ":snap")) warnings.add(name + ": a cube rotated " + Math.round(c.rotation[ca]) + " deg was rounded to the nearest 22.5 step");
            // box: inflate, rotate the corners by the cube's exact 90-degree part about its origin, then place
            double[] lo = {1e9, 1e9, 1e9}, hi = {-1e9, -1e9, -1e9};
            for (int corner = 0; corner < 8; corner++) {
                double[] v = { ((corner & 1) == 0 ? c.from[0] - c.inflate : c.to[0] + c.inflate) - c.origin[0],
                               ((corner & 2) == 0 ? c.from[1] - c.inflate : c.to[1] + c.inflate) - c.origin[1],
                               ((corner & 4) == 0 ? c.from[2] - c.inflate : c.to[2] + c.inflate) - c.origin[2] };
                double[] w = { Pe[0][0]*v[0]+Pe[0][1]*v[1]+Pe[0][2]*v[2] + c.origin[0], Pe[1][0]*v[0]+Pe[1][1]*v[1]+Pe[1][2]*v[2] + c.origin[1], Pe[2][0]*v[0]+Pe[2][1]*v[1]+Pe[2][2]*v[2] + c.origin[2] };
                double[] q = apply(place, w);
                for (int i = 0; i < 3; i++) { lo[i] = Math.min(lo[i], q[i]); hi[i] = Math.max(hi[i], q[i]); }
            }
            double[] from = lo, to = hi;
            JsonObject cfaces = remapFaces(c.faces, mul3i(perm, Pe));
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
                if (!cfaces.has(f)) continue;
                JsonObject bf = cfaces.getAsJsonObject(f);
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

    // ------------------------------------------------------------------ 3x3 rotation helpers
    private static double[][] rot3(double[] deg) {
        double[][] m = rotZYX(deg);
        return new double[][]{{m[0][0], m[0][1], m[0][2]}, {m[1][0], m[1][1], m[1][2]}, {m[2][0], m[2][1], m[2][2]}};
    }
    private static double[][] mul3(double[][] a, double[][] b) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) { double x = 0; for (int k = 0; k < 3; k++) x += a[i][k] * b[k][j]; r[i][j] = x; }
        return r;
    }
    private static double[][] transpose3(int[][] p) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) r[i][j] = p[j][i];
        return r;
    }
    private static int[][] identityPerm() { return new int[][]{{1,0,0},{0,1,0},{0,0,1}}; }
    private static int[][] mul3i(int[][] a, int[][] b) {
        int[][] r = new int[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) { int x = 0; for (int k = 0; k < 3; k++) x += a[i][k] * b[k][j]; r[i][j] = x; }
        return r;
    }
    private static double[][] perm4(int[][] p) {
        double[][] m = identity();
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) m[i][j] = p[i][j];
        return m;
    }
    /** The signed axis permutation (a rotation by multiples of 90 degrees) nearest to R; identity if R is not close
     *  to one on some axis (then everything goes to the residual). */
    private static int[][] nearestPerm(double[][] R) {
        int[][] P = new int[3][3];
        boolean[] usedCol = new boolean[3];
        for (int i = 0; i < 3; i++) {
            int bj = 0;
            for (int j = 1; j < 3; j++) if (Math.abs(R[i][j]) > Math.abs(R[i][bj])) bj = j;
            if (usedCol[bj] || Math.abs(R[i][bj]) < 0.5) return identityPerm();
            usedCol[bj] = true;
            P[i][bj] = R[i][bj] >= 0 ? 1 : -1;
        }
        // must be a proper rotation (det +1); a reflection means the pick was wrong -> fall back
        int det = P[0][0]*(P[1][1]*P[2][2]-P[1][2]*P[2][1]) - P[0][1]*(P[1][0]*P[2][2]-P[1][2]*P[2][0]) + P[0][2]*(P[1][0]*P[2][1]-P[1][1]*P[2][0]);
        return det == 1 ? P : identityPerm();
    }
    private static double[] euler3(double[][] m) {
        double y = Math.asin(Math.max(-1, Math.min(1, -m[2][0])));
        double x = Math.atan2(m[2][1], m[2][2]);
        double z = Math.atan2(m[1][0], m[0][0]);
        return new double[]{ Math.toDegrees(x), Math.toDegrees(y), Math.toDegrees(z) };
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
