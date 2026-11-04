package fi.alavesa.firearms;

import io.papermc.paper.event.player.PlayerArmSwingEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Trigger, reload, recoil and first-person clips. Firing is driven by ARM SWINGS: one per click normally,
 * one per TICK while LEFT is held on the hold-detect barrier (see HoldDetector) - so an AUTO gun sprays at
 * its fire-rate for as long as the button is down and a SEMI gun fires once per press.
 */
public final class FireController implements Listener {

    private final FirearmsPlugin plugin;
    private final Registry registry;
    private final Ballistics ballistics;
    private final Casings casings;
    private final NamespacedKey capKey, magTypeKey;

    private final Map<UUID, Long> nextShotAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSwing = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> reloadTask = new ConcurrentHashMap<>();
    private final Set<UUID> reloadingMag = ConcurrentHashMap.newKeySet();
    private final Set<UUID> reloadingPump = ConcurrentHashMap.newKeySet();
    private final Map<UUID, List<BukkitTask>> animTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastShotTick = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> drawUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> aimSeen = new ConcurrentHashMap<>();     // last server tick a right-click arrived (the client repeats it every ~4 ticks while held)
    private final Map<UUID, Integer> lastSwingTick = new ConcurrentHashMap<>(); // last server tick a trigger pull (arm swing) arrived
    private final Map<UUID, Integer> rhythmPrevTap = new ConcurrentHashMap<>(); // rhythm guns: the tap before the last one
    private final Set<UUID> rhythmActive = ConcurrentHashMap.newKeySet();      // rhythm guns: taps came fast enough = treated as a held trigger
    private final Set<UUID> aiming = ConcurrentHashMap.newKeySet();          // players holding the sight
    private final Map<UUID, Vector> lastPos = new ConcurrentHashMap<>();      // position a tick ago: is the shooter standing still?
    private final Map<UUID, Integer> slowStamp = new ConcurrentHashMap<>();   // the latest knockback slow-down per player (older removals skip)
    private final NamespacedKey aimKey;

    public FireController(FirearmsPlugin plugin, Registry registry, Ballistics ballistics, Casings casings) {
        this.aimKey = new NamespacedKey(plugin, "aim");
        this.plugin = plugin;
        this.registry = registry;
        this.ballistics = ballistics;
        this.casings = casings;
        this.capKey = new NamespacedKey(plugin, "cap");
        this.magTypeKey = new NamespacedKey(plugin, "magtype");
    }

    // ------------------------------------------------------------------ trigger

    /** Every arm swing while a gun is held = a trigger pull. Cancelled so other players never see the punch. */
    @EventHandler(ignoreCancelled = true)
    public void onSwing(PlayerArmSwingEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player p = event.getPlayer();
        ItemStack item = p.getInventory().getItemInMainHand();
        GunType gun = registry.gunOf(item);
        if (gun == null) return;
        event.setCancelled(true);
        trigger(p, gun, item);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player p = event.getPlayer();
        ItemStack item = p.getInventory().getItemInMainHand();
        GunType gun = registry.gunOf(item);
        boolean left = event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK;
        boolean right = event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK;
        if (gun != null) {
            if (left) {
                // Never cancel a left-click on an AIR block: that is the client mining the hold-detect barrier,
                // and Paper answers a cancelled one by re-sending the real block (which deletes the barrier).
                if (event.getClickedBlock() == null || !event.getClickedBlock().getType().isAir()) event.setCancelled(true);
                trigger(p, gun, item);
            } else if (right) {
                event.setCancelled(true);
                // HOLD to aim: the client re-sends the right-click every ~4 ticks while the button is down (the
                // charged crossbow never starts a continuous use), so "still held" = a click within the last few ticks
                aimSeen.put(p.getUniqueId(), plugin.getServer().getCurrentTick());
                if (!aiming.contains(p.getUniqueId())) enterAim(p, gun, item);
            }
            return;
        }
        MagType mag = registry.magOf(item);
        if (mag != null && right) {
            event.setCancelled(true);
            fillMag(p, item, mag);
        }
    }

    /** A trigger pull arrived (an arm swing / left click): the input side. Rhythm guns read the tap cadence here -
     *  two taps within hold.rhythm-window-ticks = "held", and tick() keeps firing while taps keep coming. */
    private void trigger(Player p, GunType gun, ItemStack item) {
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        int tick = plugin.getServer().getCurrentTick();
        Long last = lastSwing.put(id, now);
        lastSwingTick.put(id, tick);
        boolean auto = registry.isAuto(item, gun);
        if (gun.rhythm() && auto) {
            Integer prev = rhythmPrevTap.put(id, tick);
            int window = Math.max(1, plugin.getConfig().getInt("hold.rhythm-window-ticks", 6));
            if (prev != null && tick - prev > 0 && tick - prev <= window) rhythmActive.add(id);
        }
        if (!auto && last != null && now - last < 150) return;      // semi: ignore the per-tick hold stream
        fire(p, gun, item);
    }

