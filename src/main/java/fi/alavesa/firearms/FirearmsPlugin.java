package fi.alavesa.firearms;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public final class FirearmsPlugin extends JavaPlugin {

    private Registry registry;
    private Ballistics ballistics;
    private HoldDetector hold;
    private FireController controller;
    private String duplicateJars;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        new File(getDataFolder(), "models").mkdirs();
        registry = new Registry(this);
        registry.load();
        ballistics = new Ballistics(this, registry);
        hold = new HoldDetector(this, registry);
        controller = new FireController(this, registry, ballistics);
        getServer().getPluginManager().registerEvents(controller, this);
        getServer().getScheduler().runTaskTimer(this, hold::tick, 1L, 1L);
        getServer().getScheduler().runTaskTimer(this, hold::poll, 20L, 5L);
        getServer().getScheduler().runTaskTimer(this, ballistics::tick, 1L, 1L);
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) { hold.forget(e.getPlayer().getUniqueId()); }
        }, this);
        // Probe the item components once the server is fully up (the item-string parser is not usable during enable).
        getServer().getScheduler().runTaskLater(this, () -> {
            Registry.resetComponentProbe();
            for (GunType g : registry.guns()) { registry.buildGun(g); break; }
            getLogger().info("Components: swing-hide " + (Registry.swingOk ? "OK" : "unsupported") + ", adventure can_break "
                + (Registry.canBreakOk ? "OK" : "unsupported") + "; packet recoil " + (NmsRecoil.available() ? "OK" : "fallback"));
        }, 100L);
        // The #1 reason an update "did nothing": an older Firearms-x.y.z.jar left next to the new one. Paper
        // then loads only ONE of them (the first by name = the OLD one). Shout about it in the console and to ops.
        File[] jars = getDataFolder().getParentFile().listFiles((d, n) -> n.toLowerCase().startsWith("firearms") && n.toLowerCase().endsWith(".jar"));
        if (jars != null && jars.length > 1) {
            StringBuilder sb = new StringBuilder();
            for (File j : jars) sb.append(j.getName()).append(" ");
            duplicateJars = sb.toString().trim();
            getLogger().severe("=======================================================================");
            getLogger().severe("MULTIPLE Firearms jars in plugins/: " + duplicateJars);
            getLogger().severe("Paper only loads ONE of them (usually the OLD one). Delete the old jar and restart.");
            getLogger().severe("=======================================================================");
        }
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
                if (duplicateJars != null && e.getPlayer().hasPermission("firearms.admin"))
                    e.getPlayer().sendMessage(Component.text("[Firearms] Several Firearms jars in plugins/ (" + duplicateJars + ") - delete the old one and restart, or updates do nothing.", NamedTextColor.RED));
            }
        }, this);
        getLogger().info("Firearms enabled - guns: " + registry.gunIds() + ", mags: " + registry.magIds() + ", ammo: " + registry.ammoIds()
            + ". Drop .bbmodel files into plugins/Firearms/models and run /firearms pack.");
    }

    @Override
    public void onDisable() {
        if (controller != null) controller.shutdown();
        if (hold != null) hold.clearAll();
        if (ballistics != null) ballistics.clearAll();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) return usage(sender);
        switch (args[0].toLowerCase()) {
            case "list" -> {
                sender.sendMessage(Component.text("Guns: " + String.join(", ", registry.gunIds()), NamedTextColor.GOLD));
                sender.sendMessage(Component.text("Mags: " + String.join(", ", registry.magIds()), NamedTextColor.YELLOW));
                sender.sendMessage(Component.text("Ammo: " + String.join(", ", registry.ammoIds()), NamedTextColor.GRAY));
                sender.sendMessage(Component.text("Vests: " + String.join(", ", registry.vestIds()), NamedTextColor.AQUA));
                return true;
            }
            case "give" -> {
                if (!sender.hasPermission("firearms.give")) return deny(sender);
                if (args.length < 2) return usage(sender);
                Player target = args.length >= 4 ? Bukkit.getPlayer(args[3]) : sender instanceof Player p ? p : null;
                if (target == null) { sender.sendMessage(Component.text("Player not found / not a player.", NamedTextColor.RED)); return true; }
                int amount = 1;
                if (args.length >= 3) try { amount = Math.max(1, Integer.parseInt(args[2])); } catch (NumberFormatException ignored) { }
                String id = args[1].toLowerCase();
                List<ItemStack> items = new ArrayList<>();
                GunType g = registry.gun(id);
                MagType m = registry.mag(id);
                AmmoType a = registry.ammo(id);
                ArmorType v = registry.vest(id);
                if (v != null) for (int i = 0; i < amount; i++) items.add(registry.buildVest(v));
                else if (g != null) for (int i = 0; i < amount; i++) items.add(registry.buildGun(g));
                else if (m != null) for (int i = 0; i < amount; i++) items.add(registry.buildMag(m, m.capacity()));
                else if (a != null) {
                    int left = amount;
                    while (left > 0) { int n = Math.min(left, a.stack()); items.add(registry.buildAmmo(a, n)); left -= n; }
                } else { sender.sendMessage(Component.text("Unknown id: " + id, NamedTextColor.RED)); return true; }
                for (ItemStack it : items) target.getInventory().addItem(it).values().forEach(l -> target.getWorld().dropItemNaturally(target.getLocation(), l));
                sender.sendMessage(Component.text("Gave " + amount + " x " + id + " to " + target.getName(), NamedTextColor.GREEN));
                return true;
            }
            case "pack" -> {
                if (!sender.hasPermission("firearms.admin")) return deny(sender);
                try {
                    PackGenerator.Result r = new PackGenerator(this, registry).generate();
                    registry.load();
                    sender.sendMessage(Component.text("Pack written: " + r.zip().getPath(), NamedTextColor.GREEN));
                    sender.sendMessage(Component.text(r.models() + " .bbmodel converted, " + r.frames() + " animation frames baked, "
                        + r.placeholders() + " placeholder model(s)", NamedTextColor.GRAY));
                    for (String w : r.warnings()) sender.sendMessage(Component.text("! " + w, NamedTextColor.YELLOW));
                } catch (Exception ex) {
                    sender.sendMessage(Component.text("Pack generation failed: " + ex, NamedTextColor.RED));
                    getLogger().warning("Pack generation failed: " + ex);
                }
                return true;
            }
            case "models" -> {
                File dir = new File(getDataFolder(), "models");
                sender.sendMessage(Component.text("Model files expected in " + dir.getPath() + ":", NamedTextColor.GOLD));
                for (GunType g : registry.guns()) sender.sendMessage(line(dir, g.model(), "gun " + g.id()));
                for (MagType m : registry.mags()) sender.sendMessage(line(dir, m.model(), "mag " + m.id()));
                for (AmmoType a : registry.ammos()) sender.sendMessage(line(dir, a.model(), "ammo " + a.id()));
                for (ArmorType v : registry.vests()) sender.sendMessage(line(dir, v.model(), "vest " + v.id() + " (or " + v.model() + ".png icon)"));
                sender.sendMessage(line(dir, getConfig().getString("craters.model", "crater"), "crater (or crater.png)"));
                return true;
            }
            case "version" -> { return usage(sender); }
            case "reload" -> {
                if (!sender.hasPermission("firearms.admin")) return deny(sender);
                reloadConfig();
                registry.load();
                ballistics.load();
                sender.sendMessage(Component.text("Firearms reloaded.", NamedTextColor.GREEN));
                return true;
            }
            default -> { return usage(sender); }
        }
    }

    private Component line(File dir, String model, String what) {
        boolean has = new File(dir, model + ".bbmodel").exists();
        return Component.text("  " + model + ".bbmodel ", has ? NamedTextColor.GREEN : NamedTextColor.RED)
            .append(Component.text(has ? "found" : "missing (placeholder)", NamedTextColor.DARK_GRAY))
            .append(Component.text("  - " + what, NamedTextColor.GRAY));
    }

    private boolean deny(CommandSender s) { s.sendMessage(Component.text("No permission.", NamedTextColor.RED)); return true; }

    private boolean usage(CommandSender s) {
        s.sendMessage(Component.text("Firearms v" + getPluginMeta().getVersion() + (duplicateJars != null ? "  (WARNING: several jars: " + duplicateJars + ")" : ""), NamedTextColor.GOLD));
        s.sendMessage(Component.text("/firearms list | give <gun|mag|ammo|vest> [amount] [player] | models | pack | reload | version", NamedTextColor.YELLOW));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(Stream.of("list", "give", "models", "pack", "reload", "version"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            List<String> ids = new ArrayList<>(registry.gunIds());
            ids.addAll(registry.magIds());
            ids.addAll(registry.ammoIds());
            ids.addAll(registry.vestIds());
            return filter(ids.stream(), args[1]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("give")) return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName), args[3]);
        return List.of();
    }

    private static List<String> filter(Stream<String> options, String prefix) {
        String p = prefix.toLowerCase();
        return options.filter(o -> o.toLowerCase().startsWith(p)).toList();
    }
}
