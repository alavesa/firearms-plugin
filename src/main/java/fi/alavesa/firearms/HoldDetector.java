package fi.alavesa.firearms;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LEFT-HOLD detection. Minecraft's client re-sends the arm swing EVERY TICK only while it is MINING a block
 * under the crosshair; clicking air or an entity sends ONE swing per press. So while an AUTO gun is held an
 * invisible, unbreakable BARRIER is kept CLIENT-SIDE-ONLY on the crosshair ray, ahead of the player: holding
 * LEFT then "mines" it for ever and every per-tick swing becomes a shot; releasing ends it the same tick.
 * Placement each tick: a 3x3x3 cluster at `hold.distance` blocks (and one where the crosshair is heading,
 * so fast turns keep streaming); a nearer real block is mined instead (Mining Fatigue = no cracks); an
 * entity under the crosshair gets the box just in front of it (the client picks entities over blocks);
 * never within ~1.2 blocks of the player's own hitbox. Cells linger a few ticks, then the real block is
 * re-sent. Also keeps the per-holder attributes: block-reach bonus, weight (walk speed) and the fatigue.
 */
public final class HoldDetector {

    private final FirearmsPlugin plugin;
    private final Registry registry;
    private final Map<UUID, Map<Location, Integer>> cells = new ConcurrentHashMap<>();
    private final Map<UUID, Vector> lastDir = new ConcurrentHashMap<>();
    private final NamespacedKey reachKey, weightKey, atkKey;
    private int tick = 0;

    public HoldDetector(FirearmsPlugin plugin, Registry registry) {
        this.plugin = plugin;
        this.registry = registry;
        this.reachKey = new NamespacedKey(plugin, "block_reach");
        this.weightKey = new NamespacedKey(plugin, "weight");
        this.atkKey = new NamespacedKey(plugin, "attack_speed");
    }

