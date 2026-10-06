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
    /** model -> clip -> [frames, frameTicks], written by the pack generator (models/anim-index.yml). */
    private final Map<String, Map<String, int[]>> anims = new LinkedHashMap<>();

    final NamespacedKey gunKey, magKey, ammoKey, roundsKey, uidKey, craterKey;

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
    }

    // ------------------------------------------------------------------ loading

    public void load() {
        guns.clear(); mags.clear(); ammo.clear(); anims.clear();
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
                s.getString("fire-mode", "semi").trim().equalsIgnoreCase("auto"),
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
                (float) s.getDouble("pitch", 1.6)));
        }
        // Animation frame index written by the pack generator.
        File ai = new File(new File(plugin.getDataFolder(), "models"), "anim-index.yml");
        if (ai.exists()) {
            YamlConfiguration a = YamlConfiguration.loadConfiguration(ai);
            for (String model : a.getKeys(false)) {
                ConfigurationSection s = a.getConfigurationSection(model);
                if (s == null) continue;
                Map<String, int[]> clips = new LinkedHashMap<>();
                for (String clip : s.getKeys(false)) {
                    clips.put(clip, new int[]{ s.getInt(clip + ".frames", 0), Math.max(1, s.getInt(clip + ".frame-ticks", 1)) });
                }
                anims.put(model, clips);
            }
        }
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

    /** [frames, frameTicks] of a model's clip ("fire", "reload", "equip", "pump"), or null if none generated. */
    public int[] anim(String model, String clip) {
        Map<String, int[]> c = anims.get(model);
        if (c == null) return null;
        int[] a = c.get(clip);
        return a == null || a[0] <= 0 ? null : a;
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
        meta.lore(List.of(
            Component.text(gun.auto() ? "AUTO - hold left-click" : "SEMI - one shot per click", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text(gun.usesMag() ? "Magazine: " + gun.magId() : "Loads " + gun.ammoId() + " one by one", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("F = reload", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
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
        ItemStack sw = component(item, "minecraft:swing_animation" + swing);
        if (sw == null) sw = component(item, "minecraft:attack_animation" + swing);
        if (sw != null) { item = sw; swingOk = true; }
        ItemStack cb = component(item, "minecraft:can_break=[{}]");
        if (cb == null) cb = component(item, "minecraft:can_break={predicates:[{}]}");
        if (cb != null) {
            item = cb;
            canBreakOk = true;
            ItemStack td = component(item, "minecraft:tooltip_display={hidden_components:[\"minecraft:can_break\"]}");
            if (td != null) item = td;
        }
        return item;
    }

    /** Apply one item component through the vanilla item-string parser; null if this server rejects it.
     *  NOTE: Paper prepends the item id itself - pass ONLY the "[component]" part. */
    private static ItemStack component(ItemStack item, String component) {
        try {
            return Bukkit.getUnsafe().modifyItemStack(item, "[" + component + "]");
        } catch (Throwable t) {
            if (componentWarned.add(component))
                Bukkit.getLogger().warning("[Firearms] component rejected by this server: " + component);
            return null;
        }
    }

    static void resetComponentProbe() { componentWarned.clear(); canBreakOk = false; swingOk = false; }
}
