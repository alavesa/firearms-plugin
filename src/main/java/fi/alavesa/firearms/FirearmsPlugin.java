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

    Registry registry;
    Ballistics ballistics;
    private HoldDetector hold;
    private FireController controller;
    private Casings casings;
    private Grenades grenades;
    String duplicateJars;
    volatile boolean packRunning = false;

    private HitAnimations hitAnimations;
    PackHost packHost;
    DisplayRig rig;
    public DisplayRig rig() { return rig; }
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
        rig = new DisplayRig(this, registry);
        rig.load();
        controller = new FireController(this, registry, ballistics, casings);
        getServer().getPluginManager().registerEvents(controller, this);
        getServer().getScheduler().runTaskTimer(this, casings::tick, 1L, 1L);
        grenades = new Grenades(this, registry);
        getServer().getPluginManager().registerEvents(grenades, this);
        getServer().getScheduler().runTaskTimer(this, grenades::tick, 1L, 1L);
        getServer().getScheduler().runTaskTimer(this, hold::tick, 1L, 1L);
        getServer().getScheduler().runTaskTimer(this, controller::tick, 1L, 1L);
        getServer().getScheduler().runTaskTimer(this, rig::tick, 1L, 1L);
        getServer().getScheduler().runTaskTimer(this, hold::poll, 20L, 5L);
        getServer().getScheduler().runTaskTimer(this, ballistics::tick, 1L, 1L);
        FirearmsCommand cmd = new FirearmsCommand(this);
        getCommand("firearms").setExecutor(cmd);
        getCommand("firearms").setTabCompleter(cmd);
        hitAnimations = new HitAnimations(this);
        if (hitAnimations.available()) {
            try { hitAnimations.sync(); } catch (Throwable t) { getLogger().warning("RP Engine rig sync failed: " + t); }
            getLogger().info("RP Engine found: flinch animations play as emotes from models/players/<rig>.bbmodel (hit-animations in config.yml).");
        }
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
        if (rig != null) for (org.bukkit.entity.Player p : getServer().getOnlinePlayers()) rig.hide(p);
        if (packHost != null) packHost.stop();
        if (controller != null) controller.shutdown();
        if (hold != null) hold.clearAll();
        if (ballistics != null) ballistics.clearAll();
        if (casings != null) casings.clearAll();
        if (grenades != null) grenades.clearAll();
    }
}
