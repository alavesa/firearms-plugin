package fi.alavesa.firearms;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Loads guns.yml / mags.yml, builds the items, and reads/writes the per-item state (rounds etc.). */
public final class Registry {

    private final FirearmsPlugin plugin;
    private final Map<String, GunType> guns = new LinkedHashMap<>();
    private final Map<String, MagType> mags = new LinkedHashMap<>();
    private final Map<String, AmmoType> ammo = new LinkedHashMap<>();
    private final Map<String, ArmorType> vests = new LinkedHashMap<>();
    private final Map<String, GrenadeType> grenades = new LinkedHashMap<>();
    /** model -> skin pixel order (u,v pairs) for the classic / slim hand variants (from anim-index.yml). */
    private final Map<String, List<int[]>> skinClassic = new LinkedHashMap<>(), skinSlim = new LinkedHashMap<>();
    /** model -> clip -> [frames, frameTicks], written by the pack generator (models/anim-index.yml). */
    private final Map<String, Map<String, int[]>> anims = new LinkedHashMap<>();
    /** model -> clip -> ["tick:sound", ...] from the .bbmodel sound keyframes. */
    private final Map<String, Map<String, List<String>>> animSounds = new LinkedHashMap<>();

    final NamespacedKey gunKey, magKey, ammoKey, roundsKey, uidKey, craterKey, vestKey, grenadeKey;

    /** True once a gun was successfully given the adventure-mode can_break component. */
    static boolean canBreakOk = false;
    static boolean swingOk = false;
    private static final Set<String> componentWarned = ConcurrentHashMap.newKeySet();

    public Registry(FirearmsPlugin plugin) {
        this.plugin = plugin;
        gunKey = new NamespacedKey(plugin, "gun");
        magKey = new NamespacedKey(plugin, "mag");
        ammoKey = new NamespacedKey(plugin, "ammo");
        roundsKey = new NamespacedKey(plugin, "rounds");
        uidKey = new NamespacedKey(plugin, "uid");
        craterKey = new NamespacedKey(plugin, "crater");
        vestKey = new NamespacedKey(plugin, "vest");
        grenadeKey = new NamespacedKey(plugin, "grenade");
    }

    // ------------------------------------------------------------------ loading