    /** One shot if the gun can fire now (fire rate, draw, reload, rounds). */
    private void fire(Player p, GunType gun, ItemStack item) {
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        if (reloadingMag.contains(id)) return;                       // a magazine swap cannot be interrupted
        Integer du = drawUntil.get(id);
        if (du != null && plugin.getServer().getCurrentTick() < du) return;   // still drawing the gun: no shooting
        Long next = nextShotAt.get(id);
        if (next != null && now < next) return;                      // fire-rate
        if (reloadingPump.contains(id)) cancelReload(p);             // pump reload: shooting interrupts it
        int rounds = registry.rounds(item);
        if (rounds <= 0) {
            nextShotAt.put(id, now + 250);
            p.playSound(p.getLocation(), gun.emptySound() == null || gun.emptySound().isEmpty() ? "minecraft:block.dispenser.fail" : gun.emptySound(), 0.8f, 1.6f);
            return;
        }
        nextShotAt.put(id, now + gun.shotIntervalMs());
        lastShotTick.put(id, (long) plugin.getServer().getCurrentTick());
        registry.setRounds(item, rounds - 1, capacity(item, gun));
        String shot = gun.sound() == null || gun.sound().isEmpty() || gun.sound().startsWith("minecraft:") ? registry.modelSound(gun.model(), "gunshot") : null;
        p.getWorld().playSound(p.getLocation(), shot != null ? shot : gun.sound(), 1.2f, gun.pitch());
        Vector aim = p.getEyeLocation().getDirection();
        for (int i = 0; i < gun.pellets(); i++) ballistics.fire(p, gun, spread(p, gun), 1.0);
        if (registry.casingModel(gun) != null) casings.eject(p, gun, aim);
        recoil(p, gun);
        knockback(p, gun, aim);
        if (gun.shader() && registry.displayMode(gun.model())) {
            double maxRecoil = plugin.getConfig().getDouble("stats.max.recoil", 8);
            plugin.rig().signal(p, Math.max(0.15, Math.min(1, gun.recoil() / Math.max(0.1, maxRecoil))));
        }
        playClip(p, gun, item, "fire");
    }

