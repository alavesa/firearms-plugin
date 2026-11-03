package fi.alavesa.firearms;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /viewmodel: each player's own placement of the first-person gun, like Counter-Strike's viewmodel settings -
 * offset (right, up, forward), fov and scale - applied by the display rig for that player only, at once, without
 * touching the pack. Stored in plugins/Firearms/viewmodels.yml. A setting can be for every gun ("*") or one gun.
 * Only guns in display mode can follow it: a frame-mode gun's placement is baked into the pack for everybody.
 */
public final class Viewmodels implements org.bukkit.command.CommandExecutor, TabCompleter {
    /** One player's settings for one gun id (or "*"). Offsets in blocks of view space; fov like CS (60 = stock). */
    public static final class Setting {
        public double x, y, z, scale = 1, fov = 60;
        boolean isDefault() { return x == 0 && y == 0 && z == 0 && scale == 1 && fov == 60; }
    }

    private final FirearmsPlugin plugin;
    private final Map<UUID, Map<String, Setting>> settings = new ConcurrentHashMap<>();
    private final File file;

    public Viewmodels(FirearmsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "viewmodels.yml");
        load();
    }

    /** The setting in force for this player and gun (the gun's own, else "*", else stock). */
    public Setting of(Player p, String gunId) {
        Map<String, Setting> m = settings.get(p.getUniqueId());
        if (m == null) return new Setting();
        Setting s = m.get(gunId);
        if (s == null) s = m.get("*");
        return s == null ? new Setting() : s;
    }

    private void load() {
        settings.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String id : y.getKeys(false)) {
            UUID u; try { u = UUID.fromString(id); } catch (IllegalArgumentException e) { continue; }
            var sec = y.getConfigurationSection(id);
            if (sec == null) continue;
            Map<String, Setting> m = new ConcurrentHashMap<>();
            for (String gun : sec.getKeys(false)) {
                var g = sec.getConfigurationSection(gun);
                if (g == null) continue;
                Setting s = new Setting();
                s.x = g.getDouble("x", 0); s.y = g.getDouble("y", 0); s.z = g.getDouble("z", 0); s.scale = g.getDouble("scale", 1); s.fov = g.getDouble("fov", 60);
                m.put(gun, s);
            }
            settings.put(u, m);
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (var e : settings.entrySet()) for (var g : e.getValue().entrySet()) {
            String k = e.getKey() + "." + g.getKey();
            Setting s = g.getValue();
            y.set(k + ".x", s.x); y.set(k + ".y", s.y); y.set(k + ".z", s.z); y.set(k + ".scale", s.scale); y.set(k + ".fov", s.fov);
        }
        try { y.save(file); } catch (Exception ex) { plugin.getLogger().warning("viewmodels.yml could not be saved: " + ex.getMessage()); }
    }

    private static final double MAX_OFFSET = 1.0, MIN_FOV = 40, MAX_FOV = 110, MIN_SCALE = 0.3, MAX_SCALE = 2.0;

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) { help(p); return true; }
        // optional gun id first: /viewmodel rifle offset ...
        String gun = "*";
        int i = 0;
        if (plugin.registry.gun(args[0].toLowerCase(Locale.ROOT)) != null) { gun = args[0].toLowerCase(Locale.ROOT); i = 1; }
        if (args.length <= i) { help(p); return true; }
        Map<String, Setting> m = settings.computeIfAbsent(p.getUniqueId(), x -> new ConcurrentHashMap<>());
        Setting s = m.computeIfAbsent(gun, x -> new Setting());
        String sub = args[i].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "offset", "pos", "position" -> {
                    if (args.length < i + 4) { p.sendMessage(Component.text("/viewmodel [gun] offset <right> <up> <forward>   (blocks, -1..1; e.g. 0.1 -0.05 0)", NamedTextColor.YELLOW)); return true; }
                    s.x = clamp(Double.parseDouble(args[i + 1]), -MAX_OFFSET, MAX_OFFSET);
                    s.y = clamp(Double.parseDouble(args[i + 2]), -MAX_OFFSET, MAX_OFFSET);
                    s.z = clamp(Double.parseDouble(args[i + 3]), -MAX_OFFSET, MAX_OFFSET);
                }
                case "x" -> s.x = clamp(Double.parseDouble(args[i + 1]), -MAX_OFFSET, MAX_OFFSET);
                case "y" -> s.y = clamp(Double.parseDouble(args[i + 1]), -MAX_OFFSET, MAX_OFFSET);
                case "z" -> s.z = clamp(Double.parseDouble(args[i + 1]), -MAX_OFFSET, MAX_OFFSET);
                case "fov" -> s.fov = clamp(Double.parseDouble(args[i + 1]), MIN_FOV, MAX_FOV);
                case "scale", "size" -> s.scale = clamp(Double.parseDouble(args[i + 1]), MIN_SCALE, MAX_SCALE);
                case "reset" -> { m.remove(gun); if (gun.equals("*")) m.clear(); }
                case "show", "get" -> { }
                default -> { help(p); return true; }
            }
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) { p.sendMessage(Component.text("Numbers, please: /viewmodel [gun] offset <right> <up> <forward> | fov <40-110> | scale <0.3-2> | reset", NamedTextColor.RED)); return true; }
        if (m.isEmpty()) settings.remove(p.getUniqueId());
        save();
        Setting now = of(p, gun);
        p.sendMessage(Component.text("Viewmodel" + (gun.equals("*") ? "" : " for " + gun) + ": offset " + fmt(now.x) + " " + fmt(now.y) + " " + fmt(now.z) + ", fov " + fmt(now.fov) + ", scale " + fmt(now.scale), NamedTextColor.GREEN));
        GunType held = plugin.registry.gunOf(p.getInventory().getItemInMainHand());
        if (held != null && !plugin.registry.displayMode(held.model()))
            p.sendMessage(Component.text("(" + held.id() + " is a frame-mode gun: its placement is baked into the pack for everybody. The viewmodel applies to guns in display mode - guns.yml  anim: { mode: display }.)", NamedTextColor.GRAY));
        plugin.rig().refresh(p);
        return true;
    }

    private void help(Player p) {
        p.sendMessage(Component.text("/viewmodel offset <right> <up> <forward>   move the gun (blocks, e.g. 0.1 -0.05 0.05)", NamedTextColor.YELLOW));
        p.sendMessage(Component.text("/viewmodel fov <40-110>   60 = stock; higher = smaller, further gun (Counter-Strike's viewmodel_fov)", NamedTextColor.YELLOW));
        p.sendMessage(Component.text("/viewmodel scale <0.3-2>   |   /viewmodel reset   |   /viewmodel show", NamedTextColor.YELLOW));
        p.sendMessage(Component.text("Put a gun id first to set it for that gun only: /viewmodel rifle offset 0.2 0 0", NamedTextColor.GRAY));
        p.sendMessage(Component.text("Yours only, applied at once; no pack reload. Works for guns in display mode.", NamedTextColor.GRAY));
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static String fmt(double v) { return String.valueOf(Math.round(v * 1000) / 1000.0); }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) { out.addAll(List.of("offset", "fov", "scale", "reset", "show")); out.addAll(plugin.registry.gunIds()); }
        else if (args.length == 2 && plugin.registry.gun(args[0].toLowerCase(Locale.ROOT)) != null) out.addAll(List.of("offset", "fov", "scale", "reset", "show"));
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.startsWith(last));
        return out;
    }
}
