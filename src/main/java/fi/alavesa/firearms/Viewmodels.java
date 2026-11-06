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
 * /viewmodel: each player's own placement of the first-person gun - one offset (right, up, forward) for EVERY gun,
 * applied by the display rig for that player only, at once, without touching the pack. Each value is -5..5 in
 * sixteenths of a block (the unit of a model pixel). Where a gun sits relative to the other guns is the model's
 * own business (/firearms pose, baked into the pack for everybody); this only shifts the whole view for one player.
 * Stored in plugins/Firearms/viewmodels.yml.
 */
public final class Viewmodels implements org.bukkit.command.CommandExecutor, TabCompleter {
    /** One player's offset in sixteenths of a block: right, up, forward. */
    public static final class Setting {
        public double x, y, z;
        boolean isDefault() { return x == 0 && y == 0 && z == 0; }
    }

    static final double MAX = 5.0;

    private final FirearmsPlugin plugin;
    private final Map<UUID, Setting> settings = new ConcurrentHashMap<>();
    private final File file;

    public Viewmodels(FirearmsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "viewmodels.yml");
        load();
    }

    /** The offset in force for this player (stock when none). */
    public Setting of(Player p) {
        Setting s = settings.get(p.getUniqueId());
        return s == null ? new Setting() : s;
    }

    static double clamp(double v) { return Math.max(-MAX, Math.min(MAX, v)); }

    private void load() {
        settings.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String id : y.getKeys(false)) {
            UUID u; try { u = UUID.fromString(id); } catch (IllegalArgumentException e) { continue; }
            var sec = y.getConfigurationSection(id);
            if (sec == null) continue;
            // 0.26 wrote per-gun sections ("*" / gun id, in blocks); only the "*" one is carried over, in pixels
            if (sec.contains("x")) read(u, sec.getDouble("x"), sec.getDouble("y"), sec.getDouble("z"));
            else if (sec.getConfigurationSection("*") != null) { var g = sec.getConfigurationSection("*"); read(u, g.getDouble("x") * 16, g.getDouble("y") * 16, g.getDouble("z") * 16); }
        }
    }
    private void read(UUID u, double x, double y, double z) {
        Setting s = new Setting(); s.x = clamp(x); s.y = clamp(y); s.z = clamp(z);
        if (!s.isDefault()) settings.put(u, s);
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (var e : settings.entrySet()) { String k = e.getKey().toString(); y.set(k + ".x", e.getValue().x); y.set(k + ".y", e.getValue().y); y.set(k + ".z", e.getValue().z); }
        try { y.save(file); } catch (Exception ex) { plugin.getLogger().warning("viewmodels.yml could not be saved: " + ex.getMessage()); }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // console / admin form: /viewmodel <player> <right> <up> <forward> | reset | show
        if (args.length >= 2 && (!(sender instanceof Player) || sender.hasPermission("firearms.admin"))) {
            Player other = plugin.getServer().getPlayerExact(args[0]);
            if (other != null) {
                String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
                boolean ok = apply(other, rest);
                if (ok) { Setting now = of(other); sender.sendMessage(Component.text(other.getName() + " viewmodel offset: " + fmt(now.x) + " " + fmt(now.y) + " " + fmt(now.z), NamedTextColor.GREEN)); }
                else sender.sendMessage(Component.text("/viewmodel <player> <right> <up> <forward> | reset | show", NamedTextColor.YELLOW));
                return true;
            }
        }
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) { help(p); return true; }
        if (!apply(p, args)) { help(p); return true; }
        Setting now = of(p);
        p.sendMessage(Component.text("Viewmodel offset: right " + fmt(now.x) + ", up " + fmt(now.y) + ", forward " + fmt(now.z) + (now.isDefault() ? " (stock)" : ""), NamedTextColor.GREEN));
        GunType held = plugin.registry.gunOf(p.getInventory().getItemInMainHand());
        if (held != null && !plugin.registry.displayMode(held.model()))
            p.sendMessage(Component.text("(" + held.id() + " is not on the display rig - its first person is baked into the pack. Guns follow /viewmodel in display mode: config anim.mode: display, then /firearms pack.)", NamedTextColor.GRAY));
        return true;
    }

    /** Parse and store an offset command for `p`; false = bad syntax. Refreshes the rig. */
    private boolean apply(Player p, String[] args) {
        String sub = args.length == 0 ? "show" : args[0].toLowerCase(Locale.ROOT);
        int i = sub.equals("offset") || sub.equals("set") ? 1 : 0;
        if (sub.equals("reset")) { settings.remove(p.getUniqueId()); }
        else if (sub.equals("show") || sub.equals("get")) { }
        else {
            if (args.length < i + 3) return false;
            Setting s = new Setting();
            try {
                s.x = clamp(Double.parseDouble(args[i]));
                s.y = clamp(Double.parseDouble(args[i + 1]));
                s.z = clamp(Double.parseDouble(args[i + 2]));
            } catch (NumberFormatException e) { return false; }
            if (s.isDefault()) settings.remove(p.getUniqueId()); else settings.put(p.getUniqueId(), s);
        }
        save();
        plugin.rig().refresh(p);
        return true;
    }

    private void help(Player p) {
        p.sendMessage(Component.text("/viewmodel <right> <up> <forward>   move your guns on screen; each -5..5, 1 = one sixteenth of a block (a model pixel)", NamedTextColor.YELLOW));
        p.sendMessage(Component.text("/viewmodel reset   |   /viewmodel show", NamedTextColor.YELLOW));
        p.sendMessage(Component.text("Yours only, every gun, applied at once; no pack reload.", NamedTextColor.GRAY));
    }

    private static String fmt(double v) { return String.valueOf(Math.round(v * 100) / 100.0); }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) out.addAll(List.of("0", "reset", "show"));
        else if (args.length <= 3) out.add("0");
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.startsWith(last));
        return out;
    }
}
