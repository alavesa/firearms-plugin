package fi.alavesa.firearms;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** /firearms ... - every command, tab completion, the pack build and the pose editor. */
public final class FirearmsCommand implements CommandExecutor, TabCompleter {
    private final FirearmsPlugin plugin;

    public FirearmsCommand(FirearmsPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) return usage(sender);
        switch (args[0].toLowerCase()) {
            case "list" -> {
                sender.sendMessage(Component.text("Guns: " + String.join(", ", plugin.registry.gunIds()), NamedTextColor.GOLD));
                sender.sendMessage(Component.text("Mags: " + String.join(", ", plugin.registry.magIds()), NamedTextColor.YELLOW));
                sender.sendMessage(Component.text("Ammo: " + String.join(", ", plugin.registry.ammoIds()), NamedTextColor.GRAY));
                sender.sendMessage(Component.text("Vests: " + String.join(", ", plugin.registry.vestIds()), NamedTextColor.AQUA));
                sender.sendMessage(Component.text("Grenades: " + String.join(", ", plugin.registry.grenadeIds()), NamedTextColor.RED));
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
                GunType g = plugin.registry.gun(id);
                MagType m = plugin.registry.mag(id);
                AmmoType a = plugin.registry.ammo(id);
                ArmorType v = plugin.registry.vest(id);
                GrenadeType gr = plugin.registry.grenade(id);
                if (gr != null) { int left = amount; while (left > 0) { int n = Math.min(left, 16); items.add(plugin.registry.buildGrenade(gr, n)); left -= n; } }
                else if (v != null) for (int i = 0; i < amount; i++) items.add(plugin.registry.buildVest(v));
                else if (g != null) for (int i = 0; i < amount; i++) { ItemStack gi = plugin.registry.buildGun(g); plugin.registry.applySkin(gi, g, target); items.add(gi); }
                else if (m != null) for (int i = 0; i < amount; i++) items.add(plugin.registry.buildMag(m, m.capacity()));
                else if (a != null) {
                    int left = amount;
                    while (left > 0) { int n = Math.min(left, a.stack()); items.add(plugin.registry.buildAmmo(a, n)); left -= n; }
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
            case "aimpose", "aimmodel" -> {   // /firearms aimpose <gun> <right> <up> <forward> - the shared aim placement, like /viewmodel but for everyone
                if (!sender.hasPermission("firearms.admin")) return deny(sender);
                if (args.length < 2) { sender.sendMessage(Component.text("/firearms aimpose <gun> <right> <up> <forward> | reset | show   (sixteenths of a block, -80..80; everyone's aim state of that gun, applied live)", NamedTextColor.YELLOW)); return true; }
                String[] a2;
                if (args.length >= 5) a2 = new String[]{"pose", args[1], "aim", "offset", args[2], args[3], args[4]};
                else if (args.length >= 3 && args[2].equalsIgnoreCase("reset")) a2 = new String[]{"pose", args[1], "aim", "reset"};
                else a2 = new String[]{"pose", args[1], "aim"};
                return pose(sender, a2);
            }
            case "models" -> {
                File dir = new File(plugin.getDataFolder(), "models");
                sender.sendMessage(Component.text("Model files expected in " + dir.getPath() + ":", NamedTextColor.GOLD));
                for (GunType g : plugin.registry.guns()) sender.sendMessage(line(dir, g.model(), "gun " + g.id()));
                for (MagType m : plugin.registry.mags()) sender.sendMessage(line(dir, m.model(), "mag " + m.id()));
                for (AmmoType a : plugin.registry.ammos()) sender.sendMessage(line(dir, a.model(), "ammo " + a.id()));
                for (ArmorType v : plugin.registry.vests()) sender.sendMessage(line(dir, v.model(), "vest " + v.id() + " (or " + v.model() + ".png icon)"));
                for (GrenadeType gr : plugin.registry.grenades()) sender.sendMessage(line(dir, gr.model(), "grenade " + gr.id() + " (clips: unpin, throw)"));
                for (GunType g : plugin.registry.guns()) {
                    String c = plugin.registry.casingModel(g);
                    if (c != null) sender.sendMessage(line(dir, c, "casing of " + g.id() + (c.equals(g.model() + "_casing") ? " (auto-detected)" : " - or add " + g.model() + "_casing.bbmodel")));
                }
                sender.sendMessage(line(dir, plugin.getConfig().getString("craters.model", "crater"), "crater (or crater.png)"));
                return true;
            }
            case "mode", "firemode" -> {
                if (!(sender instanceof Player pl)) { sender.sendMessage("Players only."); return true; }
                ItemStack held = pl.getInventory().getItemInMainHand();
                GunType g = plugin.registry.gunOf(held);
                if (g == null) { sender.sendMessage(Component.text("Hold a gun.", NamedTextColor.RED)); return true; }
                String nm = plugin.registry.toggleMode(held, g);
                sender.sendMessage(Component.text(nm == null ? g.id() + " has one fire mode only (guns.yml fire-mode: \"semi,auto\" makes it switchable)." : g.id() + " is now " + nm.toUpperCase() + ".", nm == null ? NamedTextColor.YELLOW : NamedTextColor.GREEN));
                return true;
            }
            case "clips" -> {
                if (args.length < 2) { sender.sendMessage(Component.text("/firearms clips <model>", NamedTextColor.YELLOW)); return true; }
                String model = args[1];
                sender.sendMessage(Component.text("Clips the plugin knows for '" + model + "' (from models/anim-index.yml):", NamedTextColor.GOLD));
                boolean any = false;
                List<String> keys = new ArrayList<>(plugin.registry.clipKeys(model));
                for (String clip : new String[]{"fire", "reload", "equip", "pump", "inspect", "aim", "unpin", "throw"}) if (!keys.contains(clip)) keys.add(clip);
                for (String clip : keys) {
                    int[] a = plugin.registry.anim(model, clip);
                    if (a == null) continue;
                    any = true;
                    sender.sendMessage(Component.text("  " + clip + ": " + a[0] + " frames over " + plugin.registry.clipTicks(model, clip) + " ticks" + (plugin.registry.animSounds(model, clip).isEmpty() ? "" : "  sounds " + plugin.registry.animSounds(model, clip)), NamedTextColor.GRAY));
                }
                if (!any) sender.sendMessage(Component.text("  none - run /firearms pack and check its 'clips:' lines; the animation names must match anim.names", NamedTextColor.YELLOW));
                return true;
            }
            case "rig", "viewmodel", "diag" -> {
                if (!(sender instanceof Player pl)) { sender.sendMessage("Players only."); return true; }
                ItemStack held = pl.getInventory().getItemInMainHand();
                GunType g = plugin.registry.gunOf(held);
                sender.sendMessage(Component.text("Display rig diagnostics (why /viewmodel does or does not move the gun):", NamedTextColor.GOLD));
                sender.sendMessage(Component.text("1. server can build rigs: " + (NmsDisplay.available() ? "YES" : "NO - " + NmsDisplay.problem()), NmsDisplay.available() ? NamedTextColor.GREEN : NamedTextColor.RED));
                sender.sendMessage(Component.text("2. poses loaded (models/poses.bin from /firearms pack): " + plugin.rig().loadedModels(), plugin.rig().loadedModels().isEmpty() ? NamedTextColor.RED : NamedTextColor.GREEN));
                if (g == null) sender.sendMessage(Component.text("3. held item is not a gun - hold one and run this again", NamedTextColor.YELLOW));
                else {
                    boolean dm = plugin.registry.displayMode(g.model());
                    sender.sendMessage(Component.text("3. " + g.id() + " (model " + g.model() + "): " + (dm ? "display mode" : "FRAME mode - baked into the pack, /viewmodel cannot move it; anim.mode: display + /firearms pack"), dm ? NamedTextColor.GREEN : NamedTextColor.RED));
                    sender.sendMessage(Component.text("4. rig showing for you: " + (plugin.rig().showing(pl) ? "YES, " + plugin.rig().partCount(pl) + " part(s) - if you still see no gun, your client has not applied the pack (see 5)" : "NO" + (dm && plugin.rig().has(g.model()) ? " (re-select the hotbar slot)" : "")), plugin.rig().showing(pl) ? NamedTextColor.GREEN : NamedTextColor.RED));
                }
                Viewmodels.Setting vm = plugin.viewmodels().of(pl);
                sender.sendMessage(Component.text("5. pack: " + plugin.packHost.status() + " | your URL " + plugin.packHost.urlFor(pl), NamedTextColor.GRAY));
                sender.sendMessage(Component.text("6. your /viewmodel offset: " + vm.x + " " + vm.y + " " + vm.z + (vm.isDefault() ? " (stock)" : ""), NamedTextColor.GRAY));
                sender.sendMessage(Component.text("docs/VIEWMODEL.md explains each line.", NamedTextColor.DARK_GRAY));
                return true;
            }
            case "aim" -> {   // /firearms aim <player> on|off - hold/release the sight for testing from the console
                if (!sender.hasPermission("firearms.admin")) return deny(sender);
                Player t = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player pl ? pl : null;
                if (t == null) { sender.sendMessage(Component.text("/firearms aim <player> on|off", NamedTextColor.YELLOW)); return true; }
                boolean on = args.length < 3 || !args[2].equalsIgnoreCase("off");
                plugin.controller().forceAim(t, on);
                sender.sendMessage(Component.text(t.getName() + " aim " + (on ? "held" : "released"), NamedTextColor.GREEN));
                return true;
            }
            case "fire" -> {   // /firearms fire <player> - one shot from the console (testing)
                if (!sender.hasPermission("firearms.admin")) return deny(sender);
                Player t = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player pl ? pl : null;
                if (t == null) { sender.sendMessage(Component.text("/firearms fire <player>", NamedTextColor.YELLOW)); return true; }
                plugin.controller().forceFire(t);
                return true;
            }
            case "host", "packhost" -> { sender.sendMessage(Component.text(plugin.packHost.status(), NamedTextColor.GOLD)); if (sender instanceof Player pl) { sender.sendMessage(Component.text("URL for you: " + plugin.packHost.urlFor(pl) + "  sha1 " + plugin.packHost.sha1(), NamedTextColor.GRAY)); plugin.packHost.send(pl); sender.sendMessage(Component.text("(pack re-sent to you)", NamedTextColor.GRAY)); } return true; }
            case "version" -> { return usage(sender); }
            case "reload" -> {
                if (!sender.hasPermission("firearms.admin")) return deny(sender);
                plugin.reloadConfig();
                plugin.registry.load(); plugin.rig.load();
                plugin.ballistics.load();
                sender.sendMessage(Component.text("Firearms reloaded.", NamedTextColor.GREEN));
                return true;
            }
            default -> { return usage(sender); }
        }
    }


    /** /firearms pack (check = dry run), in the background. */
    private void buildPack(CommandSender sender, boolean dry) {
                if (plugin.packRunning) { sender.sendMessage(Component.text("A pack build is already running.", NamedTextColor.YELLOW)); return; }
                plugin.packRunning = true;
                sender.sendMessage(Component.text(dry ? "Checking models..." : "Building the pack in the background (the server keeps running)...", NamedTextColor.GRAY));
                // Off the main thread: big models x many animation frames took long enough to freeze the server and
                // time players out. The generator only reads immutable gun data + config, so this is safe.
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    PackGenerator.Result r = null; Throwable err = null;
                    // Throwable, not Exception: an OutOfMemoryError from a huge model used to kill this task silently and
                    // leave packRunning set, so every later /firearms pack was refused as "already running".
                    try { r = new PackGenerator(plugin, plugin.registry, dry).generate(); } catch (Throwable ex) { err = ex; plugin.getLogger().log(java.util.logging.Level.SEVERE, "Pack generation failed", ex); }
                    if (!dry && err == null) plugin.packHost.prepareAsync();   // hash + (upload mode) upload, still off the main thread
                    final PackGenerator.Result res = r; final Throwable e = err;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        plugin.packRunning = false;
                        if (e != null) {
                            sender.sendMessage(Component.text("Pack generation failed: " + e, NamedTextColor.RED));
                            plugin.getLogger().warning("Pack generation failed: " + e);
                            return;
                        }
                        if (!dry) { plugin.registry.load(); plugin.rig.load(); plugin.packHost.sendAll(); }
                        sender.sendMessage(Component.text((dry ? "Check done" : "Pack written: " + res.zip().getPath()) + "  (" + res.millis() + " ms, " + (res.bytes() / 1024) + " KB uncompressed)", NamedTextColor.GREEN));
                        sender.sendMessage(Component.text(res.models() + " .bbmodel converted, " + res.frames() + " animation frames baked, "
                            + res.placeholders() + " placeholder model(s)", NamedTextColor.GRAY));
                        if (!res.biggest().isEmpty()) sender.sendMessage(Component.text("Biggest: " + String.join(", ", res.biggest()), NamedTextColor.DARK_GRAY));
                        for (String nte : res.notes()) sender.sendMessage(Component.text(nte, NamedTextColor.GRAY));
                        for (String w : res.warnings()) sender.sendMessage(Component.text("! " + w, NamedTextColor.YELLOW));
                        if (!dry) {
                            String stt = plugin.packHost.status();
                            boolean sent = plugin.packHost.enabled() && !stt.contains("PROBLEM") && !stt.contains("not running") && !stt.contains("disabled");
                            sender.sendMessage(Component.text(sent ? "The new pack was sent to every online player; newcomers get it on join. " + stt
                                : stt + " Until that is fixed, apply the NEW Firearms-pack.zip manually - an old pack with this new index shows purple frames while firing.", sent ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
                        }
                    });
                });
    }

    /** /firearms pose <gun> [offset|rotate|scale [x y z]] - first-person position of the gun model, saved to guns.yml
     *  display.firstperson_righthand (+ lefthand), then the pack is rebuilt. "~" keeps a value, "~2" adds 2. */
    private static final java.util.Map<String, String[]> POSE_CTX = new java.util.LinkedHashMap<>();
    static {
        POSE_CTX.put("first", new String[]{"firstperson_righthand", "firstperson_lefthand"});
        POSE_CTX.put("third", new String[]{"thirdperson_righthand", "thirdperson_lefthand"});
        POSE_CTX.put("gui", new String[]{"gui"}); POSE_CTX.put("ground", new String[]{"ground"}); POSE_CTX.put("fixed", new String[]{"fixed"}); POSE_CTX.put("head", new String[]{"head"});
    }

    /** /firearms pose <gun> [first|third|gui|ground|fixed|head] [offset|rotate|scale <...>|reset] - the gun model's display
     *  transform per display state, saved to guns.yml display.<context>, then the pack is rebuilt and re-sent. */
    private boolean pose(CommandSender sender, String[] args) {
        if (args.length < 2) return poseUsage(sender);
        GunType g = plugin.registry.gun(args[1].toLowerCase());
        if (g == null) { sender.sendMessage(Component.text("Unknown gun: " + args[1] + " (" + String.join(", ", plugin.registry.gunIds()) + ")", NamedTextColor.RED)); return true; }
        File gf = new File(plugin.getDataFolder(), "guns.yml");
        org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(gf);
        if (args.length > 2 && (args[2].equalsIgnoreCase("aim") || args[2].equalsIgnoreCase("ads"))) return aimPose(sender, g, y, gf, args);
        int ai = 2;
        String ctx = "first";
        if (args.length > 2 && POSE_CTX.containsKey(args[2].toLowerCase())) { ctx = args[2].toLowerCase(); ai = 3; }
        String[] keys = POSE_CTX.get(ctx);
        String base = "guns." + g.id() + ".display." + keys[0] + ".";
        double[] tr = vec3(y, base + "translation", new double[]{0, 0, 0}), rot = vec3(y, base + "rotation", new double[]{0, 0, 0}), sc = vec3(y, base + "scale", new double[]{1, 1, 1});
        if (args.length <= ai) {
            sender.sendMessage(Component.text(g.id() + " display states (guns.yml; unset = the .bbmodel's own Display values):", NamedTextColor.GOLD));
            for (var en : POSE_CTX.entrySet()) {
                String b = "guns." + g.id() + ".display." + en.getValue()[0] + ".";
                boolean set = y.contains(b + "translation") || y.contains(b + "rotation") || y.contains(b + "scale");
                sender.sendMessage(Component.text("  " + en.getKey() + ": " + (set ? "offset " + fmt(vec3(y, b + "translation", new double[]{0, 0, 0})) + "  rotate " + fmt(vec3(y, b + "rotation", new double[]{0, 0, 0})) + "  scale " + fmt(vec3(y, b + "scale", new double[]{1, 1, 1})) : "(model's own)"), set ? NamedTextColor.YELLOW : NamedTextColor.GRAY));
            }
            return true;
        }
        String what = args[ai].toLowerCase();
        try {
            switch (what) {
                case "offset", "position", "translation", "move" -> { if (args.length < ai + 4) return poseUsage(sender); for (int i = 0; i < 3; i++) tr[i] = Math.max(-80, Math.min(80, rel(args[ai + 1 + i], tr[i]))); for (String k : keys) y.set("guns." + g.id() + ".display." + k + ".translation", list(tr)); }
                case "rotate", "rotation", "rot" -> { if (args.length < ai + 4) return poseUsage(sender); for (int i = 0; i < 3; i++) rot[i] = rel(args[ai + 1 + i], rot[i]); for (String k : keys) y.set("guns." + g.id() + ".display." + k + ".rotation", list(rot)); }
                case "scale", "size" -> { if (args.length < ai + 2) return poseUsage(sender); double v = Math.max(0.05, Math.min(4, rel(args[ai + 1], sc[0]))); sc = new double[]{v, v, v}; for (String k : keys) y.set("guns." + g.id() + ".display." + k + ".scale", list(sc)); }
                case "reset", "clear" -> { for (String k : keys) y.set("guns." + g.id() + ".display." + k, null); if (y.getConfigurationSection("guns." + g.id() + ".display") != null && y.getConfigurationSection("guns." + g.id() + ".display").getKeys(false).isEmpty()) y.set("guns." + g.id() + ".display", null); }
                default -> { return poseUsage(sender); }
            }
        } catch (NumberFormatException ex) { sender.sendMessage(Component.text("Numbers please: " + ex.getMessage(), NamedTextColor.RED)); return true; }
        try { y.save(gf); } catch (java.io.IOException ex) { sender.sendMessage(Component.text("Could not write guns.yml: " + ex.getMessage(), NamedTextColor.RED)); return true; }
        plugin.registry.load(); plugin.rig.load();
        sender.sendMessage(Component.text(g.id() + " " + ctx + ": offset " + fmt(tr) + "  rotate " + fmt(rot) + "  scale " + fmt(sc) + "  -> saved to guns.yml (display." + String.join(" + ", keys) + "); rebuilding the pack", NamedTextColor.GREEN));
        buildPack(sender, false);
        return true;
    }

    /** /firearms pose <gun> aim offset|rotate|scale|reset: the gun's fixed aim state A on the display rig (guns.yml aim-pose),
     *  applied to every rig at once - no pack rebuild, the client blends from the hip pose to it. */
    private boolean aimPose(CommandSender sender, GunType g, org.bukkit.configuration.file.YamlConfiguration y, File gf, String[] args) {
        String base = "guns." + g.id() + ".aim-pose.";
        double[] off = vec3(y, base + "offset", new double[]{0, 0, 0}), rot = vec3(y, base + "rotation", new double[]{0, 0, 0});
        double sc = y.getDouble(base + "scale", 1.0);
        if (args.length <= 3) {
            sender.sendMessage(Component.text(g.id() + " aim state (guns.yml aim-pose, on top of the model's aim clip): offset " + fmt(off) + " (sixteenths of a block: right, up, forward)  rotate " + fmt(rot) + "  scale " + sc, NamedTextColor.GOLD));
            sender.sendMessage(Component.text("/firearms pose " + g.id() + " aim offset <r> <u> <f> | rotate <x> <y> <z> | scale <s> | reset   (~ = keep, ~2 = add 2; applied live)", NamedTextColor.YELLOW));
            return true;
        }
        try {
            switch (args[3].toLowerCase()) {
                case "offset", "position", "move" -> { if (args.length < 7) return poseUsage(sender); for (int i = 0; i < 3; i++) off[i] = Math.max(-80, Math.min(80, rel(args[4 + i], off[i]))); y.set(base + "offset", list(off)); }
                case "rotate", "rotation", "rot" -> { if (args.length < 7) return poseUsage(sender); for (int i = 0; i < 3; i++) rot[i] = rel(args[4 + i], rot[i]); y.set(base + "rotation", list(rot)); }
                case "scale", "size" -> { if (args.length < 5) return poseUsage(sender); sc = Math.max(0.05, Math.min(4, rel(args[4], sc))); y.set(base + "scale", Math.round(sc * 1000) / 1000.0); }
                case "reset", "clear" -> { y.set("guns." + g.id() + ".aim-pose", null); off = new double[3]; rot = new double[3]; sc = 1; }
                default -> { return poseUsage(sender); }
            }
        } catch (NumberFormatException ex) { sender.sendMessage(Component.text("Numbers please: " + ex.getMessage(), NamedTextColor.RED)); return true; }
        try { y.save(gf); } catch (java.io.IOException ex) { sender.sendMessage(Component.text("Could not write guns.yml: " + ex.getMessage(), NamedTextColor.RED)); return true; }
        plugin.registry.load();
        plugin.rig().refreshAll();
        sender.sendMessage(Component.text(g.id() + " aim: offset " + fmt(off) + "  rotate " + fmt(rot) + "  scale " + sc + "  -> saved (aim-pose), applied to every rig now; hold right-click to see it", NamedTextColor.GREEN));
        return true;
    }

    private boolean poseUsage(CommandSender s) { s.sendMessage(Component.text("/firearms pose <gun> [first|third|gui|ground|fixed|head|aim] offset <x> <y> <z> | rotate <x> <y> <z> | scale <s> | reset   (model px / degrees; ~ = keep, ~2 = add 2; no state = first; aim = the rig's aim state, live)", NamedTextColor.YELLOW)); return true; }
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
        s.sendMessage(Component.text("Firearms v" + plugin.getPluginMeta().getVersion() + (plugin.duplicateJars != null ? "  (WARNING: several jars: " + plugin.duplicateJars + ")" : ""), NamedTextColor.GOLD));
        s.sendMessage(Component.text("/firearms list | give <gun|mag|ammo|vest|grenade> [amount] [player] | pose <gun> offset|rotate|scale ... | models | pack | check | host | clips <model> | reload | version", NamedTextColor.YELLOW));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(Stream.of("list", "give", "pose", "models", "pack", "check", "host", "clips", "reload", "version"), args[0]);
        if (args[0].equalsIgnoreCase("pose")) {
            if (args.length == 2) return filter(plugin.registry.gunIds().stream(), args[1]);
            if (args.length == 3) return filter(Stream.of("first", "third", "gui", "ground", "fixed", "head", "offset", "rotate", "scale", "reset"), args[2]);
            if (args.length == 4 && POSE_CTX.containsKey(args[2].toLowerCase())) return filter(Stream.of("offset", "rotate", "scale", "reset"), args[3]);
            if (args.length >= 4 && args.length <= 7) return List.of("~", "0");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            List<String> ids = new ArrayList<>(plugin.registry.gunIds());
            ids.addAll(plugin.registry.magIds());
            ids.addAll(plugin.registry.ammoIds());
            ids.addAll(plugin.registry.vestIds());
            ids.addAll(plugin.registry.grenadeIds());
            return filter(ids.stream(), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("clips")) return filter(plugin.registry.guns().stream().map(GunType::model), args[1]);
        if (args.length == 4 && args[0].equalsIgnoreCase("give")) return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName), args[3]);
        return List.of();
    }

    private static List<String> filter(Stream<String> options, String prefix) {
        String p = prefix.toLowerCase();
        return options.filter(o -> o.toLowerCase().startsWith(p)).toList();
    }
}
