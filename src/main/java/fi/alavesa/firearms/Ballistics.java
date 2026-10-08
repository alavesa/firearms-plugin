package fi.alavesa.firearms;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ray-based bullets. A shot is INSTANT (hitscan) out to the gun's hitscan-range; past that it becomes a
 * simulated projectile (no entity) that is stepped every tick: it slows down (drag), drops (gravity) and
 * ray-traces each tick's segment, so it can still hit at 30 blocks but not 100. Damage falls off linearly
 * from the hitscan range to the max range. Pass-through blocks (wood, wool, bars, water, lava, powder snow)
 * are flown through if there is only ONE layer of them; a second layer in a row stops the bullet.
 * Block hits leave a crater (an ItemDisplay you texture) that fades after a while.
 */
public final class Ballistics {

    private final FirearmsPlugin plugin;
    private final Registry registry;
    private final List<Bullet> bullets = new ArrayList<>();
    private final Deque<UUID> craters = new ArrayDeque<>();
    private Set<Material> pass = EnumSet.noneOf(Material.class);
    private Set<Material> ignore = EnumSet.noneOf(Material.class);

    private static final class Bullet {
        final Player shooter; final GunType gun; final double dmgMult;
        Location pos; Vector vel; double traveled;
        Bullet(Player s, GunType g, Location p, Vector v, double m) { shooter = s; gun = g; pos = p; vel = v; dmgMult = m; }
    }

    /** Result of a trace: whichever came first. */
    private static final class Hit {
        LivingEntity entity; Block block; BlockFace face; Location point; double dist; int penetrations;
    }

    public Ballistics(FirearmsPlugin plugin, Registry registry) {
        this.plugin = plugin;
        this.registry = registry;
        load();
    }

    public void load() {
        pass = parse(plugin.getConfig().getStringList("ballistics.pass-through"));
        ignore = parse(plugin.getConfig().getStringList("ballistics.ignore"));
    }

