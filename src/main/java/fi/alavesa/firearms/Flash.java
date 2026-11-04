package fi.alavesa.firearms;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flashbang: everyone in radius with a line of sight gets their screen washed white, strongest when near and facing
 * it, then it fades. The white is a post effect from the pack (firearms:flash_1..flash_N, flash.fsh; N = whitest),
 * switched per tick as the flash fades - the 26.3 post-effect list, nothing drawn in the world. Servers without post
 * effects fall back to Blindness (dark instead of white) for the same time.
 */
final class Flash {
    private static final class Blind { BukkitTask task; int level = -1; int stamp; }

    private final FirearmsPlugin plugin;
    private final Map<UUID, Blind> blinded = new ConcurrentHashMap<>();
    private boolean fallbackNoted;

    Flash(FirearmsPlugin plugin) { this.plugin = plugin; }

    /** The grenade went off at `at`. */
    void burst(Location at, GrenadeType g, Player thrower) {
        var w = at.getWorld();
        w.playSound(at, "minecraft:entity.generic.explode", 1.6f, 1.7f);
        w.playSound(at, "minecraft:entity.firework_rocket.twinkle", 2.0f, 0.6f);
        w.spawnParticle(Particle.FLASH, at, 3, 0.1, 0.1, 0.1, 0);
        w.spawnParticle(Particle.END_ROD, at, 40, 0.3, 0.3, 0.3, 0.15);
        double radius = g.radius();
        for (Player p : w.getPlayers()) {
            Location eye = p.getEyeLocation();
            double d = eye.distance(at);
            if (d > radius) continue;
            Vector to = at.toVector().subtract(eye.toVector());
            if (d > 0.3 && w.rayTraceBlocks(eye, to.clone().normalize(), d, org.bukkit.FluidCollisionMode.NEVER, true) != null) continue;   // a wall shields
            double facing = d < 0.3 ? 1 : eye.getDirection().dot(to.clone().normalize());   // 1 = looking straight at it, -1 = back turned
            double facingFactor = 0.35 + 0.65 * Math.max(0, facing);
            double intensity = (1 - 0.7 * d / radius) * facingFactor;
            if (intensity < 0.08) continue;
            int ticks = (int) Math.round(g.duration() * 20 * Math.min(1, intensity + 0.25));
            blind(p, Math.min(1, intensity), ticks);
        }
    }

    /** Wash the player's screen white at `intensity` (0..1), fading out over `ticks`. */
    void blind(Player p, double intensity, int ticks) {
        if (ticks <= 0) return;
        int levels = Math.max(1, Math.min(32, plugin.getConfig().getInt("flashbang.levels", 10)));
        int hold = (int) Math.round(plugin.getConfig().getInt("flashbang.hold-ticks", 12) * intensity);
        p.playSound(p.getLocation(), "minecraft:block.note_block.chime", 1.0f, 2.0f);
        if (!PostEffects.available()) {
            if (!fallbackNoted) { fallbackNoted = true; plugin.getLogger().warning("Flashbang: no post effects on this server (" + PostEffects.problem() + ") - Blindness fallback"); }
            if (plugin.getConfig().getBoolean("flashbang.fallback-blindness", true)) p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, ticks, 0, true, false, false));
            return;
        }
        Blind b = blinded.computeIfAbsent(p.getUniqueId(), x -> new Blind());
        if (b.task != null) b.task.cancel();
        final int stamp = ++b.stamp;
        final int start = plugin.getServer().getCurrentTick();
        b.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline() || b.stamp != stamp) { clear(p); return; }
            int age = plugin.getServer().getCurrentTick() - start;
            int left = ticks - age;
            int level;
            if (left <= 0) level = 0;
            else if (age < hold) level = (int) Math.ceil(levels * intensity);
            else { double fade = Math.max(1, ticks - hold); level = (int) Math.ceil(levels * intensity * Math.min(1.0, left / fade)); }
            level = Math.max(0, Math.min(levels, level));
            if (level != b.level) {
                String from = b.level > 0 ? "firearms:flash_" + b.level : null, to = level > 0 ? "firearms:flash_" + level : null;
                if (!PostEffects.swap(p, from, to)) { clear(p); return; }
                b.level = level;
            }
            if (left <= 0) clear(p);
        }, 0L, 1L);
    }

    void clear(Player p) {
        Blind b = blinded.remove(p.getUniqueId());
        if (b == null) return;
        if (b.task != null) b.task.cancel();
        if (b.level > 0 && p.isOnline()) PostEffects.set(p, "firearms:flash_" + b.level, false);
    }

    void clearAll() { for (Player p : plugin.getServer().getOnlinePlayers()) clear(p); blinded.clear(); }
}
