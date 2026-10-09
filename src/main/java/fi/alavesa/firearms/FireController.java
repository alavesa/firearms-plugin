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
    private final Map<UUID, Long> modeToggleAt = new ConcurrentHashMap<>();   // server tick until which the gun is still being drawn

    public FireController(FirearmsPlugin plugin, Registry registry, Ballistics ballistics, Casings casings) {
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
                if (gun.switchable()) {
                    long now = System.currentTimeMillis();
                    Long lastT = modeToggleAt.put(p.getUniqueId(), now);
                    if (lastT == null || now - lastT > 300) {   // one toggle per click (right-click fires twice: air + block)
                        String mode = registry.toggleMode(item, gun);
                        p.sendActionBar(Component.text("Fire mode: " + mode, NamedTextColor.YELLOW));
                        p.playSound(p.getLocation(), "minecraft:block.lever.click", 0.7f, mode.equals("AUTO") ? 1.6f : 1.2f);
                    }
                }
            }
            return;
        }
        MagType mag = registry.magOf(item);
        if (mag != null && right) {
            event.setCancelled(true);
            fillMag(p, item, mag);
        }
    }

    private void trigger(Player p, GunType gun, ItemStack item) {
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastSwing.put(id, now);
        if (reloadingMag.contains(id)) return;                       // a magazine swap cannot be interrupted
        Integer du = drawUntil.get(id);
        if (du != null && plugin.getServer().getCurrentTick() < du) return;   // still drawing the gun: no shooting
        if (!registry.isAuto(item, gun) && last != null && now - last < 150) return; // semi: ignore the per-tick hold stream
        Long next = nextShotAt.get(id);
        if (next != null && now < next) return;                      // fire-rate
        if (reloadingPump.contains(id)) cancelReload(p);             // pump reload: shooting interrupts it
        int rounds = registry.rounds(item);
        if (rounds <= 0) {
            nextShotAt.put(id, now + 250);
            p.playSound(p.getLocation(), "minecraft:block.dispenser.fail", 0.8f, 1.6f);
            return;
        }
        nextShotAt.put(id, now + gun.shotIntervalMs());
        lastShotTick.put(id, (long) plugin.getServer().getCurrentTick());
        registry.setRounds(item, rounds - 1, capacity(item, gun));
        p.getWorld().playSound(p.getLocation(), gun.sound(), 1.2f, gun.pitch());
        Vector aim = p.getEyeLocation().getDirection();
        for (int i = 0; i < gun.pellets(); i++) ballistics.fire(p, gun, spread(p, gun), 1.0);
        if (registry.casingModel(gun) != null) casings.eject(p, gun, aim);
        recoil(p, gun);
        playClip(p, gun, item, "fire");
    }

    private int capacity(ItemStack item, GunType gun) {
        if (!item.hasItemMeta()) return gun.magazine();
        return item.getItemMeta().getPersistentDataContainer().getOrDefault(capKey, PersistentDataType.INTEGER, gun.magazine());
    }

    /** Aim direction with the gun's spread: tighter when sneaking, looser moving / sprinting / airborne. */
    private Vector spread(Player p, GunType gun) {
        double deg = p.isSneaking() ? gun.aimAccuracy() : gun.accuracy();
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
        double mult = p.isSneaking() ? plugin.getConfig().getDouble("recoil.aim-multiplier", 0.5) : 1.0;
        double up = gun.recoil() * mult;
        double side = (ThreadLocalRandom.current().nextDouble() * 2 - 1) * gun.hRecoil() * mult;
        if (up == 0 && side == 0) return;
        int ticks = Math.max(1, plugin.getConfig().getInt("recoil.ticks", 3));
        double[] weights = ticks == 1 ? new double[]{1} : ticks == 2 ? new double[]{0.65, 0.35} : new double[]{0.5, 0.3, 0.2};
        for (int i = 0; i < Math.min(ticks, weights.length); i++) {
            final double w = weights[i];
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                float yaw = (float) (side * w), pitch = (float) (-up * w);
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
        boolean started = gun.pumpAction() || !gun.usesMag() ? startPumpReload(p, gun, item) : startMagReload(p, gun, item);
        // Second use of the reload key: full gun or nothing to load -> the "inspect" animation.
        if (!started && !reloadingMag.contains(p.getUniqueId()) && !reloadingPump.contains(p.getUniqueId())) playClip(p, gun, item, "inspect");
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
        p.getWorld().playSound(p.getLocation(), "minecraft:item.crossbow.loading_start", 1f, 1.2f);
        p.sendActionBar(Component.text("Reloading...", NamedTextColor.YELLOW));
        playClip(p, gun, item, "reload");
        long ticks = Math.max(1, Math.round(gun.reloadSeconds() * 20));
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
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (event.getDamager() instanceof Player p && registry.gunOf(p.getInventory().getItemInMainHand()) != null)
            event.setCancelled(true);   // the bullet does the damage, never the punch
    }

    // ------------------------------------------------------------------ equip / quit

    @EventHandler
    public void onHeld(PlayerItemHeldEvent event) {
        Player p = event.getPlayer();
        cancelReload(p);
        cancelClip(p);
        ItemStack next = p.getInventory().getItem(event.getNewSlot());
        GunType gun = registry.gunOf(next);
        if (gun == null) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            ItemStack held = p.getInventory().getItem(event.getNewSlot());
            if (registry.gunOf(held) == gun) {
                clearOffhand(p);
                refreshArms(p);
                p.playSound(p.getLocation(), "minecraft:item.armor.equip_chain", 0.5f, 1.5f);
                int[] a = registry.anim(gun.model(), "equip");
                int ticks = gun.equipSeconds() > 0 ? (int) Math.round(gun.equipSeconds() * 20) : a != null ? a[0] * a[1] : 10;
                drawUntil.put(p.getUniqueId(), plugin.getServer().getCurrentTick() + ticks);
                playClip(p, gun, held, "equip", ticks);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancelReload(event.getPlayer());
        cancelClip(event.getPlayer());
        UUID id = event.getPlayer().getUniqueId();
        nextShotAt.remove(id); lastSwing.remove(id); lastShotTick.remove(id); drawUntil.remove(id);
    }

    // ------------------------------------------------------------------ first-person clips

    /** Flip the held gun through <model>_<clip>_1..N (frames generated from the .bbmodel animation). */
    private void playClip(Player p, GunType gun, ItemStack item, String clip) { playClip(p, gun, item, clip, 0); }

    /** totalTicks > 0 stretches/compresses the clip to that length (equip-seconds); the last frame holds until then. */
    private void playClip(Player p, GunType gun, ItemStack item, String clip, int totalTicks) {
        int[] a = registry.anim(gun.model(), clip);
        if (a == null) return;
        if (totalTicks > 0) a = new int[]{ a[0], Math.max(1, (int) Math.round((double) totalTicks / a[0])) };
        final int holdUntil = Math.max(totalTicks, a[0] * a[1]);
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
            }, (long) (i - 1) * a[1]));
        }
        // Sound keyframes from the .bbmodel (effects animator -> sounds.json by /firearms pack).
        for (String snd : registry.animSounds(gun.model(), clip)) {
            int colon = snd.indexOf(':');
            if (colon <= 0) continue;
            long at; try { at = Long.parseLong(snd.substring(0, colon)); } catch (NumberFormatException e) { continue; }
            String id = snd.substring(colon + 1);
            tasks.add(plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline() && uid.equals(uid(p.getInventory().getItem(slot)))) p.getWorld().playSound(p.getLocation(), id, 1f, 1f);
            }, at));
        }
        tasks.add(plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            animTasks.remove(p.getUniqueId());
            ItemStack cur = p.getInventory().getItem(slot);
            if (uid.equals(uid(cur))) registry.setModel(cur, gun.model());
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
            if (g != null && registry.applySkin(it, g, p)) inv.setItem(i, it);
        }
    }

    private void cancelClip(Player p) {
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

    static List<String> clips() { return Arrays.asList("fire", "reload", "equip", "pump", "inspect"); }
}
