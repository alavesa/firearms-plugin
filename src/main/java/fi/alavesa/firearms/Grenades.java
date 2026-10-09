package fi.alavesa.firearms;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Grenades: RIGHT-click unpins (the "unpin" clip; with cook: true the fuse starts now), LEFT-click throws (the
 * "throw" clip, then the grenade leaves the hand). The thrown grenade is an ItemDisplay with its own physics
 * (gravity, bounces, rolling to a stop), then: frag = blast damage, incendiary = fire, smoke = a smoke cloud.
 */
public final class Grenades implements Listener {

    private final FirearmsPlugin plugin;
    private final Registry registry;
    private final NamespacedKey armedKey, armedAtKey;
    private final List<Thrown> live = new ArrayList<>();
    private final Map<UUID, Long> lastThrow = new ConcurrentHashMap<>();
    private final Map<UUID, List<BukkitTask>> clips = new ConcurrentHashMap<>();
    private final Map<Location, Material> tempFire = new HashMap<>();

    private static final class Thrown {
        GrenadeType type; Player thrower; ItemDisplay d; Location pos; Vector vel; int fuseTicks; boolean resting; float spin;
        int smokeLeft; Location smokeAt;   // smoke phase
    }

    public Grenades(FirearmsPlugin plugin, Registry registry) {
        this.plugin = plugin;
        this.registry = registry;
        armedKey = new NamespacedKey(plugin, "armed");
        armedAtKey = new NamespacedKey(plugin, "armed_at");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player p = event.getPlayer();
        ItemStack item = p.getInventory().getItemInMainHand();
        GrenadeType g = registry.grenadeOf(item);
        if (g == null) return;
        boolean left = event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK;
        boolean right = event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK;
        if (!left && !right) return;
        event.setCancelled(true);
        long now = System.currentTimeMillis();
        Long last = lastThrow.put(p.getUniqueId(), now);
        if (last != null && now - last < 300) return;   // one action per click (air + block both fire)
        var meta = item.getItemMeta();
        boolean armed = meta.getPersistentDataContainer().has(armedKey, PersistentDataType.BYTE);
        if (right) {
            if (armed) return;
            meta.getPersistentDataContainer().set(armedKey, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(armedAtKey, PersistentDataType.LONG, now);
            item.setItemMeta(meta);
            p.playSound(p.getLocation(), "minecraft:item.flintandsteel.use", 0.8f, 1.6f);
            p.sendActionBar(Component.text(g.cook() ? "Pin pulled - " + g.fuse() + " s!" : "Pin pulled", NamedTextColor.YELLOW));
            playClip(p, g, item, "unpin", null);
            return;
        }
        // throw: play the throw clip, then launch and consume one
        int fuseTicks = (int) Math.round(g.fuse() * 20);
        if (armed && g.cook()) {
            long armedAt = meta.getPersistentDataContainer().getOrDefault(armedAtKey, PersistentDataType.LONG, now);
            fuseTicks = Math.max(1, fuseTicks - (int) ((now - armedAt) / 50));
        }
        final int fuse = fuseTicks;
        playClip(p, g, item, "throw", () -> launch(p, g, fuse));
    }

    private void launch(Player p, GrenadeType g, int fuseTicks) {
        ItemStack item = p.getInventory().getItemInMainHand();
        if (registry.grenadeOf(item) != g) return;
        // consume one; the rest of the stack is un-armed again
        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
            var meta = item.getItemMeta();
            meta.getPersistentDataContainer().remove(armedKey);
            meta.getPersistentDataContainer().remove(armedAtKey);
            item.setItemMeta(meta);
        } else p.getInventory().setItemInMainHand(null);
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        Thrown t = new Thrown();
        t.type = g; t.thrower = p; t.fuseTicks = fuseTicks;
        t.pos = eye.clone().add(dir.clone().multiply(0.6)).add(0, -0.2, 0);
        t.vel = dir.clone().multiply(g.speed()).add(new Vector(0, 0.08, 0)).add(p.getVelocity().clone().multiply(0.5));
        t.spin = (float) Math.toRadians(20 + ThreadLocalRandom.current().nextDouble() * 20);
        t.d = p.getWorld().spawn(t.pos, ItemDisplay.class, d -> {
            d.setItemStack(registry.buildGrenade(g, 1));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setPersistent(false);
            d.setTeleportDuration(1);
            d.setInterpolationDuration(1);
            d.setBrightness(new Display.Brightness(10, 15));
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.5f, 0.5f, 0.5f), new Quaternionf()));
            d.addScoreboardTag("firearms_grenade");
        });
        live.add(t);
        p.getWorld().playSound(p.getLocation(), "minecraft:entity.snowball.throw", 0.8f, 0.7f);
    }

    public void tick() {
        for (Iterator<Thrown> it = live.iterator(); it.hasNext(); ) {
            Thrown t = it.next();
            if (t.smokeLeft > 0) {   // smoke cloud phase
                t.smokeLeft--;
                smokePuff(t);
                if (t.smokeLeft <= 0) it.remove();
                continue;
            }
            if (!t.d.isValid() || !t.pos.isChunkLoaded()) { it.remove(); continue; }
            t.fuseTicks--;
            if (t.fuseTicks <= 0) { detonate(t); if (!t.type.kind().equals("smoke")) it.remove(); continue; }
            if (!t.resting) {
                t.vel.setY(t.vel.getY() - 0.045);
                t.vel.multiply(0.99);
                double len = t.vel.length();
                RayTraceResult hit = len > 0.01 ? t.pos.getWorld().rayTraceBlocks(t.pos, t.vel.clone().normalize(), len + 0.15, org.bukkit.FluidCollisionMode.NEVER, true) : null;
                if (hit != null && hit.getHitBlock() != null && hit.getHitBlockFace() != null) {
                    Vector n = hit.getHitBlockFace().getDirection();
                    t.pos = hit.getHitPosition().toLocation(t.pos.getWorld()).add(n.clone().multiply(0.08));
                    t.vel.subtract(n.clone().multiply(2 * t.vel.dot(n))).multiply(0.45);
                    if (hit.getHitBlockFace() == BlockFace.UP) t.vel.setX(t.vel.getX() * 0.7).setZ(t.vel.getZ() * 0.7);
                    if (t.vel.length() < 0.06 && hit.getHitBlockFace() == BlockFace.UP) { t.resting = true; t.vel.zero(); }
                    t.pos.getWorld().playSound(t.pos, "minecraft:block.stone.hit", 0.5f, 1.3f);
                } else {
                    t.pos.add(t.vel);
                }
                t.spin *= 0.98f;
                t.d.setInterpolationDelay(0);
                t.d.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotationXYZ(t.spin * t.fuseTicks, t.spin * 0.5f * t.fuseTicks, 0), new Vector3f(0.5f, 0.5f, 0.5f), new Quaternionf()));
                t.d.teleport(t.pos);
            }
            if (t.fuseTicks % 10 == 0) t.pos.getWorld().spawnParticle(Particle.SMOKE, t.pos, 2, 0.05, 0.05, 0.05, 0.01);
        }
    }

    private void detonate(Thrown t) {
        Location at = t.pos.clone();
        var w = at.getWorld();
        if (t.d.isValid()) t.d.remove();
        switch (t.type.kind()) {
            case "incendiary" -> {
                w.playSound(at, "minecraft:item.firecharge.use", 1.5f, 0.8f);
                w.playSound(at, "minecraft:entity.generic.explode", 0.6f, 1.4f);
                w.spawnParticle(Particle.FLAME, at, 120, t.type.radius() * 0.5, 0.6, t.type.radius() * 0.5, 0.05);
                w.spawnParticle(Particle.LAVA, at, 30, t.type.radius() * 0.4, 0.4, t.type.radius() * 0.4, 0);
                int burn = (int) Math.round(t.type.duration() * 20);
                for (Entity e : w.getNearbyEntities(at, t.type.radius(), t.type.radius(), t.type.radius()))
                    if (e instanceof LivingEntity le && e.getLocation().distance(at) <= t.type.radius()) le.setFireTicks(Math.max(le.getFireTicks(), burn));
                int r = (int) Math.ceil(t.type.radius());
                List<Location> lit = new ArrayList<>();
                for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = -1; dy <= 1; dy++) {
                    Block b = at.clone().add(dx, dy, dz).getBlock();
                    if (b.getLocation().distance(at) > t.type.radius() || ThreadLocalRandom.current().nextDouble() > 0.65) continue;
                    if (b.getType().isAir() && b.getRelative(BlockFace.DOWN).getType().isSolid()) {
                        tempFire.put(b.getLocation(), b.getType());
                        b.setType(Material.FIRE, false);
                        lit.add(b.getLocation());
                    }
                }
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    for (Location l : lit) { if (l.getBlock().getType() == Material.FIRE) l.getBlock().setType(Material.AIR, false); tempFire.remove(l); }
                }, burn);
            }
            case "smoke" -> {
                w.playSound(at, "minecraft:block.fire.extinguish", 1.2f, 0.6f);
                t.smokeLeft = (int) Math.round(t.type.duration() * 20);
                t.smokeAt = at;
            }
            default -> {   // frag
                w.playSound(at, "minecraft:entity.generic.explode", 2.0f, 0.9f);
                w.spawnParticle(Particle.EXPLOSION_EMITTER, at, 1);
                w.spawnParticle(Particle.CLOUD, at, 40, 0.8, 0.5, 0.8, 0.1);
                for (Entity e : w.getNearbyEntities(at, t.type.radius(), t.type.radius(), t.type.radius())) {
                    if (!(e instanceof LivingEntity le) || e.isDead()) continue;
                    double d = le.getLocation().add(0, 0.9, 0).distance(at);
                    if (d > t.type.radius()) continue;
                    // line of sight: a wall between grenade and target shields it
                    Vector to = le.getLocation().add(0, 0.9, 0).toVector().subtract(at.toVector());
                    if (to.lengthSquared() > 0.01 && w.rayTraceBlocks(at, to.clone().normalize(), to.length(), org.bukkit.FluidCollisionMode.NEVER, true) != null) continue;
                    double amount = t.type.damage() * (1 - d / t.type.radius());
                    if (amount <= 0.5) continue;
                    le.setNoDamageTicks(0);
                    double hp = le.getHealth();
                    le.damage(amount, t.thrower);
                    if (le instanceof Player && !le.isDead() && le.getHealth() >= hp - 0.001 && plugin.getConfig().getBoolean("bypass-pvp", true)) { le.setNoDamageTicks(0); le.damage(amount); }
                    le.setVelocity(le.getVelocity().add(to.normalize().multiply(0.6 * (1 - d / t.type.radius())).setY(0.25)));
                }
            }
        }
    }

    private void smokePuff(Thrown t) {
        var w = t.smokeAt.getWorld();
        double r = t.type.radius();
        var rnd = ThreadLocalRandom.current();
        for (int i = 0; i < 6; i++) {
            Location l = t.smokeAt.clone().add((rnd.nextDouble() * 2 - 1) * r * 0.8, rnd.nextDouble() * 1.6, (rnd.nextDouble() * 2 - 1) * r * 0.8);
            w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, l, 1, 0.1, 0.1, 0.1, 0.004);
            w.spawnParticle(Particle.CLOUD, l, 1, 0.3, 0.3, 0.3, 0.0);
        }
        if (t.smokeLeft % 40 == 0) w.playSound(t.smokeAt, "minecraft:block.fire.extinguish", 0.2f, 0.5f);
    }

    // --- clips (same frame mechanism as guns, on the grenade item)
    private void playClip(Player p, GrenadeType g, ItemStack item, String clip, Runnable then) {
        int[] a = registry.anim(g.model(), clip);
        cancelClip(p);
        if (a == null) { if (then != null) then.run(); return; }
        int slot = p.getInventory().getHeldItemSlot();
        List<BukkitTask> tasks = new ArrayList<>();
        for (int i = 1; i <= a[0]; i++) {
            final int frame = i;
            tasks.add(plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                ItemStack cur = p.getInventory().getItem(slot);
                if (registry.grenadeOf(cur) != g || p.getInventory().getHeldItemSlot() != slot) { cancelClip(p); return; }
                registry.setModel(cur, g.model() + "_" + clip + "_" + frame);
            }, (long) (i - 1) * a[1]));
        }
        tasks.add(plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            clips.remove(p.getUniqueId());
            ItemStack cur = p.getInventory().getItem(slot);
            if (registry.grenadeOf(cur) == g) registry.setModel(cur, g.model());
            if (then != null) then.run();
        }, (long) a[0] * a[1]));
        clips.put(p.getUniqueId(), tasks);
    }

    private void cancelClip(Player p) {
        List<BukkitTask> t = clips.remove(p.getUniqueId());
        if (t != null) t.forEach(BukkitTask::cancel);
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent event) { cancelClip(event.getPlayer()); }

    public void clearAll() {
        for (Thrown t : live) if (t.d != null && t.d.isValid()) t.d.remove();
        live.clear();
        for (Location l : tempFire.keySet()) if (l.getBlock().getType() == Material.FIRE) l.getBlock().setType(Material.AIR, false);
        tempFire.clear();
        for (var w : plugin.getServer().getWorlds())
            for (var e : w.getEntitiesByClass(ItemDisplay.class))
                if (e.getScoreboardTags().contains("firearms_grenade")) e.remove();
    }
}