    public void load() {
        guns.clear(); mags.clear(); ammo.clear(); anims.clear(); vests.clear(); animSounds.clear(); grenades.clear(); skinClassic.clear(); skinSlim.clear();
        File grf = new File(plugin.getDataFolder(), "grenades.yml");
        if (!grf.exists()) plugin.saveResource("grenades.yml", false);
        ConfigurationSection grs = YamlConfiguration.loadConfiguration(grf).getConfigurationSection("grenades");
        if (grs != null) for (String id : grs.getKeys(false)) {
            ConfigurationSection s = grs.getConfigurationSection(id);
            if (s == null) continue;
            grenades.put(id.toLowerCase(), new GrenadeType(id.toLowerCase(), s.getString("name", id), s.getString("model", "grenade_" + id.toLowerCase()),
                s.getString("kind", "frag").toLowerCase(), s.getDouble("fuse", 3), s.getBoolean("cook", false), s.getDouble("radius", 4),
                s.getDouble("damage", 12), s.getDouble("duration", 10), s.getDouble("speed", 1.2)));
        }
        ConfigurationSection vs = plugin.getConfig().getConfigurationSection("armor");
        if (vs != null) for (String id : vs.getKeys(false)) {
            ConfigurationSection s = vs.getConfigurationSection(id);
            if (s == null) continue;
            int color = 0x8A8A8A;
            try { color = Integer.parseInt(s.getString("color", "8A8A8A").replace("#", ""), 16); } catch (NumberFormatException ignored) { }
            vests.put(id.toLowerCase(), new ArmorType(id.toLowerCase(), s.getString("name", id), s.getString("model", id.toLowerCase()),
                s.getDouble("hearts", 1), s.getDouble("absorb-hearts", 40), Math.max(0, Math.min(1, s.getDouble("absorb", 0.5))), color));
        }
        File gf = new File(plugin.getDataFolder(), "guns.yml");
        File mf = new File(plugin.getDataFolder(), "mags.yml");
        if (!gf.exists()) plugin.saveResource("guns.yml", false);
        if (!mf.exists()) plugin.saveResource("mags.yml", false);
        YamlConfiguration g = YamlConfiguration.loadConfiguration(gf);
        YamlConfiguration m = YamlConfiguration.loadConfiguration(mf);

        ConfigurationSection as = m.getConfigurationSection("ammo");
        if (as != null) for (String id : as.getKeys(false)) {
            ConfigurationSection s = as.getConfigurationSection(id);
            if (s == null) continue;
            ammo.put(id.toLowerCase(), new AmmoType(id.toLowerCase(), s.getString("name", id),
                s.getString("model", id.toLowerCase()), Math.max(1, Math.min(99, s.getInt("stack", 64)))));
        }
        ConfigurationSection ms = m.getConfigurationSection("mags");
        if (ms != null) for (String id : ms.getKeys(false)) {
            ConfigurationSection s = ms.getConfigurationSection(id);
            if (s == null) continue;
            mags.put(id.toLowerCase(), new MagType(id.toLowerCase(), s.getString("name", id),
                s.getString("ammo", "").toLowerCase(), Math.max(1, s.getInt("capacity", 30)),
                s.getString("model", id.toLowerCase())));
        }
        ConfigurationSection gs = g.getConfigurationSection("guns");
        if (gs != null) for (String id : gs.getKeys(false)) {
            ConfigurationSection s = gs.getConfigurationSection(id);
            if (s == null) continue;
            String key = id.toLowerCase();
            String mag = s.getString("mag", "none");
            guns.put(key, new GunType(key,
                s.getString("name", id),
                s.getString("model", key),
                s.getDouble("weight", 1.0),
                s.getString("fire-mode", "semi").trim().toLowerCase().startsWith("auto"),
                s.getString("fire-mode", "semi").toLowerCase().contains("semi") && s.getString("fire-mode", "semi").toLowerCase().contains("auto"),
                s.getDouble("fire-rate", 5.0),
                s.getDouble("damage", 4.0),
                Math.max(1, s.getInt("magazine", 10)),
                mag == null ? "" : mag.toLowerCase(),
                s.getString("ammo", "").toLowerCase(),
                s.getBoolean("pump-action", false),
                s.getDouble("reload-seconds", 1.5),
                s.getDouble("hitscan-range", 10.0),
                s.getDouble("range", 60.0),
                s.getDouble("speed", 4.0),
                s.getDouble("accuracy", 1.5),
                s.getDouble("aim-accuracy", 0.5),
                s.getDouble("recoil", 2.0),
                s.getDouble("h-recoil", 0.8),
                s.getDouble("falloff-min", 0.4),
                Math.max(1, s.getInt("pellets", 1)),
                s.getString("sound", "minecraft:block.anvil.land"),
                (float) s.getDouble("pitch", 1.6),
                triple(s.getString("muzzle", "0.35,-0.22,0.7"), new double[]{0.35, -0.22, 0.7}),
                s.contains("flash") ? triple(s.getString("flash", ""), null) : null,
                s.getConfigurationSection("display"),
                s.getString("casing", "casing").toLowerCase(),
                triple(s.getString("eject", "0.25,-0.15,0.4"), new double[]{0.25, -0.15, 0.4}),
                s.getConfigurationSection("arms"),
                s.getDouble("drop-start", s.getDouble("hitscan-range", 10.0) * 3),
                s.getDouble("drop", plugin.getConfig().getDouble("ballistics.projectile-gravity", 0.03)),
                s.getConfigurationSection("anim-names"),
                s.getConfigurationSection("anim"),
                s.getDouble("equip-seconds", 0)));
        }
        // Animation frame index written by the pack generator.
        File ai = new File(new File(plugin.getDataFolder(), "models"), "anim-index.yml");
        if (ai.exists()) {
            YamlConfiguration a = YamlConfiguration.loadConfiguration(ai);
            for (String model : a.getKeys(false)) {
                ConfigurationSection s = a.getConfigurationSection(model);
                if (s == null) continue;
                Map<String, int[]> clips = new LinkedHashMap<>();
                Map<String, List<String>> snd = new LinkedHashMap<>();
                if (s.contains("skin-classic")) { skinClassic.put(model, pixels(s.getString("skin-classic", ""))); skinSlim.put(model, pixels(s.getString("skin-slim", ""))); }
                for (String clip : s.getKeys(false)) {
                    if (clip.startsWith("skin-")) continue;
                    clips.put(clip, new int[]{ s.getInt(clip + ".frames", 0), Math.max(1, s.getInt(clip + ".frame-ticks", 1)) });
                    if (s.contains(clip + ".sounds")) snd.put(clip, s.getStringList(clip + ".sounds"));
                }
                anims.put(model, clips);
                animSounds.put(model, snd);
            }
        }
    }

