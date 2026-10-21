package fi.alavesa.firearms;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * Flinch animations on a shot player, played as RP Engine EMOTES. The animations come from a player rig .bbmodel in
 * plugins/Firearms/models/players/<rig>.bbmodel (BetterModel-style player rig: bones ph, pc, pra, pla ...), which is
 * mirrored into plugins/RPEngine/content/firearms/players/ - RP Engine turns every animation of it into the emote
 * "<rig>_<animation>" (hand-written emotes in any pack work too). Which one plays depends on the hit zone (head /
 * body / legs) and the side the bullet came from (left / right), config hit-animations.animations:
 *   head_left -> head -> body_left -> body   (first emote that exists wins).
 * RP Engine is optional: without it nothing happens.
 */
public final class HitAnimations {
    public static final String NAMESPACE = "firearms";
    private final FirearmsPlugin plugin;
    private final boolean available;

    public HitAnimations(FirearmsPlugin plugin) {
        this.plugin = plugin;
        boolean ok = false;
        try { Class.forName("ai.resourcepack.engine.api.Emotes"); ok = Bukkit.getPluginManager().getPlugin("RPEngine") != null; } catch (Throwable ignored) { }
        available = ok;
    }

    public boolean available() { return available && plugin.getConfig().getBoolean("hit-animations.enabled", true); }

    private static ai.resourcepack.engine.RPEnginePlugin engine() { return (ai.resourcepack.engine.RPEnginePlugin) Bukkit.getPluginManager().getPlugin("RPEngine"); }

    /** Mirror models/players/*.bbmodel into RP Engine's content folder (as emotes); reload RP Engine when anything changed. */
    public int sync() {
        if (!available) return 0;
        File src = new File(new File(plugin.getDataFolder(), "models"), "players");
        src.mkdirs();
        File pack = new File(new File(engine().getDataFolder(), "content"), NAMESPACE);
        File dst = new File(pack, "players");
        dst.mkdirs();
        try { File meta = new File(pack, "pack.yml"); if (!meta.exists()) Files.writeString(meta.toPath(), "name: Firearms\nauthor: Firearms plugin (generated - do not edit)\nversion: " + plugin.getPluginMeta().getVersion() + "\n"); } catch (Exception ignored) { }
        int changed = 0;
        Set<String> wanted = new HashSet<>();
        File[] files = src.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".bbmodel"));
        if (files != null) for (File f : files) {
            File t = new File(dst, f.getName().toLowerCase(Locale.ROOT));
            wanted.add(t.getName());
            try {
                byte[] data = Files.readAllBytes(f.toPath());
                if (!t.exists() || !Arrays.equals(data, Files.readAllBytes(t.toPath()))) { Files.write(t.toPath(), data); changed++; }
            } catch (Exception e) { plugin.getLogger().warning("Could not copy " + f.getName() + " to RP Engine: " + e); }
        }
        File[] stale = dst.listFiles((d, n) -> n.endsWith(".bbmodel") && !wanted.contains(n));
        if (stale != null) for (File s : stale) if (s.delete()) changed++;
        if (changed > 0) {
            plugin.getLogger().info("RP Engine: " + changed + " player rig file(s) changed in content/" + NAMESPACE + "/players - reloading RP Engine (its animations become emotes).");
            try { engine().reload(); } catch (Throwable t) { plugin.getLogger().warning("RP Engine reload failed: " + t); }
        }
        return changed;
    }

    private String configured(String key) { return plugin.getConfig().getString("hit-animations.animations." + key, ""); }

    /** "left" when the hit point is on the victim's left, else "right" (by the victim's facing). */
    static String side(float victimYaw, Location victim, Location hit) {
        if (hit == null) return "right";
        double rad = Math.toRadians(victimYaw);
        double rx = -Math.cos(rad), rz = -Math.sin(rad);   // the right-hand side of a mob facing (-sin, cos)
        double dx = hit.getX() - victim.getX(), dz = hit.getZ() - victim.getZ();
        return dx * rx + dz * rz >= 0 ? "right" : "left";
    }

    static String[] keys(String zone, String side) { return new String[]{ zone + "_" + side, zone, "body_" + side, "body" }; }

    /** The emote id for a configured animation name: "<rig>_<name>" (a rig file's animation) or "<name>" (a hand-written emote). */
    String emoteFor(String name, Collection<String> ids) {
        String rig = plugin.getConfig().getString("hit-animations.rig", "player_rig").toLowerCase(Locale.ROOT).replaceAll("\\.bbmodel$", "");
        for (String cand : new String[]{ rig.isEmpty() ? name : rig + "_" + name, name })
            for (String id : ids) if (id.equalsIgnoreCase(cand)) return id;
        return null;
    }

    public void play(Player victim, String zone, Location hitPoint) {
        if (!available()) return;
        try {
            List<String> ids = engine().emotes().ids();
            String side = side(victim.getLocation().getYaw(), victim.getLocation(), hitPoint);
            for (String k : keys(zone, side)) {
                String name = configured(k);
                if (name.isEmpty()) continue;
                String emote = emoteFor(name, ids);
                if (emote == null) continue;
                engine().emotes().play(victim, emote);
                return;
            }
        } catch (Throwable t) { plugin.getLogger().fine("hit animation failed: " + t); }
    }
}
