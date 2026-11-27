package fi.alavesa.firearms;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.time.Duration;

/**
 * Flashbang: everyone in radius with a line of sight gets their screen washed white, strongest when near and facing
 * it, then it fades. Default (flashbang.mode: postfx, 26.3): the pack's post effects firearms:flash_1..24 (FlashPostFx:
 * white-out, blur, drained colour and bloom, one overall strength per level); the plugin holds the level of the hit and
 * steps it down tick by tick, so the white goes first and the blur / grey "ringing" lingers. Fallback (flashbang.mode:
 * title, or a server without per-player post effects): one glyph of the pack's firearms:flash font drawn as a fading
 * TITLE. The post shader is a POST shader, never a core shader: a mistake in it cannot make the client drop the pack.
 */
final class Flash {
    private static final Component GLYPH = Component.text("", NamedTextColor.WHITE).font(Key.key("firearms", "flash"));

    private final FirearmsPlugin plugin;
    /** The running fade of each player: the plugin's own task and the level that is on the player's screen now. */
    private final java.util.Map<java.util.UUID, Fade> fades = new java.util.HashMap<>();

    private static final class Fade {
        org.bukkit.scheduler.BukkitTask task;
        int level;
    }

    Flash(FirearmsPlugin plugin) { this.plugin = plugin; }

    private boolean postFx() {
        return PostEffects.available() && !plugin.getConfig().getString("flashbang.mode", "postfx").equalsIgnoreCase("title");
    }

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
            blind(p, Math.min(1, intensity), (int) Math.round(g.duration() * 20 * Math.min(1, intensity + 0.25)));
        }
    }

    /**
     * White screen for `ticks`: held at the strength of the hit for flashbang.hold-ticks (scaled by intensity), then fading
     * over the rest. In postfx mode the strength s (0..1) picks the effect level; the white-out disappears first
     * (levels above ~30 %), the blur and the grey tail last.
     */
    void blind(Player p, double intensity, int ticks) {
        if (ticks <= 0) return;
        int hold = Math.min(ticks, (int) Math.round(plugin.getConfig().getInt("flashbang.hold-ticks", 12) * intensity));
        int fade = Math.max(1, ticks - hold);
        p.playSound(p.getLocation(), "minecraft:block.note_block.chime", 1.0f, 2.0f);
        if (!postFx()) {
            p.showTitle(Title.title(GLYPH, Component.empty(), Title.Times.times(Duration.ZERO, Duration.ofMillis(hold * 50L), Duration.ofMillis(fade * 50L))));
            return;
        }
        // a longer tail than the white: the ringing outlasts the flash
        int tail = (int) Math.round(fade * plugin.getConfig().getDouble("flashbang.tail", 1.0));
        double strength = Math.min(1, intensity * 1.15 + 0.1);
        cancel(p, true);
        Fade f = new Fade();
        fades.put(p.getUniqueId(), f);
        int total = hold + Math.max(1, tail);
        int[] t = {0};
        f.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline()) { cancel(p, false); return; }
            double s;
            if (t[0] < hold) s = strength;
            else s = strength * Math.pow(Math.max(0, 1 - (t[0] - hold) / (double) Math.max(1, tail)), 1.3);
            int level = Math.min(FlashPostFx.LEVELS, (int) Math.ceil(s * FlashPostFx.LEVELS - 1e-9));
            if (level != f.level) {
                PostEffects.swap(p, f.level > 0 ? FlashPostFx.id(f.level) : null, level > 0 ? FlashPostFx.id(level) : null);
                f.level = level;
            }
            if (++t[0] > total) cancel(p, true);
        }, 0L, 1L);
    }

    /** Stops the player's fade; {@code remove} also takes the effect off the screen. */
    private void cancel(Player p, boolean remove) {
        Fade f = fades.remove(p.getUniqueId());
        if (f == null) return;
        if (f.task != null) f.task.cancel();
        if (remove && f.level > 0 && p.isOnline()) PostEffects.swap(p, FlashPostFx.id(f.level), null);
    }

    void clear(Player p) {
        p.clearTitle();
        cancel(p, true);
    }

    void clearAll() { for (Player p : plugin.getServer().getOnlinePlayers()) clear(p); }
}