    /** "a,b,c" -> double[3], or def when it does not parse. */
    static double[] triple(String v, double[] def) {
        if (v == null) return def;
        String[] parts = v.replace("[", "").replace("]", "").split(",");
        if (parts.length != 3) return def;
        try { return new double[]{ Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()), Double.parseDouble(parts[2].trim()) }; }
        catch (NumberFormatException e) { return def; }
    }

    public GunType gun(String id) { return id == null ? null : guns.get(id.toLowerCase()); }
    public MagType mag(String id) { return id == null ? null : mags.get(id.toLowerCase()); }
    public AmmoType ammo(String id) { return id == null ? null : ammo.get(id.toLowerCase()); }
    public List<String> gunIds() { return new ArrayList<>(guns.keySet()); }
    public List<String> magIds() { return new ArrayList<>(mags.keySet()); }
    public List<String> ammoIds() { return new ArrayList<>(ammo.keySet()); }
    public java.util.Collection<GunType> guns() { return Collections.unmodifiableCollection(guns.values()); }
    public java.util.Collection<MagType> mags() { return Collections.unmodifiableCollection(mags.values()); }
    public java.util.Collection<AmmoType> ammos() { return Collections.unmodifiableCollection(ammo.values()); }
    public ArmorType vest(String id) { return id == null ? null : vests.get(id.toLowerCase()); }
    public List<String> vestIds() { return new ArrayList<>(vests.keySet()); }
    public java.util.Collection<ArmorType> vests() { return Collections.unmodifiableCollection(vests.values()); }

    public Material vestBase() {
        try { return Material.valueOf(plugin.getConfig().getString("armor-base", "LEATHER_CHESTPLATE").toUpperCase()); }
        catch (IllegalArgumentException e) { return Material.LEATHER_CHESTPLATE; }
    }

    /** A vest: dyed chestplate, custom model, +hearts max health while worn, durability = absorb-hearts (in
     *  half-hearts), no vanilla armour points (bullets are handled by Ballistics, not the armour formula). */
    public ItemStack buildVest(ArmorType v) {
        ItemStack item = new ItemStack(vestBase());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name(v.name()));
        meta.lore(List.of(
            Component.text("+" + trim(v.hearts()) + " heart" + (v.hearts() == 1 ? "" : "s") + " while worn", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("Soaks " + Math.round(v.absorb() * 100) + "% of each bullet, " + trim(v.absorbHearts()) + " hearts before it breaks", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        setModel(meta, v.model());
        if (meta instanceof org.bukkit.inventory.meta.LeatherArmorMeta lam) lam.setColor(org.bukkit.Color.fromRGB(v.color() & 0xFFFFFF));
        meta.addAttributeModifier(org.bukkit.attribute.Attribute.MAX_HEALTH, new org.bukkit.attribute.AttributeModifier(
            new NamespacedKey(plugin, "vest_health"), v.hearts() * 2, org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER,
            org.bukkit.inventory.EquipmentSlotGroup.CHEST));
        meta.getPersistentDataContainer().set(vestKey, PersistentDataType.STRING, v.id());
        meta.getPersistentDataContainer().set(uidKey, PersistentDataType.STRING, UUID.randomUUID().toString());
        if (meta instanceof Damageable d) { d.setMaxDamage((int) Math.round(v.absorbHearts() * 2)); d.setDamage(0); }
        item.setItemMeta(meta);
        return item;
    }

    /** "Damage  ███████░░░ 7.0" - value is a 0..1 fraction of the configured maximum. */
    private static Component stat(String label, double frac, NamedTextColor color) {
        double v = Math.max(0, Math.min(10, Math.round(frac * 100) / 10.0));
        int filled = (int) Math.round(v);
        String bar = "█".repeat(filled) + "░".repeat(10 - filled);
        String padded = (label + "          ").substring(0, 10);
        return Component.text(padded, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            .append(Component.text(bar + " ", color))
            .append(Component.text(String.format(java.util.Locale.ROOT, "%.1f", v), NamedTextColor.WHITE));
    }

    private static String trim(double d) { return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d); }

    public ArmorType vestOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return vest(it.getItemMeta().getPersistentDataContainer().get(vestKey, PersistentDataType.STRING));
    }

    /** Soak `halfHearts` of damage into the vest's durability. Returns true if it broke (caller removes it). */
    public boolean damageVest(ItemStack it, double halfHearts) {
        ItemMeta meta = it.getItemMeta();
        if (!(meta instanceof Damageable d)) return false;
        int max = d.hasMaxDamage() ? d.getMaxDamage() : 80;
        int dmg = d.getDamage() + (int) Math.ceil(halfHearts);
        if (dmg >= max) return true;
        d.setDamage(dmg);
        it.setItemMeta(meta);
        return false;
    }

    /** [frames, frameTicks] of a model's clip ("fire", "reload", "equip", "pump"), or null if none generated. */
    public int[] anim(String model, String clip) {
        Map<String, int[]> c = anims.get(model);
        if (c == null) return null;
        int[] a = c.get(clip);
        return a == null || a[0] <= 0 ? null : a;
    }

    private static List<int[]> pixels(String joined) {
        List<int[]> out = new ArrayList<>();
        if (joined == null || joined.isEmpty()) return out;
        for (String p : joined.split(";")) {
            String[] uv = p.split(",");
            if (uv.length == 2) try { out.add(new int[]{ Integer.parseInt(uv[0].trim()), Integer.parseInt(uv[1].trim()) }); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    public GrenadeType grenade(String id) { return id == null ? null : grenades.get(id.toLowerCase()); }
    public List<String> grenadeIds() { return new ArrayList<>(grenades.keySet()); }
    public java.util.Collection<GrenadeType> grenades() { return Collections.unmodifiableCollection(grenades.values()); }

    public ItemStack buildGrenade(GrenadeType g, int amount) {
        ItemStack item = new ItemStack(base(), Math.max(1, Math.min(16, amount)));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name(g.name()));
        meta.lore(List.of(
            Component.text("Left-click: pull the pin and throw" + (g.cook() ? " (" + trim(g.fuse()) + " s fuse)" : ""), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        setModel(meta, g.model());
        meta.setMaxStackSize(16);
        meta.getPersistentDataContainer().set(grenadeKey, PersistentDataType.STRING, g.id());
        item.setItemMeta(meta);
        return item;
    }

    public GrenadeType grenadeOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return grenade(it.getItemMeta().getPersistentDataContainer().get(grenadeKey, PersistentDataType.STRING));
    }

    /** "tick:firearms:sound" entries of a clip, or an empty list. */
    public List<String> animSounds(String model, String clip) {
        Map<String, List<String>> m = animSounds.get(model);
        if (m == null) return List.of();
        return m.getOrDefault(clip, List.of());
    }

    /** Is this gun item in AUTO mode right now (per-item for switchable guns, else the gun's default)? */
    public boolean isAuto(ItemStack it, GunType gun) {
        if (!gun.switchable() || it == null || !it.hasItemMeta()) return gun.auto();
        String m = it.getItemMeta().getPersistentDataContainer().get(new NamespacedKey(plugin, "mode"), PersistentDataType.STRING);
        return m == null ? gun.auto() : m.equals("auto");
    }

    /** Flip a switchable gun between semi and auto. Returns the new mode name. */
    public String toggleMode(ItemStack it, GunType gun) {
        boolean auto = !isAuto(it, gun);
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "mode"), PersistentDataType.STRING, auto ? "auto" : "semi");
        it.setItemMeta(meta);
        return auto ? "AUTO" : "SEMI";
    }

    /** The casing model a gun ejects: models/<gunmodel>_casing.bbmodel if that file exists, else guns.yml casing:
     *  (default "casing"), or null for "none". */
    public String casingModel(GunType g) {
        File own = new File(new File(plugin.getDataFolder(), "models"), g.model() + "_casing.bbmodel");
        if (own.exists()) return g.model() + "_casing";
        String c = g.casing();
        if (c == null || c.isEmpty() || c.equalsIgnoreCase("none")) return null;
        return c;
    }

    public ItemStack buildCasing(String model) {
        ItemStack item = new ItemStack(base());
        ItemMeta meta = item.getItemMeta();
        setModel(meta, model == null || model.isEmpty() ? "casing" : model);
        item.setItemMeta(meta);
        return item;
    }

    /** Does this gun get first-person arms (guns.yml arms.enabled, else config arms.enabled)? */
    public boolean armsEnabled(GunType g) {
        if (g.arms() != null && g.arms().contains("enabled")) return g.arms().getBoolean("enabled");
        return plugin.getConfig().getBoolean("arms.enabled", false);
    }
    public boolean leftArm(GunType g) {
        if (g.arms() != null && g.arms().contains("left.enabled")) return g.arms().getBoolean("left.enabled");
        return plugin.getConfig().getBoolean("arms.left.enabled", false);   // arms are OFF by default since 0.7: model hands in the .bbmodel
    }

    /** Write the holder's skin pixel colours into the gun's custom_model_data.colors (the arms' tints). */
    public boolean applySkin(ItemStack gun, GunType type, org.bukkit.entity.Player holder) {
        boolean slim = ArmSkin.slim(holder);
        List<int[]> order = skinClassic.get(type.model());
        List<org.bukkit.Color> colors;
        if (order != null) {                                     // hands modelled in the .bbmodel
            List<int[]> use = slim && skinSlim.get(type.model()) != null && !skinSlim.get(type.model()).isEmpty() ? skinSlim.get(type.model()) : order;
            colors = ArmSkin.colorsFor(holder.getUniqueId(), use);
        } else if (armsEnabled(type)) {                          // the old generated arms
            colors = ArmSkin.colors(holder.getUniqueId(), leftArm(type));
        } else return false;
        if (colors == null) return false;
        ItemMeta meta = gun.getItemMeta();
        if (meta == null) return false;
        NamespacedKey skinOf = new NamespacedKey(plugin, "skin_of");
        String stamp = holder.getUniqueId() + ":" + (slim ? 1 : 0) + ":" + colors.size();
        if (stamp.equals(meta.getPersistentDataContainer().get(skinOf, PersistentDataType.STRING))) return false;
        var cmd = meta.getCustomModelDataComponent();
        cmd.setColors(colors);
        cmd.setFlags(List.of(slim));                             // flag 0 = slim -> the pack shows the slim hands
        meta.setCustomModelDataComponent(cmd);
        meta.getPersistentDataContainer().set(skinOf, PersistentDataType.STRING, stamp);
        gun.setItemMeta(meta);
        return true;
    }

    // ------------------------------------------------------------------ items

    public Material base() {
        try { return Material.valueOf(plugin.getConfig().getString("item-base", "BRICK").toUpperCase()); }
        catch (IllegalArgumentException e) { return Material.BRICK; }
    }

    private static Component name(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy).decoration(TextDecoration.ITALIC, false);
    }

    public ItemStack buildGun(GunType gun) {
        ItemStack item = new ItemStack(base());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name(gun.name()));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(gun.switchable() ? "SEMI / AUTO - right-click to switch" : gun.auto() ? "AUTO - hold left-click" : "SEMI - one shot per click", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text(gun.usesMag() ? "Magazine: " + gun.magId() : "Loads " + gun.ammoId() + " one by one", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        // Stat sheet on a 0-10 scale (10 = the configured maximum, config stats.max.*), so guns are comparable at a glance.
        ConfigurationSection mx = plugin.getConfig().getConfigurationSection("stats.max");
        double mDmg = mx == null ? 20 : mx.getDouble("damage", 20), mRate = mx == null ? 15 : mx.getDouble("fire-rate", 15), mRec = mx == null ? 8 : mx.getDouble("recoil", 8),
               mSpread = mx == null ? 6 : mx.getDouble("spread", 6), mRange = mx == null ? 160 : mx.getDouble("range", 160);
        lore.add(stat("Damage", gun.damage() * gun.pellets() / mDmg, NamedTextColor.RED));
        lore.add(stat("Fire rate", gun.fireRate() / mRate, NamedTextColor.GOLD));
        lore.add(stat("Accuracy", 1 - Math.min(1, gun.accuracy() / mSpread), NamedTextColor.GREEN));
        lore.add(stat("Recoil", gun.recoil() / mRec, NamedTextColor.LIGHT_PURPLE));
        lore.add(stat("Range", gun.range() / mRange, NamedTextColor.AQUA));
        lore.add(Component.text("F = reload", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        setModel(meta, gun.model());
        var pdc = meta.getPersistentDataContainer();
        pdc.set(gunKey, PersistentDataType.STRING, gun.id());
        // A fresh gun comes loaded with its FIRST accepted magazine type; capacity = that magazine's.
        int cap = gun.magazine();
        if (gun.usesMag()) for (String s : gun.magId().split(",")) {
            MagType mt = mag(s.trim());
            if (mt != null) { cap = mt.capacity(); pdc.set(new NamespacedKey(plugin, "magtype"), PersistentDataType.STRING, mt.id()); break; }
        }
        pdc.set(new NamespacedKey(plugin, "cap"), PersistentDataType.INTEGER, cap);
        pdc.set(uidKey, PersistentDataType.STRING, UUID.randomUUID().toString());   // guns never stack
        item.setItemMeta(meta);
        setRounds(item, cap, cap);
        return components(item);
    }

    public ItemStack buildMag(MagType mag, int rounds) {
        ItemStack item = new ItemStack(base());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name(mag.name()));
        setModel(meta, mag.model());
        var pdc = meta.getPersistentDataContainer();
        pdc.set(magKey, PersistentDataType.STRING, mag.id());
        item.setItemMeta(meta);
        setRounds(item, Math.max(0, Math.min(mag.capacity(), rounds)), mag.capacity());
        return item;
    }

    public ItemStack buildAmmo(AmmoType a, int amount) {
        ItemStack item = new ItemStack(base(), Math.max(1, Math.min(99, amount)));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name(a.name()));
        setModel(meta, a.model());
        meta.setMaxStackSize(a.stack());
        meta.getPersistentDataContainer().set(ammoKey, PersistentDataType.STRING, a.id());
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack buildCrater() {
        ItemStack item = new ItemStack(base());
        ItemMeta meta = item.getItemMeta();
        setModel(meta, plugin.getConfig().getString("craters.model", "crater"));
        meta.getPersistentDataContainer().set(craterKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public GunType gunOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return gun(it.getItemMeta().getPersistentDataContainer().get(gunKey, PersistentDataType.STRING));
    }
    public MagType magOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return mag(it.getItemMeta().getPersistentDataContainer().get(magKey, PersistentDataType.STRING));
    }
    public AmmoType ammoOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return ammo(it.getItemMeta().getPersistentDataContainer().get(ammoKey, PersistentDataType.STRING));
    }

    public int rounds(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return 0;
        return it.getItemMeta().getPersistentDataContainer().getOrDefault(roundsKey, PersistentDataType.INTEGER, 0);
    }

    /** Store the round count and mirror it on the item's durability bar (full = no bar). */
    public void setRounds(ItemStack it, int rounds, int capacity) {
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return;
        rounds = Math.max(0, Math.min(capacity, rounds));
        meta.getPersistentDataContainer().set(roundsKey, PersistentDataType.INTEGER, rounds);
        if (meta instanceof Damageable d) {
            d.setMaxDamage(capacity + 1);          // +1 so an empty gun never reaches "broken"
            d.setDamage(capacity - rounds);
        }
        it.setItemMeta(meta);
    }

    /** Set the item's base custom_model_data string (index 0). */
    public void setModel(ItemMeta meta, String model) {
        var cmd = meta.getCustomModelDataComponent();
        List<String> strings = new ArrayList<>(cmd.getStrings());
        if (strings.isEmpty()) strings.add(model); else strings.set(0, model);
        cmd.setStrings(strings);
        meta.setCustomModelDataComponent(cmd);
    }

    public void setModel(ItemStack it, String model) {
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return;
        setModel(meta, model);
        it.setItemMeta(meta);
    }

    public String model(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return "";
        List<String> s = it.getItemMeta().getCustomModelDataComponent().getStrings();
        return s.isEmpty() ? "" : s.get(0);
    }

    // ------------------------------------------------------------------ item components

    /** Bake the vanilla components a gun needs: the attack swing stretched to invisibility (so the arm never
     *  visibly punches - 26.2 swing_animation, 26.3+ attack_animation) and can_break on every block, so an
     *  ADVENTURE player's client is allowed to "mine" the hold-detect barrier (nothing can actually break). */
    ItemStack components(ItemStack item) {
        String swing = "={type:\"whack\",duration:2147483647}";
        ItemStack sw = component(item, "minecraft:swing_animation" + swing, true);     // 26.2 name (quiet if missing)
        if (sw == null) sw = component(item, "minecraft:attack_animation" + swing, false); // 26.3+ name
        if (sw != null) { item = sw; swingOk = true; }
        ItemStack cb = component(item, "minecraft:can_break=[{}]", true);
        if (cb == null) cb = component(item, "minecraft:can_break={predicates:[{}]}", false);
        if (cb != null) {
            item = cb;
            canBreakOk = true;
            ItemStack td = component(item, "minecraft:tooltip_display={hidden_components:[\"minecraft:can_break\"]}", false);
            if (td != null) item = td;
        }
        return item;
    }

    /** Apply one item component through the vanilla item-string parser; null if this server rejects it.
     *  NOTE: Paper prepends the item id itself - pass ONLY the "[component]" part. */
    private static ItemStack component(ItemStack item, String component, boolean quiet) {
        try {
            return Bukkit.getUnsafe().modifyItemStack(item, "[" + component + "]");
        } catch (Throwable t) {
            if (!quiet && componentWarned.add(component))
                Bukkit.getLogger().warning("[Firearms] component rejected by this server: " + component);
            return null;
        }
    }

    static void resetComponentProbe() { componentWarned.clear(); canBreakOk = false; swingOk = false; }
}