    /** Every tick: maintain each auto-gun holder's client-side barrier cluster. */
    public void tick() {
        tick++;
        double maxDist = plugin.getConfig().getDouble("hold.distance", 7.0);
        double lead = plugin.getConfig().getDouble("hold.lead", 3.0);
        int linger = plugin.getConfig().getInt("hold.linger-ticks", 3);
        BlockData fake = Material.BARRIER.createBlockData();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            ItemStack held = p.getInventory().getItemInMainHand();
            GunType gun = registry.gunOf(held);
            boolean auto = gun != null && registry.isAuto(held, gun);
            Map<Location, Integer> mine = cells.get(id);
            Set<Location> want = new HashSet<>();
            if (auto && maxDist > 0 && !p.isDead() && p.isValid()) {
                Vector dir = p.getEyeLocation().getDirection();
                Vector prev = lastDir.put(id, dir.clone());
                addCluster(want, p, dir, maxDist);
                if (prev != null && lead > 0) {
                    Vector pred = dir.clone().add(dir.clone().subtract(prev).multiply(lead));
                    if (pred.lengthSquared() > 1e-6 && pred.clone().normalize().dot(dir) < 0.999) addCluster(want, p, pred, maxDist);
                }
            } else {
                lastDir.remove(id);
            }
            if (mine == null) {
                if (want.isEmpty()) continue;
                mine = new HashMap<>();
                cells.put(id, mine);
            }
            Map<Location, BlockData> send = new HashMap<>();
            for (Location c : want) {
                if (!mine.containsKey(c) || tick % 40 == 0) send.put(c, fake);
                mine.put(c, tick);
            }
            Map<Location, BlockData> restore = new HashMap<>();
            for (var it = mine.entrySet().iterator(); it.hasNext(); ) {
                var en = it.next();
                if (tick - en.getValue() > linger) {
                    Location c = en.getKey();
                    if (c.getWorld() != null && c.isChunkLoaded()) restore.put(c, c.getBlock().getBlockData());
                    it.remove();
                }
            }
            if (!send.isEmpty()) p.sendMultiBlockChange(send);
            if (!restore.isEmpty()) p.sendMultiBlockChange(restore);
            if (mine.isEmpty()) cells.remove(id);
        }
    }

    private void addCluster(Set<Location> out, Player p, Vector dir, double maxDist) {
        Location center = spot(p, dir, maxDist);
        if (center == null) return;
        BoundingBox guard = p.getBoundingBox().expand(1.0, 0.6, 1.0);
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            Block b = center.clone().add(dx, dy, dz).getBlock();
            if (!b.getType().isAir()) continue;
            if (guard.overlaps(BoundingBox.of(b))) continue;
            out.add(b.getLocation());
        }
    }

    /** Where the barrier goes for this look direction, or null for "none" (a real block is there to mine). */
    private Location spot(Player p, Vector dir, double maxDist) {
        Location eye = p.getEyeLocation();
        Vector eyeV = eye.toVector();
        dir = dir.clone().normalize();
        World w = p.getWorld();
        double limit = maxDist;
        boolean need = true;
        RayTraceResult blockHit = w.rayTraceBlocks(eye, dir, maxDist, org.bukkit.FluidCollisionMode.NEVER, false);
        if (blockHit != null && blockHit.getHitBlock() != null) {
            double bd = blockHit.getHitPosition().distance(eyeV);
            if (bd < limit) {
                if (blockHit.getHitBlock().getType().getHardness() == 0f) {
                    // insta-break block (grass, flowers...): the fake goes just in front of it - but never close to the
                    // face: walking through a grass field would otherwise put a barrier right at your nose.
                    limit = bd - 0.3;
                    if (limit < plugin.getConfig().getDouble("hold.min-distance", 3.0)) return null;
                }
                else { limit = bd; need = false; }                                            // the client mines THAT
            }
        }
        RayTraceResult entHit = w.rayTraceEntities(eye, dir, limit, 0.3, e ->
            e != p && pickable(e) && !e.getBoundingBox().contains(eyeV)
            && !(e instanceof Player pl && pl.getGameMode() == org.bukkit.GameMode.SPECTATOR));
        if (entHit != null && entHit.getHitEntity() != null) {
            double ed = entHit.getHitPosition().distance(eyeV);
            if (ed < limit) { limit = ed - 0.6; need = true; }
        }
        if (!need || limit < 1.2) return null;
        return eye.clone().add(dir.clone().multiply(limit)).getBlock().getLocation();
    }

    private static boolean pickable(Entity e) {
        return e instanceof org.bukkit.entity.LivingEntity || e instanceof org.bukkit.entity.Vehicle
            || e instanceof org.bukkit.entity.Hanging || e instanceof org.bukkit.entity.Interaction
            || e instanceof org.bukkit.entity.EnderCrystal;
    }

    /** Every 5 ticks: reach bonus (auto guns), weight (walk speed) and the hidden Mining Fatigue. */
    public void poll() {
        double reachBonus = plugin.getConfig().getDouble("hold.reach-bonus", 6.0);
        boolean fatigue = plugin.getConfig().getBoolean("hold.mining-fatigue", true);
        double perKg = plugin.getConfig().getDouble("weight.speed-per-kg", 0.012);
        boolean noDip = plugin.getConfig().getBoolean("hold.no-dip", true);
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            ItemStack held = p.getInventory().getItemInMainHand();
            GunType gun = registry.gunOf(held);
            boolean holding = gun != null;
            boolean auto = holding && registry.isAuto(held, gun);
            modifier(p.getAttribute(Attribute.BLOCK_INTERACTION_RANGE), reachKey, auto && reachBonus != 0 ? reachBonus : 0,
                AttributeModifier.Operation.ADD_NUMBER);
            double weight = holding ? -Math.min(0.9, gun.weight() * perKg) : 0;
            modifier(p.getAttribute(Attribute.MOVEMENT_SPEED), weightKey, weight, AttributeModifier.Operation.ADD_SCALAR);
            // No "dip": after a click the client lowers the held item while the melee attack cooldown recovers.
            // A huge attack_speed makes that cooldown instant, so the gun stays up and the fire clip is visible.
            modifier(p.getAttribute(Attribute.ATTACK_SPEED), atkKey, holding && noDip ? 1000.0 : 0, AttributeModifier.Operation.ADD_NUMBER);
            PotionEffect mf = p.getPotionEffect(PotionEffectType.MINING_FATIGUE);
            if (holding && fatigue) {
                if (mf == null || mf.getAmplifier() < 3 || mf.getDuration() < 60)
                    p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 200, 3, true, false, false));
            } else if (mf != null && mf.getAmplifier() == 3) {
                p.removePotionEffect(PotionEffectType.MINING_FATIGUE);
            }
        }
    }

    private static void modifier(AttributeInstance attr, NamespacedKey key, double value, AttributeModifier.Operation op) {
        if (attr == null) return;
        AttributeModifier existing = attr.getModifier(key);
        if (existing != null && (value == 0 || existing.getAmount() != value)) attr.removeModifier(key);
        if (value != 0 && (existing == null || existing.getAmount() != value))
            attr.addModifier(new AttributeModifier(key, value, op, EquipmentSlotGroup.ANY));
    }

    /** Restore every client-side cell (disable). */
    public void clearAll() {
        for (var e : cells.entrySet()) {
            Player p = plugin.getServer().getPlayer(e.getKey());
            if (p == null) continue;
            Map<Location, BlockData> restore = new HashMap<>();
            for (Location c : e.getValue().keySet())
                if (c.getWorld() != null && c.isChunkLoaded()) restore.put(c, c.getBlock().getBlockData());
            if (!restore.isEmpty()) p.sendMultiBlockChange(restore);
        }
        cells.clear();
        lastDir.clear();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            modifier(p.getAttribute(Attribute.BLOCK_INTERACTION_RANGE), reachKey, 0, AttributeModifier.Operation.ADD_NUMBER);
            modifier(p.getAttribute(Attribute.MOVEMENT_SPEED), weightKey, 0, AttributeModifier.Operation.ADD_SCALAR);
            modifier(p.getAttribute(Attribute.ATTACK_SPEED), atkKey, 0, AttributeModifier.Operation.ADD_NUMBER);
        }
    }

    public void forget(UUID id) { cells.remove(id); lastDir.remove(id); }
}
