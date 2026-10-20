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
    private Casings casings;
    private Grenades grenades;
    private String duplicateJars;
    private volatile boolean packRunning = false;

    private HitAnimations hitAnimations;
    private PackHost packHost;
    public HitAnimations hitAnimations() { return hitAnimations; }
    public PackHost packHost() { return packHost; }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        new File(getDataFolder(), "models").mkdirs();
        // 0.7: first-person arms are OFF by default (model hands in the .bbmodel). Flip an existing config once.
        if (!getConfig().getBoolean("arms-default-off", false)) {
            getConfig().set("arms.enabled", false);
            getConfig().set("arms-default-off", true);
            saveConfig();
        }
        registry = new Registry(this);
        registry.load();
        ballistics = new Ballistics(this, registry);
        hold = new HoldDetector(this, registry);
        casings = new Casings(this, registry);
        controller = new FireController(this, registry, ballistics, casings);
        getServer().getPluginManager().registerEvents(controller, this);
        getServer().getScheduler().runTaskTimer(this, casings::tick, 1L, 1L);
        grenades = new Grenades(this, registry);
        getServer().getPluginManager().registerEvents(grenades, this);
        getServer().getScheduler().runTaskTimer(this, grenades::tick, 1L, 1L);
        getServer().getScheduler().runTaskTimer(this, hold::tick, 1L, 1L);
        getServer().getScheduler().runTaskTimer(this, hold::poll, 20L, 5L);
        getServer().getScheduler().runTaskTimer(this, ballistics::tick, 1L, 1L);
        hitAnimations = new HitAnimations(this);
        if (hitAnimations.available()) getLogger().info("BetterModel found: flinch animations play on the players' rig (hit-animations in config.yml).");
        packHost = new PackHost(this);
        packHost.start();
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) { hold.forget(e.getPlayer().getUniqueId()); ArmSkin.forget(e.getPlayer().getUniqueId()); }
            @org.bukkit.event.EventHandler
            public void onJoinSkin(org.bukkit.event.player.PlayerJoinEvent e) { ArmSkin.load(FirearmsPlugin.this, e.getPlayer(), () -> controller.refreshArms(e.getPlayer())); }
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
                getServer().getScheduler().runTaskLater(FirearmsPlugin.this, () -> { if (e.getPlayer().isOnline()) packHost.send(e.getPlayer()); }, 40L);
                if (duplicateJars != null && e.getPlayer().hasPermission("firearms.admin"))
                    e.getPlayer().sendMessage(Component.text("[Firearms] Several Firearms jars in plugins/ (" + duplicateJars + ") - delete the old one and restart, or updates do nothing.", NamedTextColor.RED));
            }
        }, this);
        getLogger().info("Firearms enabled - guns: " + registry.gunIds() + ", mags: " + registry.magIds() + ", ammo: " + registry.ammoIds()
            + ". Drop .bbmodel files into plugins/Firearms/models and run /firearms pack.");
    }

    @Override
    public void onDisable() {
        if (packHost != null) packHost.stop();
        if (controller != null) controller.shutdown();
        if (hold != null) hold.clearAll();
        if (ballistics != null) ballistics.clearAll();
        if (casings != null) casings.clearAll();
        if (grenades != null) grenades.clearAll();
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
                sender.sendMessage(Component.text("Grenades: " + String.join(", ", registry.grenadeIds()), NamedTextColor.RED));
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
                GrenadeType gr = registry.grenade(id);
                if (gr != null) { int left = amount; while (left > 0) { int n = Math.min(left, 16); items.add(registry.buildGrenade(gr, n)); left -= n; } }
                else if (v != null) for (int i = 0; i < amount; i++) items.add(registry.buildVest(v));
                else if (g != null) for (int i = 0; i < amount; i++) { ItemStack gi = registry.buildGun(g); registry.applySkin(gi, g, target); items.add(gi); }
                else if (m != null) for (int i = 0; i < amount; i++) items.add(registry.buildMag(m, m.capacity()));
                else if (a != null) {
                    int left = amount;
                    while (left > 0) { int n = Math.min(left, a.stack()); items.add(registry.buildAmmo(a, n)); left -= n; }
                } else { sender.sendMessage(Component.text("Unknown id: " + id, NamedTextColor.RED)); return true; }
                for (ItemStack it : items) target.getInventory().addItem(it).values().forEach(l -> target.getWorld().dropItemNaturally(target.getLocation(), l));
                sender.sendMessage(Component.text("Gave " + amount + " x " + id + " to " + target.getName(), NamedTextColor.GREEN));
                return true;
            }
            case "pack", "check" -> {
                if (!sender.hasPermission("firearms.admin")) return deny(sender);
                buildPack(sender, args[0].equalsIgnoreCase("check"));
                return true;
            }
            case "pose", "offset", "position" -> {
                if (!sender.hasPermission("firearms.admin")) return deny(sender);
                return pose(sender, args);
            }
            case "models" -> {
                File dir = new File(getDataFolder(), "models");
                sender.sendMessage(Component.text("Model files expected in " + dir.getPath() + ":", NamedTextColor.GOLD));
                for (GunType g : registry.guns()) sender.sendMessage(line(dir, g.model(), "gun " + g.id()));
                for (MagType m : registry.mags()) sender.sendMessage(line(dir, m.model(), "mag " + m.id()));
                for (AmmoType a : registry.ammos()) sender.sendMessage(line(dir, a.model(), "ammo " + a.id()));
                for (ArmorType v : registry.vests()) sender.sendMessage(line(dir, v.model(), "vest " + v.id() + " (or " + v.model() + ".png icon)"));
                for (GrenadeType gr : registry.grenades()) sender.sendMessage(line(dir, gr.model(), "grenade " + gr.id() + " (clips: unpin, throw)"));
                for (GunType g : registry.guns()) {
                    String c = registry.casingModel(g);
                    if (c != null) sender.sendMessage(line(dir, c, "casing of " + g.id() + (c.equals(g.model() + "_casing") ? " (auto-detected)" : " - or add " + g.model() + "_casing.bbmodel")));
                }
                sender.sendMessage(line(dir, getConfig().getString("craters.model", "crater"), "crater (or crater.png)"));
                return true;
            }
            case "clips" -> {
                if (args.length < 2) { sender.sendMessage(Component.text("/firearms clips <model>", NamedTextColor.YELLOW)); return true; }
                String model = args[1];
                sender.sendMessage(Component.text("Clips the plugin knows for '" + model + "' (from models/anim-index.yml):", NamedTextColor.GOLD));
                boolean any = false;
                for (String clip : new String[]{"fire", "reload", "equip", "pump", "inspect", "unpin", "throw"}) {
                    int[] a = registry.anim(model, clip);
                    if (a == null) continue;
                    any = true;
                    sender.sendMessage(Component.text("  " + clip + ": " + a[0] + " frames x " + a[1] + " ticks" + (registry.animSounds(model, clip).isEmpty() ? "" : "  sounds " + registry.animSounds(model, clip)), NamedTextColor.GRAY));
                }
                if (!any) sender.sendMessage(Component.text("  none - run /firearms pack and check its 'clips:' lines; the animation names must match anim.names", NamedTextColor.YELLOW));
                return true;
            }
            case "host", "packhost" -> { sender.sendMessage(Component.text(packHost.status(), NamedTextColor.GOLD)); if (sender instanceof Player pl) { sender.sendMessage(Component.text("URL for you: " + packHost.urlFor(pl) + "  sha1 " + packHost.sha1(), NamedTextColor.GRAY)); packHost.send(pl); sender.sendMessage(Component.text("(pack re-sent to you)", NamedTextColor.GRAY)); } return true; }
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


    /** /firearms pack (check = dry run), in the background. */
    private void buildPack(CommandSender sender, boolean dry) {
                if (packRunning) { sender.sendMessage(Component.text("A pack build is already running.", NamedTextColor.YELLOW)); return; }
                packRunning = true;
                sender.sendMessage(Component.text(dry ? "Checking models..." : "Building the pack in the background (the server keeps running)...", NamedTextColor.GRAY));
                // Off the main thread: big models x many animation frames took long enough to freeze the server and
                // time players out. The generator only reads immutable gun data + config, so this is safe.
                getServer().getScheduler().runTaskAsynchronously(this, () -> {
                    PackGenerator.Result r = null; Exception err = null;
                    try { r = new PackGenerator(this, registry, dry).generate(); } catch (Exception ex) { err = ex; }
                    if (!dry && err == null) packHost.prepareAsync();   // hash + (upload mode) upload, still off the main thread
                    final PackGenerator.Result res = r; final Exception e = err;
                    getServer().getScheduler().runTask(this, () -> {
                        packRunning = false;
                        if (e != null) {
                            sender.sendMessage(Component.text("Pack generation failed: " + e, NamedTextColor.RED));
                            getLogger().warning("Pack generation failed: " + e);
                            return;
                        }
                        if (!dry) { registry.load(); packHost.sendAll(); }
                        sender.sendMessage(Component.text((dry ? "Check done" : "Pack written: " + res.zip().getPath()) + "  (" + res.millis() + " ms, " + (res.bytes() / 1024) + " KB uncompressed)", NamedTextColor.GREEN));
                        sender.sendMessage(Component.text(res.models() + " .bbmodel converted, " + res.frames() + " animation frames baked, "
                            + res.placeholders() + " placeholder model(s)", NamedTextColor.GRAY));
                        if (!res.biggest().isEmpty()) sender.sendMessage(Component.text("Biggest: " + String.join(", ", res.biggest()), NamedTextColor.DARK_GRAY));
                        for (String nte : res.notes()) sender.sendMessage(Component.text(nte, NamedTextColor.GRAY));
                        for (String w : res.warnings()) sender.sendMessage(Component.text("! " + w, NamedTextColor.YELLOW));
                        if (!dry) {
                            String stt = packHost.status();
                            boolean sent = packHost.enabled() && !stt.contains("PROBLEM") && !stt.contains("not running") && !stt.contains("disabled");
                            sender.sendMessage(Component.text(sent ? "The new pack was sent to every online player; newcomers get it on join. " + stt
                                : stt + " Until that is fixed, apply the NEW Firearms-pack.zip manually - an old pack with this new index shows purple frames while firing.", sent ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
                        }
                    });
                });
    }

    /** /firearms pose <gun> [offset|rotate|scale [x y z]] - first-person position of the gun model, saved to guns.yml
     *  display.firstperson_righthand (+ lefthand), then the pack is rebuilt. "~" keeps a value, "~2" adds 2. */
    private boolean pose(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage(Component.text("/firearms pose <gun> offset <x> <y> <z> | rotate <x> <y> <z> | scale <s>   (model px / degrees; ~ = keep, ~2 = add 2)", NamedTextColor.YELLOW)); return true; }
        GunType g = registry.gun(args[1].toLowerCase());
        if (g == null) { sender.sendMessage(Component.text("Unknown gun: " + args[1] + " (" + String.join(", ", registry.gunIds()) + ")", NamedTextColor.RED)); return true; }
        File gf = new File(getDataFolder(), "guns.yml");
        org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(gf);
        String base = "guns." + g.id() + ".display.firstperson_righthand.";
        double[] tr = vec3(y, base + "translation", new double[]{0, 0, 0}), rot = vec3(y, base + "rotation", new double[]{0, 0, 0}), sc = vec3(y, base + "scale", new double[]{1, 1, 1});
        if (args.length < 3) {
            sender.sendMessage(Component.text(g.id() + " first person: offset " + fmt(tr) + "  rotate " + fmt(rot) + "  scale " + fmt(sc)
                + (y.contains(base + "translation") || y.contains(base + "rotation") || y.contains(base + "scale") ? "" : "  (not set: the .bbmodel's own Display values are used)"), NamedTextColor.GOLD));
            return true;
        }
        String what = args[2].toLowerCase();
        try {
            switch (what) {
                case "offset", "position", "translation", "move" -> { if (args.length < 6) return poseUsage(sender); for (int i = 0; i < 3; i++) tr[i] = Math.max(-80, Math.min(80, rel(args[3 + i], tr[i]))); y.set(base + "translation", list(tr)); }
                case "rotate", "rotation", "rot" -> { if (args.length < 6) return poseUsage(sender); for (int i = 0; i < 3; i++) rot[i] = rel(args[3 + i], rot[i]); y.set(base + "rotation", list(rot)); }
                case "scale", "size" -> { if (args.length < 4) return poseUsage(sender); double v = Math.max(0.05, Math.min(4, rel(args[3], sc[0]))); sc = new double[]{v, v, v}; y.set(base + "scale", list(sc)); }
                case "reset", "clear" -> { y.set("guns." + g.id() + ".display", null); }
                default -> { return poseUsage(sender); }
            }
        } catch (NumberFormatException ex) { sender.sendMessage(Component.text("Numbers please: " + ex.getMessage(), NamedTextColor.RED)); return true; }
        if (!what.equals("reset") && !what.equals("clear")) {
            // the left hand is the mirror image: same values
            for (String k : new String[]{"translation", "rotation", "scale"}) if (y.contains(base + k)) y.set("guns." + g.id() + ".display.firstperson_lefthand." + k, y.get(base + k));
        }
        try { y.save(gf); } catch (java.io.IOException ex) { sender.sendMessage(Component.text("Could not write guns.yml: " + ex.getMessage(), NamedTextColor.RED)); return true; }
        registry.load();
        sender.sendMessage(Component.text(g.id() + " first person: offset " + fmt(tr) + "  rotate " + fmt(rot) + "  scale " + fmt(sc) + "  -> saved to guns.yml", NamedTextColor.GREEN));
        buildPack(sender, false);
        return true;
    }

    private boolean poseUsage(CommandSender s) { s.sendMessage(Component.text("/firearms pose <gun> offset <x> <y> <z> | rotate <x> <y> <z> | scale <s> | reset", NamedTextColor.YELLOW)); return true; }
    private static double rel(String a, double cur) {
        if (a.equals("~")) return cur;
        if (a.startsWith("~")) return cur + Double.parseDouble(a.substring(1).replace(',', '.'));
        return Double.parseDouble(a.replace(',', '.'));
    }
    private static double[] vec3(org.bukkit.configuration.ConfigurationSection y, String path, double[] def) {
        List<Double> l = y.getDoubleList(path);
        return l.size() == 3 ? new double[]{l.get(0), l.get(1), l.get(2)} : def.clone();
    }
    private static List<Double> list(double[] v) { return List.of(Math.round(v[0] * 100) / 100.0, Math.round(v[1] * 100) / 100.0, Math.round(v[2] * 100) / 100.0); }
    private static String fmt(double[] v) { return "[" + Math.round(v[0] * 100) / 100.0 + ", " + Math.round(v[1] * 100) / 100.0 + ", " + Math.round(v[2] * 100) / 100.0 + "]"; }

    private Component line(File dir, String model, String what) {
        boolean has = new File(dir, model + ".bbmodel").exists();
        return Component.text("  " + model + ".bbmodel ", has ? NamedTextColor.GREEN : NamedTextColor.RED)
            .append(Component.text(has ? "found" : "missing (placeholder)", NamedTextColor.DARK_GRAY))
            .append(Component.text("  - " + what, NamedTextColor.GRAY));
    }

    private boolean deny(CommandSender s) { s.sendMessage(Component.text("No permission.", NamedTextColor.RED)); return true; }

    private boolean usage(CommandSender s) {
        s.sendMessage(Component.text("Firearms v" + getPluginMeta().getVersion() + (duplicateJars != null ? "  (WARNING: several jars: " + duplicateJars + ")" : ""), NamedTextColor.GOLD));
        s.sendMessage(Component.text("/firearms list | give <gun|mag|ammo|vest|grenade> [amount] [player] | pose <gun> offset|rotate|scale ... | models | pack | check | host | clips <model> | reload | version", NamedTextColor.YELLOW));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(Stream.of("list", "give", "pose", "models", "pack", "check", "host", "clips", "reload", "version"), args[0]);
        if (args[0].equalsIgnoreCase("pose")) {
            if (args.length == 2) return filter(registry.gunIds().stream(), args[1]);
            if (args.length == 3) return filter(Stream.of("offset", "rotate", "scale", "reset"), args[2]);
            if (args.length >= 4 && args.length <= 6) return List.of("~", "0");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            List<String> ids = new ArrayList<>(registry.gunIds());
            ids.addAll(registry.magIds());
            ids.addAll(registry.ammoIds());
            ids.addAll(registry.vestIds());
            ids.addAll(registry.grenadeIds());
            return filter(ids.stream(), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("clips")) return filter(registry.guns().stream().map(GunType::model), args[1]);
        if (args.length == 4 && args[0].equalsIgnoreCase("give")) return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName), args[3]);
        return List.of();
    }

    private static List<String> filter(Stream<String> options, String prefix) {
        String p = prefix.toLowerCase();
        return options.filter(o -> o.toLowerCase().startsWith(p)).toList();
    }
}
