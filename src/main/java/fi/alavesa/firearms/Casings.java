package fi.alavesa.firearms;

import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Ejected shell casings: an ItemDisplay per shot thrown out of the ejection port at a random angle, tumbling
 *  in the air (interpolated rotation), falling with gravity, landing and lying there for a while. */
public final class Casings {

    private final FirearmsPlugin plugin;
    private final Registry registry;
    private final List<Shell> shells = new ArrayList<>();

    private static final class Shell {
        ItemDisplay d; Location pos; Vector vel; Vector spinAxis; float spin, angle; int age; boolean landed; int landedAt;
    }

    public Casings(FirearmsPlugin plugin, Registry registry) { this.plugin = plugin; this.registry = registry; }

    public void eject(Player p, GunType gun, Vector dir) {
        if (!plugin.getConfig().getBoolean("casings.enabled", true)) return;
        var r = ThreadLocalRandom.current();
        Location eye = p.getEyeLocation();
        Vector fwd = dir.clone().normalize();
        Vector right = fwd.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1e-6) right = new Vector(1, 0, 0);
        right.normalize();
        Vector up = right.clone().crossProduct(fwd).normalize();
        double[] e = gun.eject() == null ? new double[]{0.25, -0.15, 0.4} : gun.eject();
        Location at = eye.clone().add(right.clone().multiply(e[0])).add(up.clone().multiply(e[1])).add(fwd.clone().multiply(e[2]));
        if (!at.getBlock().getType().isAir()) at = eye.clone();
        // random ejection: mostly to the right, a bit up and back, with scatter
        Vector vel = right.clone().multiply(0.10 + r.nextDouble() * 0.08)
            .add(up.clone().multiply(0.12 + r.nextDouble() * 0.08))
            .add(fwd.clone().multiply(-0.04 + r.nextDouble() * 0.06))
            .add(new Vector((r.nextDouble() - 0.5) * 0.04, 0, (r.nextDouble() - 0.5) * 0.04));
        float scale = (float) plugin.getConfig().getDouble("casings.scale", 0.35);
        Shell s = new Shell();
        s.pos = at; s.vel = vel;
        s.spinAxis = new Vector(r.nextDouble() - 0.5, r.nextDouble() - 0.5, r.nextDouble() - 0.5).normalize();
        s.spin = (float) Math.toRadians(25 + r.nextDouble() * 40);
        s.angle = (float) (r.nextDouble() * Math.PI * 2);
        s.d = at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(registry.buildCasing(registry.casingModel(gun)));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setPersistent(false);
            d.setTeleportDuration(1);
            d.setInterpolationDuration(1);
            d.setInterpolationDelay(0);
            d.setBrightness(new Display.Brightness(10, 15));
            d.setTransformation(new Transformation(new Vector3f(), rot(s.spinAxis, s.angle), new Vector3f(scale, scale, scale), new Quaternionf()));
            d.addScoreboardTag("firearms_casing");
        });
        shells.add(s);
        int max = plugin.getConfig().getInt("casings.max", 300);
        while (shells.size() > max) { Shell old = shells.remove(0); if (old.d.isValid()) old.d.remove(); }
    }

    private static Quaternionf rot(Vector axis, float angle) {
        return new Quaternionf().rotationAxis(angle, (float) axis.getX(), (float) axis.getY(), (float) axis.getZ());
    }

    public void tick() {
        int keep = plugin.getConfig().getInt("casings.seconds", 8) * 20;
        float scale = (float) plugin.getConfig().getDouble("casings.scale", 0.35);
        for (Iterator<Shell> it = shells.iterator(); it.hasNext(); ) {
            Shell s = it.next();
            s.age++;
            if (!s.d.isValid() || (s.landed && s.age - s.landedAt > keep) || s.age > keep + 100) { if (s.d.isValid()) s.d.remove(); it.remove(); continue; }
            if (s.landed) continue;
            s.vel.setY(s.vel.getY() - 0.045);
            s.vel.multiply(0.98);
            Location next = s.pos.clone().add(s.vel);
            if (!next.getBlock().getType().isAir() || !next.isChunkLoaded()) {
                // landed: sit on top of the block it hit, stop tumbling, play a tiny tink
                Location rest = s.pos.clone();
                rest.setY(Math.floor(rest.getY()) + 0.03);
                if (!rest.getBlock().getType().isAir()) rest.setY(Math.floor(s.pos.getY()) + 1.03);
                s.landed = true; s.landedAt = s.age;
                s.d.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotationX((float) Math.toRadians(90)).mul(new Quaternionf().rotationY(s.angle)), new Vector3f(scale, scale, scale), new Quaternionf()));
                s.d.teleport(rest);
                rest.getWorld().playSound(rest, "minecraft:block.chain.fall", 0.25f, 1.9f + ThreadLocalRandom.current().nextFloat() * 0.3f);
                continue;
            }
            s.pos = next;
            s.angle += s.spin;
            s.d.setInterpolationDelay(0);
            s.d.setTransformation(new Transformation(new Vector3f(), rot(s.spinAxis, s.angle), new Vector3f(scale, scale, scale), new Quaternionf()));
            s.d.teleport(next);
        }
    }

    public void clearAll() {
        for (Shell s : shells) if (s.d.isValid()) s.d.remove();
        shells.clear();
        for (var w : plugin.getServer().getWorlds())
            for (var e : w.getEntitiesByClass(ItemDisplay.class))
                if (e.getScoreboardTags().contains("firearms_casing")) e.remove();
    }
}