    @SuppressWarnings("unchecked")
    private static Set<Material> parse(List<String> names) {
        Set<Material> out = EnumSet.noneOf(Material.class);
        for (String n : names) {
            String s = n.trim();
            if (s.startsWith("#")) {
                Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_BLOCKS, NamespacedKey.minecraft(s.substring(1).toLowerCase()), Material.class);
                if (tag != null) out.addAll(tag.getValues());
            } else {
                try { out.add(Material.valueOf(s.toUpperCase())); } catch (IllegalArgumentException ignored) { }
            }
        }
        return out;
    }

    private boolean passable(Material m) { return pass.contains(m) || ignore.contains(m); }

    // ------------------------------------------------------------------ firing

    /** Fire one pellet from the shooter's eye along dir. */
    public void fire(Player shooter, GunType gun, Vector dir, double dmgMult) {
        Location eye = shooter.getEyeLocation();
        dir = dir.clone().normalize();
        Hit hit = trace(eye, dir, gun.hitscanRange(), shooter);
        Location end = hit != null ? hit.point : eye.clone().add(dir.clone().multiply(gun.hitscanRange()));
        // The RAY starts at the eye (so it lands exactly on the crosshair); the visible tracer and the flash
        // start at the gun's muzzle in the hand (guns.yml muzzle: right,up,forward) and converge onto the ray.
        Location muzzle = muzzle(shooter, gun, dir);
        tracer(muzzle, end);
        flash(shooter, muzzle);
        if (hit != null) { resolve(hit, shooter, gun, hit.dist, dmgMult); return; }
        if (gun.range() <= gun.hitscanRange()) return;
        // Projectile phase: continue from the end of the hitscan as a slowing, dropping ray bullet.
        bullets.add(new Bullet(shooter, gun, end.clone(), dir.clone().multiply(Math.max(0.5, gun.speed())), dmgMult));
    }

    /** Every tick: step the live projectiles. */
    public void tick() {
        double drag = plugin.getConfig().getDouble("ballistics.projectile-drag", 0.06);
        double gravity = plugin.getConfig().getDouble("ballistics.projectile-gravity", 0.03);
        for (Iterator<Bullet> it = bullets.iterator(); it.hasNext(); ) {
            Bullet b = it.next();
            double step = b.vel.length();
            if (step < 0.15 || b.pos.getWorld() == null || !b.pos.isChunkLoaded()) { it.remove(); continue; }
            Vector dir = b.vel.clone().normalize();
            Hit hit = trace(b.pos, dir, step, b.shooter);
            if (hit != null) {
                tracer(b.pos, hit.point);
                resolve(hit, b.shooter, b.gun, b.gun.hitscanRange() + b.traveled + hit.dist, b.dmgMult);
                it.remove();
                continue;
            }
            Location next = b.pos.clone().add(b.vel);
            tracer(b.pos, next);
            b.pos = next;
            b.traveled += step;
            b.vel.multiply(1.0 - drag);
            b.vel.setY(b.vel.getY() - gravity);
            if (b.gun.hitscanRange() + b.traveled >= b.gun.range()) it.remove();
        }
    }

    private void resolve(Hit hit, Player shooter, GunType gun, double distance, double dmgMult) {
        if (hit.entity != null) {
            double f = falloff(gun, distance);
            // Shot through a penetrable wall: less damage per layer passed (ballistics.penetration-damage).
            double pen = Math.pow(Math.max(0, Math.min(1, plugin.getConfig().getDouble("ballistics.penetration-damage", 0.9))), hit.penetrations);
            double amount = gun.damage() * dmgMult * f * pen;
            if (amount <= 0) return;
            amount = vestAbsorb(hit.entity, amount);
            if (amount <= 0.001) return;
            hit.entity.setNoDamageTicks(0);          // fast fire must register every round
            double hpBefore = hit.entity.getHealth();
            hit.entity.damage(amount, shooter);
            // PvP off (server.properties / world / region): the game cancels player-vs-player damage, so hits
            // register (sounds, vest) but nothing happens. Force it through as source-less damage.
            if (hit.entity instanceof Player && !hit.entity.isDead() && hit.entity.getHealth() >= hpBefore - 0.001
                && plugin.getConfig().getBoolean("bypass-pvp", true)) {
                hit.entity.setNoDamageTicks(0);
                hit.entity.damage(amount);
                if (shooter != null && hit.entity.isDead()) hit.entity.getWorld().getPlayers().forEach(pl -> { });
            }
            if (shooter != null && shooter.isOnline())
                shooter.playSound(shooter.getLocation(), "minecraft:entity.arrow.hit_player", 0.6f, 1.4f);
        } else if (hit.block != null) {
            World w = hit.block.getWorld();
            w.spawnParticle(Particle.BLOCK, hit.point, 6, 0.05, 0.05, 0.05, 0.02, hit.block.getBlockData());
            w.playSound(hit.point, "minecraft:block.stone.hit", 0.5f, 1.2f);
            crater(hit.block, hit.point, hit.face);
        }
    }

    /** A worn vest soaks its share of the bullet into its durability; the rest reaches the wearer. */
    private double vestAbsorb(LivingEntity target, double amount) {
        var eq = target.getEquipment();
        if (eq == null) return amount;
        ItemStack chest = eq.getChestplate();
        ArmorType vest = registry.vestOf(chest);
        if (vest == null) return amount;
        double soak = amount * vest.absorb();
        boolean broke = registry.damageVest(chest, soak);
        if (broke) {
            eq.setChestplate(null);
            target.getWorld().playSound(target.getLocation(), "minecraft:entity.item.break", 1f, 0.8f);
            if (target instanceof Player p) p.sendActionBar(net.kyori.adventure.text.Component.text("Your vest is destroyed!", net.kyori.adventure.text.format.NamedTextColor.RED));
        } else {
            eq.setChestplate(chest);
            target.getWorld().playSound(target.getLocation(), "minecraft:item.armor.equip_chain", 0.7f, 0.6f);
        }
        return amount - soak;
    }

    /** 1.0 inside the hitscan range, then linear down to falloff-min at the max range. */
    static double falloff(GunType gun, double distance) {
        if (distance <= gun.hitscanRange()) return 1.0;
        double span = Math.max(0.001, gun.range() - gun.hitscanRange());
        double t = Math.min(1.0, (distance - gun.hitscanRange()) / span);
        return 1.0 - t * (1.0 - gun.falloffMin());
    }

    // ------------------------------------------------------------------ tracing

    /** Trace along dir for at most maxDist, flying through pass-through blocks (one layer) and ignored ones. */
    private Hit trace(Location from, Vector dir, double maxDist, Player shooter) {
        World w = from.getWorld();
        Location cur = from.clone();
        double left = maxDist;
        double used = 0;
        int penetrations = 0;
        for (int hop = 0; hop < 8 && left > 0.01; hop++) {
            RayTraceResult ent = w.rayTraceEntities(cur, dir, left, 0.25, e -> target(e, shooter));
            RayTraceResult blk = w.rayTraceBlocks(cur, dir, left, FluidCollisionMode.ALWAYS, true);
            double ed = ent != null && ent.getHitEntity() != null ? ent.getHitPosition().distance(cur.toVector()) : Double.MAX_VALUE;
            double bd = blk != null && blk.getHitBlock() != null ? blk.getHitPosition().distance(cur.toVector()) : Double.MAX_VALUE;
            if (ed == Double.MAX_VALUE && bd == Double.MAX_VALUE) return null;
            if (ed <= bd) {
                Hit h = new Hit();
                h.entity = (LivingEntity) ent.getHitEntity();
                h.point = ent.getHitPosition().toLocation(w);
                h.dist = used + ed;
                h.penetrations = penetrations;
                return h;
            }
            Block b = blk.getHitBlock();
            Material type = b.getType();
            if (!passable(type)) {
                Hit h = new Hit();
                h.block = b; h.face = blk.getHitBlockFace();
                h.point = blk.getHitPosition().toLocation(w);
                h.dist = used + bd;
                return h;
            }
            // Pass-through: step through this block to its far side.
            Location exit = blk.getHitPosition().toLocation(w).add(dir.clone().multiply(0.05));
            int steps = 0;
            while (exit.getBlock().equals(b) && steps++ < 40) exit.add(dir.clone().multiply(0.05));
            exit.add(dir.clone().multiply(0.02));
            Material beyond = exit.getBlock().getType();
            if (pass.contains(type) && !ignore.contains(type) && pass.contains(beyond) && !ignore.contains(beyond)) {
                // A second layer right behind it: the bullet stops in the first block.
                Hit h = new Hit();
                h.block = b; h.face = blk.getHitBlockFace();
                h.point = blk.getHitPosition().toLocation(w);
                h.dist = used + bd;
                return h;
            }
            if (!ignore.contains(type)) penetrations++;    // a real wall layer passed (barriers etc. don't count)
            double d = exit.distance(cur);
            used += d; left -= d; cur = exit;
        }
        return null;
    }

    private static boolean target(Entity e, Player shooter) {
        if (!(e instanceof LivingEntity le) || e == shooter || e.isDead()) return false;
        if (e instanceof Player p && (p.getGameMode() == org.bukkit.GameMode.SPECTATOR || p.getGameMode() == org.bukkit.GameMode.CREATIVE)) return false;
        if (e instanceof org.bukkit.entity.ArmorStand as && as.isMarker()) return false;
        return !le.isInvulnerable();
    }

    /** Muzzle point in the world for this shot: eye + right/up/forward offsets from the gun. */
    public static Location muzzle(Player p, GunType gun, Vector dir) {
        Location eye = p.getEyeLocation();
        double[] m = gun.muzzle() == null ? new double[]{0.35, -0.22, 0.7} : gun.muzzle();
        Vector fwd = dir.clone().normalize();
        Vector right = fwd.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1e-6) right = new Vector(1, 0, 0);
        right.normalize();
        Vector up = right.clone().crossProduct(fwd).normalize();
        Location out = eye.clone().add(right.multiply(m[0])).add(up.multiply(m[1])).add(fwd.multiply(m[2]));
        // never start inside a block (muzzle against a wall): pull back to the eye
        return out.getBlock().getType().isAir() ? out : eye.clone().add(fwd.clone().multiply(0.1));
    }

    private final Map<Location, Integer> flashCells = new HashMap<>();

    /** Muzzle flash: the air cell at the muzzle becomes a level-15 LIGHT block CLIENT-SIDE for every nearby
     *  player for 2 ticks, so the shooter and bystanders see the surroundings light up (no server block). */
    private void flash(Player shooter, Location muzzle) {
        if (!plugin.getConfig().getBoolean("flash.light", true)) return;
        Location cell = muzzle.getBlock().getLocation();
        if (!cell.getBlock().getType().isAir()) return;
        BlockData light = Material.LIGHT.createBlockData();
        if (light instanceof org.bukkit.block.data.Levelled lv) lv.setLevel(Math.max(1, Math.min(15, plugin.getConfig().getInt("flash.level", 15))));
        double range = plugin.getConfig().getDouble("flash.range", 32);
        List<Player> viewers = new ArrayList<>();
        for (Player v : muzzle.getWorld().getPlayers()) if (v.getLocation().distanceSquared(muzzle) <= range * range) { v.sendBlockChange(cell, light); viewers.add(v); }
        int ticks = Math.max(1, plugin.getConfig().getInt("flash.ticks", 2));
        flashCells.merge(cell, 1, Integer::sum);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            int left = flashCells.merge(cell, -1, Integer::sum);
            if (left <= 0) { flashCells.remove(cell); for (Player v : viewers) if (v.isOnline() && cell.isChunkLoaded()) v.sendBlockChange(cell, cell.getBlock().getBlockData()); }
        }, ticks);
    }

    private void tracer(Location a, Location b) {
        if (!plugin.getConfig().getBoolean("ballistics.tracer", true)) return;
        double step = Math.max(0.5, plugin.getConfig().getDouble("ballistics.tracer-step", 1.5));
        Vector d = b.toVector().subtract(a.toVector());
        double len = d.length();
        if (len < 0.01) return;
        d.normalize().multiply(step);
        Location p = a.clone();
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(255, 225, 140), 0.55f);
        for (double t = 0; t < len; t += step) {
            a.getWorld().spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, dust);
            p.add(d);
        }
    }

    // ------------------------------------------------------------------ craters

    private void crater(Block block, Location point, BlockFace face) {
        if (!plugin.getConfig().getBoolean("craters.enabled", true) || face == null) return;
        World w = block.getWorld();
        Vector n = face.getDirection();
        Location at = point.clone().add(n.clone().multiply(0.015));
        float size = (float) plugin.getConfig().getDouble("craters.size", 0.35);
        ItemDisplay d = w.spawn(at, ItemDisplay.class, disp -> {
            disp.setItemStack(registry.buildCrater());
            disp.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            disp.setPersistent(false);
            disp.setBrightness(new org.bukkit.entity.Display.Brightness(8, 15));
            // FIXED shows the item flat with its face toward +Z; turn +Z onto the struck face's normal,
            // with a random spin around it so holes don't all look identical.
            Quaternionf q = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), new Vector3f((float) n.getX(), (float) n.getY(), (float) n.getZ()));
            q.mul(new Quaternionf().rotationZ((float) (ThreadLocalRandom.current().nextDouble() * Math.PI * 2)));
            disp.setTransformation(new Transformation(new Vector3f(), q, new Vector3f(size, size, size), new Quaternionf()));
            disp.addScoreboardTag("firearms_crater");
        });
        craters.addLast(d.getUniqueId());
        int max = plugin.getConfig().getInt("craters.max-per-world", 400);
        while (craters.size() > max) removeCrater(craters.pollFirst());
        long life = Math.max(20, (long) (plugin.getConfig().getDouble("craters.seconds", 90) * 20));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> { craters.remove(d.getUniqueId()); removeCrater(d.getUniqueId()); }, life);
    }

    private void removeCrater(UUID id) {
        if (id == null) return;
        Entity e = plugin.getServer().getEntity(id);
        if (e != null) e.remove();
    }

    /** Remove every crater (disable) - also sweeps any left over from a crash. */
    public void clearAll() {
        for (UUID id : craters) removeCrater(id);
        craters.clear();
        for (World w : plugin.getServer().getWorlds())
            for (Entity e : w.getEntitiesByClass(ItemDisplay.class))
                if (e.getScoreboardTags().contains("firearms_crater")) e.remove();
        bullets.clear();
    }
}
