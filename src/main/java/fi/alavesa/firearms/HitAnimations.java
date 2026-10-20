package fi.alavesa.firearms;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.UUID;

/**
 * Flinch animations on a shot player, played through BetterModel on the player's rig (a "limb" model in
 * plugins/BetterModel/players/<rig>.bbmodel, default player_rig). Which animation plays depends on the hit zone
 * (head / body / legs) and the side the bullet came from (left / right), config hit-animations.animations:
 *   head_left -> head -> body   (first one that exists in the rig wins).
 * BetterModel is optional: without it (or without the rig) nothing happens.
 */
public final class HitAnimations {
    private final FirearmsPlugin plugin;
    private final boolean available;

    public HitAnimations(FirearmsPlugin plugin) {
        this.plugin = plugin;
        boolean ok = false;
        try { Class.forName("kr.toxicity.model.api.BetterModel"); ok = plugin.getServer().getPluginManager().getPlugin("BetterModel") != null; } catch (Throwable ignored) { }
        available = ok;
    }

    public boolean available() { return available && plugin.getConfig().getBoolean("hit-animations.enabled", true); }

    /** The configured animation name for a zone + side key, "" when unset. */
    private String configured(String key) { return plugin.getConfig().getString("hit-animations.animations." + key, ""); }

    /** "left" when the hit point is on the victim's left, else "right" (by the victim's facing). */
    static String side(float victimYaw, Location victim, Location hit) {
        if (hit == null) return "right";
        double rad = Math.toRadians(victimYaw);
        // facing (-sin, cos); the right-hand side is (-cos, -sin) in Minecraft's frame
        double rx = -Math.cos(rad), rz = -Math.sin(rad);
        double dx = hit.getX() - victim.getX(), dz = hit.getZ() - victim.getZ();
        return dx * rx + dz * rz >= 0 ? "right" : "left";
    }

    /** Candidate config keys, most specific first. */
    static String[] keys(String zone, String side) {
        return new String[]{ zone + "_" + side, zone, "body_" + side, "body" };
    }

    public void play(Player victim, String zone, Location hitPoint) {
        if (!available()) return;
        try { playBm(victim, zone, hitPoint); } catch (Throwable t) { plugin.getLogger().fine("hit animation failed: " + t); }
    }

    private void playBm(Player victim, String zone, Location hitPoint) {
        String rig = plugin.getConfig().getString("hit-animations.rig", "player_rig").toLowerCase(Locale.ROOT);
        String side = side(victim.getLocation().getYaw(), victim.getLocation(), hitPoint);
        UUID id = victim.getUniqueId();
        kr.toxicity.model.api.tracker.EntityTrackerRegistry reg = kr.toxicity.model.api.tracker.EntityTrackerRegistry.registry(id);
        kr.toxicity.model.api.tracker.EntityTracker tracker = null;
        if (reg != null) tracker = rig.isEmpty() ? reg.first() : reg.tracker(rig);
        if (tracker == null && reg != null && !rig.isEmpty()) tracker = reg.first();   // a rig under another name
        boolean temporary = false;
        if (tracker == null && plugin.getConfig().getBoolean("hit-animations.attach-if-missing", false) && !rig.isEmpty()) {
            var limb = kr.toxicity.model.api.BetterModel.limb(rig);
            if (limb.isEmpty()) return;
            tracker = limb.get().create(kr.toxicity.model.api.bukkit.platform.BukkitAdapter.adapt(victim));
            temporary = true;
        }
        if (tracker == null || tracker.isClosed()) return;
        var renderer = tracker.renderer();
        String anim = null;
        for (String k : keys(zone, side)) {
            String name = configured(k);
            if (name.isEmpty()) continue;
            if (renderer.animation(name).isPresent()) { anim = name; break; }
            for (String n : renderer.animations().keySet()) if (n.equalsIgnoreCase(name) || n.replace(' ', '_').equalsIgnoreCase(name)) { anim = n; break; }
            if (anim != null) break;
        }
        if (anim == null) { if (temporary) tracker.close(); return; }
        final var t = tracker;
        t.animate(anim, kr.toxicity.model.api.animation.AnimationModifier.builder().type(kr.toxicity.model.api.animation.AnimationIterator.Type.PLAY_ONCE).build());
        if (temporary) {
            float len = renderer.animation(anim).map(a -> a.length()).orElse(1f);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> { if (!t.isClosed()) t.close(); }, Math.max(5L, (long) (len * 20) + 2));
        }
    }
}