    /** Knockback on the shooter. Standing still: a short push back against the aim. Moving: NO push at all (a
     *  velocity packet replaces the client's motion and stops a sprint dead) - instead a brief movement-speed
     *  reduction, so the player keeps running, just a little slower for a few ticks. */
    private void knockback(Player p, GunType gun, Vector aim) {
        double k = gun.knockback() * (isAiming(p) ? plugin.getConfig().getDouble("knockback.aim-multiplier", 0.5) : 1.0);
        if (k <= 0) return;
        Vector was = lastPos.get(p.getUniqueId());
        Vector now = p.getLocation().toVector();
        double moved = was == null ? 0 : Math.hypot(now.getX() - was.getX(), now.getZ() - was.getZ());
        boolean still = moved < plugin.getConfig().getDouble("knockback.still-threshold", 0.03);
        if (still && p.isOnGround()) {
            Vector back = new Vector(-aim.getX(), 0, -aim.getZ());
            if (back.lengthSquared() < 1e-6) return;
            back.normalize().multiply(k);
            if (!NmsRecoil.sendMotion(p, back.getX(), 0.02, back.getZ())) p.setVelocity(back);
            return;
        }
        // moving (or airborne): slow, do not shove
        int ticks = Math.max(1, plugin.getConfig().getInt("knockback.slow-ticks", 4));
        double factor = Math.min(0.6, k * plugin.getConfig().getDouble("knockback.slow-per-unit", 2.0));   // 0.08 -> 16 % slower
        if (factor <= 0) return;
        var attr = p.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED);
        if (attr == null) return;
        var key = new org.bukkit.NamespacedKey(plugin, "knockback_slow");
        attr.removeModifier(key);
        attr.addTransientModifier(new org.bukkit.attribute.AttributeModifier(key, -factor, org.bukkit.attribute.AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        int stamp = slowStamp.merge(p.getUniqueId(), 1, Integer::sum);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || slowStamp.getOrDefault(p.getUniqueId(), 0) != stamp) return;   // a newer shot re-armed it
            var a = p.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED);
            if (a != null) a.removeModifier(key);
            slowStamp.remove(p.getUniqueId());
        }, ticks);
    }

    private int capacity(ItemStack item, GunType gun) {
        if (!item.hasItemMeta()) return gun.magazine();
        return item.getItemMeta().getPersistentDataContainer().getOrDefault(capKey, PersistentDataType.INTEGER, gun.magazine());
    }

    /** Aim direction with the gun's spread: tighter when sneaking, looser moving / sprinting / airborne. */
    private Vector spread(Player p, GunType gun) {
        double deg = isAiming(p) ? gun.aimAccuracy() : gun.accuracy();
        Vector v = p.getVelocity();
        boolean moving = v.getX() * v.getX() + v.getZ() * v.getZ() > 0.006;
        if (p.isSprinting()) deg += plugin.getConfig().getDouble("accuracy.sprint-penalty", 2.5);
        else if (moving) deg += plugin.getConfig().getDouble("accuracy.moving-penalty", 1.0);
        if (!p.isOnGround()) deg += plugin.getConfig().getDouble("accuracy.air-penalty", 2.0);
        Vector dir = p.getEyeLocation().getDirection();
        if (deg <= 0) return dir;
        var r = ThreadLocalRandom.current();
        double yaw = Math.toRadians(r.nextGaussian() * deg * 0.5);
        double pitch = Math.toRadians(r.nextGaussian() * deg * 0.5);
        Vector up = new Vector(0, 1, 0);
        Vector right = dir.clone().crossProduct(up);
        if (right.lengthSquared() < 1e-6) right = new Vector(1, 0, 0);
        right.normalize();
        Vector trueUp = right.clone().crossProduct(dir).normalize();
        return dir.clone().add(right.multiply(Math.tan(yaw))).add(trueUp.multiply(Math.tan(pitch))).normalize();
    }

    /** Camera kick via rotation-only position packets, spread over a few ticks (no teleports). */
    private void recoil(Player p, GunType gun) {
        double mult = isAiming(p) ? plugin.getConfig().getDouble("recoil.aim-multiplier", 0.5) : 1.0;
        double up = gun.recoil() * mult;
        double side = (ThreadLocalRandom.current().nextDouble() * 2 - 1) * gun.hRecoil() * mult;
        if (up == 0 && side == 0) return;
        int ticks = Math.max(1, plugin.getConfig().getInt("recoil.ticks", 3));
        double[] weights = ticks == 1 ? new double[]{1} : ticks == 2 ? new double[]{0.65, 0.35} : new double[]{0.5, 0.3, 0.2};
        // camera shake (guns.yml shake): random jitter over the same ticks that adds up to zero - the view wobbles and lands back
        double sh = gun.shake() * mult;
        var r = ThreadLocalRandom.current();
        double[] jy = new double[weights.length + 1], jp = new double[weights.length + 1];
        if (sh > 0) for (int i = 0; i < weights.length; i++) { jy[i] = (r.nextDouble() * 2 - 1) * sh * (1 - (double) i / weights.length); jp[i] = (r.nextDouble() * 2 - 1) * sh * (1 - (double) i / weights.length); }
        for (int i = 0; i <= Math.min(ticks, weights.length); i++) {
            final double w = i < weights.length ? weights[i] : 0;
            final double sy = (i < weights.length ? jy[i] : 0) - (i > 0 ? jy[i - 1] : 0), sp = (i < weights.length ? jp[i] : 0) - (i > 0 ? jp[i - 1] : 0);
            if (w == 0 && sy == 0 && sp == 0) continue;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                float yaw = (float) (side * w + sy), pitch = (float) (-up * w + sp);
                if (!NmsRecoil.sendRotation(p, yaw, pitch)) p.setRotation(p.getLocation().getYaw() + yaw, p.getLocation().getPitch() + pitch);
            }, i);
        }
    }

    // ------------------------------------------------------------------ reload (F)

    @EventHandler
    public void onReloadKey(PlayerSwapHandItemsEvent event) {
        Player p = event.getPlayer();
        ItemStack item = p.getInventory().getItemInMainHand();
        GunType gun = registry.gunOf(item);
        if (gun == null) return;
        event.setCancelled(true);
        Integer du = drawUntil.get(p.getUniqueId());
        if (du != null && plugin.getServer().getCurrentTick() < du) return;   // still drawing: no reload, no inspect
        boolean started = gun.pumpAction() || !gun.usesMag() ? startPumpReload(p, gun, item) : startMagReload(p, gun, item);
        // Second use of the reload key: full gun or nothing to load -> the "inspect" animation.
        if (!started && !reloadingMag.contains(p.getUniqueId()) && !reloadingPump.contains(p.getUniqueId())) {
            playClip(p, gun, item, registry.pickVariant(gun.model(), "inspect", registry.rounds(item) <= 0, healthFraction(p)));
            String snd = registry.modelSound(gun.model(), "inspect");
            if (snd != null) p.getWorld().playSound(p.getLocation(), snd, 1f, 1f);
        }
    }

    private List<String> magIds(GunType gun) {
        List<String> out = new ArrayList<>();
        for (String s : gun.magId().split(",")) if (!s.isBlank()) out.add(s.trim().toLowerCase());
        return out;
    }

    private boolean startMagReload(Player p, GunType gun, ItemStack item) {
        UUID id = p.getUniqueId();
        if (reloadingMag.contains(id) || reloadingPump.contains(id)) return false;
        PlayerInventory inv = p.getInventory();
        List<String> accepted = magIds(gun);
        int bestSlot = -1, bestRounds = -1;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            MagType m = registry.magOf(it);
            if (m == null || !accepted.contains(m.id())) continue;
            int r = registry.rounds(it);
            if (r > bestRounds) { bestRounds = r; bestSlot = i; }
        }
        int current = registry.rounds(item);
        if (bestSlot == -1 || bestRounds <= 0 || bestRounds <= current) {
            p.sendActionBar(Component.text(bestSlot == -1 ? "No " + String.join("/", accepted) + " magazine" : current >= capacity(item, gun) ? "Magazine full" : "No fuller magazine", NamedTextColor.GRAY));
            return false;
        }
        final int slot = bestSlot;
        final String uid = uid(item);
        reloadingMag.add(id);
        boolean customReload = gun.reloadSound() != null && !gun.reloadSound().isEmpty();
        String reloadSnd = customReload ? gun.reloadSound() : registry.modelSound(gun.model(), "reload");
        p.getWorld().playSound(p.getLocation(), reloadSnd != null ? reloadSnd : "minecraft:item.crossbow.loading_start", 1f, reloadSnd != null ? 1f : 1.2f);
        p.sendActionBar(Component.text("Reloading...", NamedTextColor.YELLOW));
        String variant = registry.pickVariant(gun.model(), "reload", current <= 0, healthFraction(p));
        playClip(p, gun, item, variant);
        long ticks = Math.max(1, Math.round(gun.reloadSeconds() * 20));
        int clipTicks = registry.clipTicks(gun.model(), variant);
        if (clipTicks > 0 && plugin.getConfig().getBoolean("reload.use-clip-length", true)) ticks = clipTicks;   // the reload takes as long as its animation
        reloadTask.put(id, plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            reloadingMag.remove(id);
            reloadTask.remove(id);
            if (!p.isOnline()) return;
            ItemStack held = p.getInventory().getItemInMainHand();
            if (!uid.equals(uid(held))) return;                           // switched away: aborted
            ItemStack magItem = p.getInventory().getItem(slot);
            MagType newMag = registry.magOf(magItem);
            if (newMag == null || !accepted.contains(newMag.id())) return;
            int newRounds = registry.rounds(magItem);
            // Take ONE magazine from that stack.
            if (magItem.getAmount() > 1) magItem.setAmount(magItem.getAmount() - 1); else p.getInventory().setItem(slot, null);
            // The magazine that was in the gun comes back with whatever it still had.
            MagType oldMag = registry.mag(held.getItemMeta().getPersistentDataContainer().getOrDefault(magTypeKey, PersistentDataType.STRING, accepted.get(0)));
            if (oldMag != null) {
                ItemStack back = registry.buildMag(oldMag, registry.rounds(held));
                var left = p.getInventory().addItem(back);
                left.values().forEach(l -> p.getWorld().dropItemNaturally(p.getLocation(), l));
            }
            var meta = held.getItemMeta();
            meta.getPersistentDataContainer().set(magTypeKey, PersistentDataType.STRING, newMag.id());
            meta.getPersistentDataContainer().set(capKey, PersistentDataType.INTEGER, newMag.capacity());
            held.setItemMeta(meta);
            registry.setRounds(held, newRounds, newMag.capacity());
            p.getWorld().playSound(p.getLocation(), "minecraft:item.crossbow.loading_end", 1f, 1.3f);
            p.sendActionBar(Component.text(newRounds + " / " + newMag.capacity(), NamedTextColor.GREEN));
        }, ticks));
        return true;
    }

    /** Pump-action / tube guns: one round at a time; shooting interrupts and keeps what was loaded. */
    private boolean startPumpReload(Player p, GunType gun, ItemStack item) {
        UUID id = p.getUniqueId();
        if (reloadingMag.contains(id) || reloadingPump.contains(id)) return false;
        AmmoType ammo = registry.ammo(gun.ammoId());
        if (ammo == null) { p.sendActionBar(Component.text("This gun has no ammo type configured", NamedTextColor.RED)); return false; }
        if (registry.rounds(item) >= gun.magazine()) return false;
        if (takeAmmo(p, ammo, 0) <= 0) { p.sendActionBar(Component.text("No " + plain(ammo.name()), NamedTextColor.GRAY)); return false; }
        final String uid = uid(item);
        reloadingPump.add(id);
        long per = Math.max(2, Math.round(gun.reloadSeconds() * 20));
        reloadTask.put(id, plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline()) { cancelReload(p); return; }
            ItemStack held = p.getInventory().getItemInMainHand();
            if (!uid.equals(uid(held))) { cancelReload(p); return; }
            int r = registry.rounds(held);
            if (r >= gun.magazine() || takeAmmo(p, ammo, 1) <= 0) { cancelReload(p); return; }
            registry.setRounds(held, r + 1, gun.magazine());
            p.getWorld().playSound(p.getLocation(), "minecraft:block.chain.place", 0.8f, 1.6f);
            playClip(p, gun, held, "pump");
            p.sendActionBar(Component.text((r + 1) + " / " + gun.magazine(), NamedTextColor.YELLOW));
            if (r + 1 >= gun.magazine()) cancelReload(p);
        }, per, per));
        return true;
    }

    /** Count (take == 0) or remove `take` loose rounds of this type from the inventory. Returns the count available before taking. */
    private int takeAmmo(Player p, AmmoType ammo, int take) {
        PlayerInventory inv = p.getInventory();
        int have = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            AmmoType a = registry.ammoOf(it);
            if (a == null || !a.id().equals(ammo.id())) continue;
            have += it.getAmount();
            if (take > 0) {
                int n = Math.min(take, it.getAmount());
                if (it.getAmount() - n <= 0) inv.setItem(i, null); else it.setAmount(it.getAmount() - n);
                take -= n;
            }
        }
        return have;
    }

    public void cancelReload(Player p) {
        UUID id = p.getUniqueId();
        BukkitTask t = reloadTask.remove(id);
        if (t != null) t.cancel();
        reloadingMag.remove(id);
        reloadingPump.remove(id);
    }

    /** Right-click a magazine: fill it from loose rounds of its ammo type. */
    private void fillMag(Player p, ItemStack magItem, MagType mag) {
        AmmoType ammo = registry.ammo(mag.ammoId());
        if (ammo == null) return;
        int rounds = registry.rounds(magItem);
        int need = mag.capacity() - rounds;
        if (need <= 0) { p.sendActionBar(Component.text("Magazine full", NamedTextColor.GRAY)); return; }
        int have = takeAmmo(p, ammo, 0);
        if (have <= 0) { p.sendActionBar(Component.text("No " + plain(ammo.name()), NamedTextColor.RED)); return; }
        int n = Math.min(need, have);
        takeAmmo(p, ammo, n);
        ItemStack filled = registry.buildMag(mag, rounds + n);
        if (magItem.getAmount() > 1) {
            magItem.setAmount(magItem.getAmount() - 1);
            var left = p.getInventory().addItem(filled);
            left.values().forEach(l -> p.getWorld().dropItemNaturally(p.getLocation(), l));
        } else {
            p.getInventory().setItemInMainHand(filled);
        }
        p.getWorld().playSound(p.getLocation(), "minecraft:block.chain.hit", 0.8f, 1.8f);
        p.sendActionBar(Component.text((rounds + n) + " / " + mag.capacity(), NamedTextColor.GREEN));
    }

    private static String plain(String legacy) { return legacy.replaceAll("&[0-9a-fk-or]", ""); }

    private String uid(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return "";
        return it.getItemMeta().getPersistentDataContainer().getOrDefault(registry.uidKey, PersistentDataType.STRING, "");
    }

    // ------------------------------------------------------------------ off-hand lock

    /** Guns never go into the off-hand, and nothing goes there while a gun is in the main hand. */
    @EventHandler(ignoreCancelled = true)
    public void onInvClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        boolean offhandSlot = event.getClickedInventory() == p.getInventory() && event.getSlot() == 40;
        boolean holdingGun = registry.gunOf(p.getInventory().getItemInMainHand()) != null;
        boolean movingGun = registry.gunOf(event.getCursor()) != null || registry.gunOf(event.getCurrentItem()) != null
            || (event.getHotbarButton() >= 0 && registry.gunOf(p.getInventory().getItem(event.getHotbarButton())) != null);
        // CREATIVE mode: the client sends "clear the source slot" and "set slot 40" as two separate packets. Cancelling the
        // second one deleted the gun (the first had already gone through) - so let it land and bounce it back next tick.
        if (event instanceof org.bukkit.event.inventory.InventoryCreativeEvent) {
            if (offhandSlot && movingGun) plugin.getServer().getScheduler().runTask(plugin, () -> HoldDetector.bounceOffhandGun(p, registry));
            return;
        }
        if (event.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND && (movingGun || holdingGun || registry.gunOf(p.getInventory().getItemInOffHand()) != null)) { event.setCancelled(true); return; }
        if (offhandSlot && (movingGun || holdingGun)) { event.setCancelled(true); p.sendActionBar(Component.text("Guns are two-handed.", NamedTextColor.GRAY)); }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInvDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (!event.getRawSlots().contains(45)) return;   // 45 = off-hand in the player's own inventory view
        if (registry.gunOf(event.getOldCursor()) != null || registry.gunOf(p.getInventory().getItemInMainHand()) != null) event.setCancelled(true);
    }

    /** Equipping a gun: whatever sits in the off-hand is moved back into the inventory. */
    private void clearOffhand(Player p) {
        ItemStack off = p.getInventory().getItemInOffHand();
        if (off == null || off.getType().isAir()) return;
        p.getInventory().setItemInOffHand(null);
        var left = p.getInventory().addItem(off);
        left.values().forEach(l -> p.getWorld().dropItemNaturally(p.getLocation(), l));
        p.sendActionBar(Component.text("Guns are two-handed - off-hand item put away.", NamedTextColor.GRAY));
    }

    // ------------------------------------------------------------------ guns never punch / break

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (registry.gunOf(event.getPlayer().getInventory().getItemInMainHand()) != null) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMelee(EntityDamageByEntityEvent event) {
        if (Ballistics.applyingDamage) return;   // our own bullet / grenade damage, not a punch
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (event.getDamager() instanceof Player p && registry.gunOf(p.getInventory().getItemInMainHand()) != null)
            event.setCancelled(true);   // the bullet does the damage, never the punch
    }

    // ------------------------------------------------------------------ equip / quit

    @EventHandler
    public void onHeld(PlayerItemHeldEvent event) {
        Player p = event.getPlayer();
        cancelReload(p);
        exitAim(p, null, null, false);
        cancelClip(p);
        plugin.rig().hide(p);
        ItemStack next = p.getInventory().getItem(event.getNewSlot());
        GunType gun = registry.gunOf(next);
        if (gun == null) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            ItemStack held = p.getInventory().getItem(event.getNewSlot());
            if (registry.gunOf(held) == gun) {
                clearOffhand(p);
                refreshArms(p);
                if (registry.displayMode(gun.model())) plugin.rig().show(p, gun, p.getInventory().getItem(event.getNewSlot()));
                boolean customEquip = gun.equipSound() != null && !gun.equipSound().isEmpty();
                p.playSound(p.getLocation(), customEquip ? gun.equipSound() : "minecraft:item.armor.equip_chain", customEquip ? 1f : 0.5f, customEquip ? 1f : 1.5f);
                int[] a = registry.anim(gun.model(), "equip");
                int ticks = gun.equipSeconds() > 0 ? (int) Math.round(gun.equipSeconds() * 20) : a != null ? registry.clipTicks(gun.model(), "equip") : 10;
                drawUntil.put(p.getUniqueId(), plugin.getServer().getCurrentTick() + ticks);
                playClip(p, gun, held, "equip", ticks);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.rig().hide(event.getPlayer());
        cancelReload(event.getPlayer());
        exitAim(event.getPlayer(), null, null, false);
        cancelClip(event.getPlayer());
        UUID id = event.getPlayer().getUniqueId();
        nextShotAt.remove(id); lastSwing.remove(id); lastShotTick.remove(id); drawUntil.remove(id); aimSeen.remove(id);
        lastSwingTick.remove(id); rhythmPrevTap.remove(id); rhythmActive.remove(id);
    }

    // ------------------------------------------------------------------ aiming down the sight

    /** Sneaking counts as aiming too (config aim.sneak-aims), as it always did. */
    public boolean isAiming(Player p) { return aiming.contains(p.getUniqueId()) || (plugin.getConfig().getBoolean("aim.sneak-aims", true) && p.isSneaking()); }

    private static double healthFraction(Player p) {
        var max = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        double m = max == null ? 20.0 : max.getValue();
        return m <= 0 ? 1.0 : Math.max(0, Math.min(1, p.getHealth() / m));
    }

    /** Every tick: release the sight when the right-click stream stopped; keep the client-side aim pose fresh. */
    public void tick() {
        int now = plugin.getServer().getCurrentTick();
        for (Player pl : plugin.getServer().getOnlinePlayers()) {
            GunType held = registry.gunOf(pl.getInventory().getItemInMainHand());
            if (held != null) lastPos.put(pl.getUniqueId(), pl.getLocation().toVector()); else lastPos.remove(pl.getUniqueId());
            if (held != null && registry.displayMode(held.model()) && !plugin.rig().showing(pl)) plugin.rig().show(pl, held, pl.getInventory().getItemInMainHand());   // given / joined holding it
        }
        // Rhythm guns: keep firing while taps keep arriving inside the window (the cadence stands in for a held button).
        int window = Math.max(1, plugin.getConfig().getInt("hold.rhythm-window-ticks", 6));
        for (UUID id : new ArrayList<>(rhythmActive)) {
            Player p = plugin.getServer().getPlayer(id);
            Integer ls = lastSwingTick.get(id);
            ItemStack item = p == null ? null : p.getInventory().getItemInMainHand();
            GunType gun = registry.gunOf(item);
            if (p == null || gun == null || !gun.rhythm() || !registry.isAuto(item, gun) || ls == null || now - ls > window) { rhythmActive.remove(id); rhythmPrevTap.remove(id); continue; }
            fire(p, gun, item);
        }
        int timeout = plugin.getConfig().getInt("aim.hold-timeout-ticks", 7);
        for (UUID id : new ArrayList<>(aiming)) {
            Player p = plugin.getServer().getPlayer(id);
            if (p == null || !p.isOnline()) { aiming.remove(id); aimSeen.remove(id); continue; }
            Integer seen = aimSeen.get(id);
            ItemStack item = p.getInventory().getItemInMainHand();
            GunType gun = registry.gunOf(item);
            // While the trigger is held the client is "mining" the hold barrier and sends NO right-clicks (it does not use
            // items while destroying a block), so the sight used to drop mid-spray: a live trigger stream keeps the aim.
            Integer ls = lastSwingTick.get(id);
            boolean firing = ls != null && now - ls <= timeout;
            if (gun == null || seen == null || (now - seen > timeout && !firing)) { exitAim(p, gun, item, true); continue; }
            if (now % 5 == 0 && !animTasks.containsKey(id)) sendAimView(p, gun, item);   // a server inventory update may have overwritten it
        }
    }

    /** Right-click held: the sight comes up - the zoom is a movement-speed modifier (the client scales its field
     *  of view with that attribute), the pose is the gun's aim model shown CLIENT-SIDE by a slot packet. */
    private void enterAim(Player p, GunType gun, ItemStack item) {
        UUID id = p.getUniqueId();
        Integer du = drawUntil.get(id);
        if (du != null && plugin.getServer().getCurrentTick() < du) return;   // still drawing the gun
        aiming.add(id);
        double mult = plugin.getConfig().getDouble("aim.speed-multiplier", 0.35);
        var attr = p.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED);
        if (attr != null) {
            if (attr.getModifier(aimKey) != null) attr.removeModifier(aimKey);
            attr.addTransientModifier(new org.bukkit.attribute.AttributeModifier(aimKey, mult - 1.0, org.bukkit.attribute.AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        }
        p.playSound(p.getLocation(), plugin.getConfig().getString("aim.sound-in", "minecraft:item.spyglass.use"), 0.6f, 1.3f);
        if (!animTasks.containsKey(id)) sendAimView(p, gun, item);
    }

    /** The aiming pose: the same gun item with the model "<model>_aim_1" (one baked pose, no frames), shown only
     *  to this client; the server's inventory keeps the rest model. Nothing happens for a gun without an aim clip. */
    private void sendAimView(Player p, GunType gun, ItemStack item) {
        if (registry.displayMode(gun.model())) { plugin.rig().aim(p, true); return; }
        if (registry.anim(gun.model(), "aim") == null || !NmsAimView.available()) return;
        ItemStack shown = item.clone();
        registry.setModel(shown, gun.model() + "_aim_1");
        NmsAimView.sendSlot(p, p.getInventory().getHeldItemSlot(), shown);
    }

    /** Leave the sight: zoom and slow-down removed, the real item shown again. gun/item may be null (slot change, quit). */
    private void exitAim(Player p, GunType gun, ItemStack item, boolean sound) {
        UUID id = p.getUniqueId();
        if (!aiming.remove(id)) return;
        aimSeen.remove(id);
        plugin.rig().aim(p, false);
        var attr = p.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED);
        if (attr != null && attr.getModifier(aimKey) != null) attr.removeModifier(aimKey);
        p.updateInventory();   // drops the client-side aim pose
        if (sound && gun != null) p.playSound(p.getLocation(), plugin.getConfig().getString("aim.sound-out", "minecraft:item.spyglass.stop_using"), 0.6f, 1.3f);
    }

    // ------------------------------------------------------------------ first-person clips

    /** Flip the held gun through <model>_<clip>_1..N (frames generated from the .bbmodel animation). */
    private void playClip(Player p, GunType gun, ItemStack item, String clip) { playClip(p, gun, item, clip, 0); }
    private void playClip(Player p, GunType gun, ItemStack item, String clip, int totalTicks) { playClip(p, gun, item, clip, totalTicks, null); }

    /** totalTicks > 0 stretches/compresses the clip to that length (equip-seconds); the last frame holds until then.
     *  then: what to do when the clip ends (null = back to the rest pose, or back into the aim loop while aiming). */
    private void playClip(Player p, GunType gun, ItemStack item, String clip, int totalTicks, Runnable then) {
        int[] a = registry.anim(gun.model(), clip);
        if (a == null) { if (then != null) then.run(); return; }
        if (registry.displayMode(gun.model())) { playOnRig(p, gun, item, clip, totalTicks, then); return; }
        // When each frame starts: the pack's own schedule (frames that did not change were not written, so the
        // previous one holds), else evenly spaced. totalTicks > 0 stretches the whole schedule to that length.
        int length = Math.max(1, registry.clipTicks(gun.model(), clip));
        int[] at = registry.frameStarts(gun.model(), clip);
        if (at == null) { at = new int[a[0]]; for (int i = 0; i < a[0]; i++) at[i] = i * a[1]; }
        double stretch = totalTicks > 0 ? (double) totalTicks / length : 1.0;
        final int holdUntil = Math.max(totalTicks, (int) Math.round(length * stretch));
        cancelClip(p);
        int slot = p.getInventory().getHeldItemSlot();
        String uid = uid(item);
        List<BukkitTask> tasks = new ArrayList<>();
        for (int i = 1; i <= a[0]; i++) {
            final int frame = i;
            tasks.add(plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                ItemStack cur = p.getInventory().getItem(slot);
                if (!uid.equals(uid(cur)) || p.getInventory().getHeldItemSlot() != slot) { cancelClip(p); return; }
                registry.setModel(cur, gun.model() + "_" + clip + "_" + frame);
            }, Math.round(at[i - 1] * stretch)));
        }
        // Sound keyframes from the .bbmodel (effects animator -> sounds.json by /firearms pack).
        for (String snd : registry.animSounds(gun.model(), clip)) {
            int colon = snd.indexOf(':');
            if (colon <= 0) continue;
            long sndAt; try { sndAt = Long.parseLong(snd.substring(0, colon)); } catch (NumberFormatException e) { continue; }
            String id = snd.substring(colon + 1);
            tasks.add(plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline() && uid.equals(uid(p.getInventory().getItem(slot)))) p.getWorld().playSound(p.getLocation(), id, 1f, 1f);
            }, Math.round(sndAt * stretch)));
        }
        tasks.add(plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            animTasks.remove(p.getUniqueId());
            ItemStack cur = p.getInventory().getItem(slot);
            if (!uid.equals(uid(cur))) return;
            if (then != null) { then.run(); return; }
            registry.setModel(cur, gun.model());
            if (aiming.contains(p.getUniqueId())) sendAimView(p, gun, cur);   // fire/reload while aiming: back onto the sight
        }, holdUntil));
        animTasks.put(p.getUniqueId(), tasks);
    }

    /** Display mode: the rig plays the clip (one pose per tick, blended by the client); the sound keyframes and the
     *  end-of-clip bookkeeping stay here so reloads, aiming and cancelling work exactly as with frames. */
    private void playOnRig(Player p, GunType gun, ItemStack item, String clip, int totalTicks, Runnable then) {
        int length = Math.max(1, registry.clipTicks(gun.model(), clip));
        double stretch = totalTicks > 0 ? (double) totalTicks / length : 1.0;
        final int holdUntil = Math.max(totalTicks, (int) Math.round(length * stretch));
        cancelClip(p);
        if (!plugin.rig().showing(p)) plugin.rig().show(p, gun, item);
        int slot = p.getInventory().getHeldItemSlot();
        String uid = uid(item);
        List<BukkitTask> tasks = new ArrayList<>();
        plugin.rig().play(p, clip, totalTicks, null);
        for (String snd : registry.animSounds(gun.model(), clip)) {
            int colon = snd.indexOf(':');
            if (colon <= 0) continue;
            long sndAt; try { sndAt = Long.parseLong(snd.substring(0, colon)); } catch (NumberFormatException e) { continue; }
            String id = snd.substring(colon + 1);
            tasks.add(plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline() && uid.equals(uid(p.getInventory().getItem(slot)))) p.getWorld().playSound(p.getLocation(), id, 1f, 1f);
            }, Math.round(sndAt * stretch)));
        }
        tasks.add(plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            animTasks.remove(p.getUniqueId());
            if (then != null) then.run();
        }, holdUntil));
        animTasks.put(p.getUniqueId(), tasks);
    }

    /** Put the player's skin colours on every gun they carry (the first-person arms). Safe to call often. */
    public void refreshArms(Player p) {
        if (!ArmSkin.ready(p.getUniqueId())) { ArmSkin.load(plugin, p, () -> refreshArms(p)); return; }
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            GunType g = registry.gunOf(it);
            if (g == null) continue;
            ItemStack migrated = registry.migrateGun(it, g);   // old brick-based guns -> crossbow guns
            if (migrated != null) { registry.applySkin(migrated, g, p); inv.setItem(i, migrated); continue; }
            if (registry.applySkin(it, g, p)) inv.setItem(i, it);
        }
    }

    /** The gun is a charged crossbow for its pose only: it must never actually loose the bolt. */
    @EventHandler(ignoreCancelled = true)
    public void onBowShoot(org.bukkit.event.entity.EntityShootBowEvent event) {
        if (registry.gunOf(event.getBow()) != null) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCrossbowLoad(io.papermc.paper.event.entity.EntityLoadCrossbowEvent event) {
        if (registry.gunOf(event.getCrossbow()) != null) event.setCancelled(true);
    }

    private void cancelClip(Player p) {
        plugin.rig().stopClip(p);
        List<BukkitTask> t = animTasks.remove(p.getUniqueId());
        if (t == null) return;
        t.forEach(BukkitTask::cancel);
        for (ItemStack it : p.getInventory().getContents()) {
            GunType g = registry.gunOf(it);
            if (g != null && !registry.model(it).equals(g.model())) registry.setModel(it, g.model());
        }
    }

    public void shutdown() {
        for (Player p : plugin.getServer().getOnlinePlayers()) { cancelReload(p); cancelClip(p); }
    }

    static List<String> clips() { return Arrays.asList("fire", "reload", "equip", "pump", "inspect", "aim"); }
}
