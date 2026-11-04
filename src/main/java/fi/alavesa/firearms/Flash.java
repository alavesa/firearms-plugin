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
 * it, then it fades. The white is one glyph of the pack's firearms:flash font (a 1024x512 white bitmap, drawn 4x as
 * a TITLE so it covers any screen); the title's own fade-out does the fading. No shaders, no post effects, nothing
 * drawn in the world; without the pack the glyph is a missing-character box and nothing else happens.
 */
final class Flash {
    private static final Component GLYPH = Component.text("", NamedTextColor.WHITE).font(Key.key("firearms", "flash"));

    private final FirearmsPlugin plugin;

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
            blind(p, Math.min(1, intensity), (int) Math.round(g.duration() * 20 * Math.min(1, intensity + 0.25)));
        }
    }

    /** White screen for `ticks`: solid for flashbang.hold-ticks (scaled by intensity), then fading over the rest. */
    void blind(Player p, double intensity, int ticks) {
        if (ticks <= 0) return;
        int hold = Math.min(ticks, (int) Math.round(plugin.getConfig().getInt("flashbang.hold-ticks", 12) * intensity));
        int fade = Math.max(1, ticks - hold);
        p.playSound(p.getLocation(), "minecraft:block.note_block.chime", 1.0f, 2.0f);
        p.showTitle(Title.title(GLYPH, Component.empty(), Title.Times.times(Duration.ZERO, Duration.ofMillis(hold * 50L), Duration.ofMillis(fade * 50L))));
    }

    void clear(Player p) { p.clearTitle(); }

    void clearAll() { for (Player p : plugin.getServer().getOnlinePlayers()) p.clearTitle(); }
}
