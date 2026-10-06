package fi.alavesa.firearms;

import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Set;

/**
 * Teleport-free camera recoil: sends a {@code ClientboundPlayerPositionPacket} that only nudges
 * the view rotation (relative X_ROT / Y_ROT) with a zero position delta, so the client rotates the
 * camera without the server moving the player at all - no teleport, no events, no rubber-band.
 *
 * All NMS is reached by reflection so the plugin still compiles against paper-api; if any class or
 * member is missing on the running server it disables itself and callers fall back to a teleport.
 */
public final class NmsRecoil {

    private static boolean ok = false;
    private static boolean fovOk = false;
    private static Constructor<?> pmrCtor;   // PositionMoveRotation(Vec3, Vec3, float yRot, float xRot)
    private static Constructor<?> pktCtor;   // ClientboundPlayerPositionPacket(int, PositionMoveRotation, Set)
    private static Constructor<?> vec3Ctor;  // Vec3(double, double, double)
    private static Constructor<?> motionCtor;// ClientboundSetEntityMotionPacket(int, Vec3)
    private static Object vec3Zero;
    private static Object relativesSet;      // EnumSet {X, Y, Z, X_ROT, Y_ROT}
    private static Object speedHolder;       // MobEffects.SPEED  (Holder<MobEffect>)
    private static Constructor<?> effectInstanceCtor;  // MobEffectInstance(Holder,int,int,bool,bool,bool)
    private static Constructor<?> effectPktCtor;       // ClientboundUpdateMobEffectPacket(int, inst, bool)
    private static Class<?> packetClass;
    private static Method getHandle;
    private static Field connectionField;
    private static Method sendMethod;

    private NmsRecoil() { }

    static {
        try {
            Class<?> vec3 = Class.forName("net.minecraft.world.phys.Vec3");
            vec3Zero = vec3.getField("ZERO").get(null);
            vec3Ctor = vec3.getConstructor(double.class, double.class, double.class);
            Class<?> motion = Class.forName("net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket");
            motionCtor = motion.getConstructor(int.class, vec3);
            Class<?> pmr = Class.forName("net.minecraft.world.entity.PositionMoveRotation");
            pmrCtor = pmr.getConstructor(vec3, vec3, float.class, float.class);
            // EVERYTHING relative (Relative.ALL): position, DELTA (velocity) and rotation are all
            // treated as offsets. Since we only put values in yRot/xRot and zero everywhere else,
            // the client adds 0 to position AND to velocity, and adds the rotation deltas. CRUCIAL:
            // if DELTA_X/Y/Z are NOT relative, the packet's zero deltaMovement is applied absolutely
            // and ZEROES the player's velocity - which, at 30 packets/shot, slowed players to a
            // crawl and froze them mid-air. That was THE bug.
            Class<?> rel = Class.forName("net.minecraft.world.entity.Relative");
            relativesSet = rel.getField("ALL").get(null);
            packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket");
            pktCtor = packetClass.getConstructor(int.class, pmr, Set.class);
            ok = true;
        } catch (Throwable t) {
            ok = false;
        }
        // FOV kick: a CLIENT-ONLY Speed effect sent to the shooter alone widens their FOV. The
        // server never gets it, so it does not change server-side movement. Reflected separately so
        // a failure here doesn't disable the camera pan above.
        try {
            Class<?> holder = Class.forName("net.minecraft.core.Holder");
            speedHolder = Class.forName("net.minecraft.world.effect.MobEffects").getField("SPEED").get(null);
            effectInstanceCtor = Class.forName("net.minecraft.world.effect.MobEffectInstance")
                .getConstructor(holder, int.class, int.class, boolean.class, boolean.class, boolean.class);
            effectPktCtor = Class.forName("net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket")
                .getConstructor(int.class, Class.forName("net.minecraft.world.effect.MobEffectInstance"), boolean.class);
            fovOk = true;
        } catch (Throwable t) {
            fovOk = false;
        }
    }

    public static boolean available() { return ok; }
    public static boolean fovAvailable() { return fovOk; }

    /** Send a CLIENT-ONLY Speed effect to just this player so their FOV widens (the recoil FOV kick),
     *  while the server never applies it. */
    public static boolean sendClientSpeed(Player player, int amplifier, int durationTicks) {
        if (!fovOk) return false;
        try {
            Object connection = connection(getHandle(player));
            Object inst = effectInstanceCtor.newInstance(speedHolder, durationTicks, amplifier, false, false, false);
            Object packet = effectPktCtor.newInstance(player.getEntityId(), inst, false);
            send(connection, packet);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Add {@code yawDelta} / {@code pitchDelta} degrees to the player's view via the position
     *  packet (relative rotation only). Returns false if it couldn't send (caller should fall back). */
    public static boolean sendRotation(Player player, float yawDelta, float pitchDelta) {
        if (!ok) return false;
        try {
            Object handle = getHandle(player);
            Object connection = connection(handle);
            Object pmr = pmrCtor.newInstance(vec3Zero, vec3Zero, yawDelta, pitchDelta);
            Object packet = pktCtor.newInstance(0, pmr, relativesSet);
            send(connection, packet);
            return true;
        } catch (Throwable t) {
            ok = false;   // stop trying; caller falls back to teleport
            return false;
        }
    }

    /** Push the player's own client entity with a one-off velocity (the recoil "kick") - a motion
     *  packet, so the camera lurches without the server changing the player's movement speed. */
    public static boolean sendMotion(Player player, double x, double y, double z) {
        if (!ok) return false;
        try {
            Object connection = connection(getHandle(player));
            Object vec = vec3Ctor.newInstance(x, y, z);
            Object packet = motionCtor.newInstance(player.getEntityId(), vec);
            send(connection, packet);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object getHandle(Player p) throws Exception {
        if (getHandle == null) getHandle = p.getClass().getMethod("getHandle");
        return getHandle.invoke(p);
    }

    private static Object connection(Object handle) throws Exception {
        if (connectionField == null) {
            connectionField = Class.forName("net.minecraft.server.level.ServerPlayer").getField("connection");
        }
        return connectionField.get(handle);
    }

    private static void send(Object connection, Object packet) throws Exception {
        if (sendMethod == null) {
            Class<?> base = Class.forName("net.minecraft.network.protocol.Packet");
            for (Class<?> c = connection.getClass(); c != null && sendMethod == null; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals("send") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(base)) {
                        m.setAccessible(true);
                        sendMethod = m;
                        break;
                    }
                }
            }
        }
        sendMethod.invoke(connection, packet);
    }
}
