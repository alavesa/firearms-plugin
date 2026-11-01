package fi.alavesa.firearms;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Shows a player a different item in their own hotbar slot WITHOUT changing the server's inventory: a
 * ClientboundContainerSetSlotPacket for container 0 (the player inventory) with the current state id. The aim
 * pose of a gun is one such fake item (custom model data = the gun's aim pose model), re-sent while the player
 * aims and dropped by a plain inventory refresh when they stop - no animation frames, nothing in the pack but
 * one model per gun. Any real inventory change from the server overrides it, so it is re-sent every few ticks.
 */
public final class NmsAimView {
    private static boolean ok;
    private static Method asNms;
    private static Constructor<?> pktCtor;
    private static Method getStateId;
    private static java.lang.reflect.Field menuField;

    private NmsAimView() { }

    static {
        try {
            asNms = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack").getMethod("asNMSCopy", ItemStack.class);
            Class<?> nmsStack = Class.forName("net.minecraft.world.item.ItemStack");
            pktCtor = Class.forName("net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket").getConstructor(int.class, int.class, int.class, nmsStack);
            Class<?> sp = Class.forName("net.minecraft.server.level.ServerPlayer");
            menuField = sp.getField("inventoryMenu");
            getStateId = Class.forName("net.minecraft.world.inventory.AbstractContainerMenu").getMethod("getStateId");
            ok = true;
        } catch (Throwable t) { ok = false; }
    }

    public static boolean available() { return ok; }

    /** Show {@code shown} in the player's hotbar slot {@code hotbar} (0-8), client side only. */
    public static boolean sendSlot(Player p, int hotbar, ItemStack shown) {
        if (!ok) return false;
        try {
            Object handle = NmsRecoil.getHandle(p);
            Object menu = menuField.get(handle);
            int stateId = (int) getStateId.invoke(menu);
            Object packet = pktCtor.newInstance(0, stateId, 36 + hotbar, asNms.invoke(null, shown));
            NmsRecoil.send(NmsRecoil.connection(handle), packet);
            return true;
        } catch (Throwable t) { ok = false; return false; }
    }
}
