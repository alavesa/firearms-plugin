package fi.alavesa.firearms;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Client-side item_display entities, by packets only: nothing is added to the world, nobody else sees them.
 * Everything goes through reflection on the server's own (unobfuscated, 26.x) classes, so the plugin still
 * compiles against the API jar. {@link #available()} says whether the server's classes matched.
 */
final class NmsDisplay {
    private static boolean ok;
    private static String problem = "not initialised";
    private static Constructor<?> itemDisplayCtor, addEntityCtor, setDataCtor, passengersCtor, removeCtor, transformationCtor, matrixCtor;
    private static Object itemDisplayType, billboardCenter, contextFixed;
    private static Method setTransformation, setInterpDuration, setInterpDelay, setBillboard, setViewRange, setItemStack, setItemTransform,
        getId, getEntityData, nonDefaultValues, packDirty, setPos, asNms, worldHandle, matrixSet;

    static {
        try {
            Class<?> entity = Class.forName("net.minecraft.world.entity.Entity");
            Class<?> display = Class.forName("net.minecraft.world.entity.Display");
            Class<?> itemDisplay = Class.forName("net.minecraft.world.entity.Display$ItemDisplay");
            Class<?> entityType = Class.forName("net.minecraft.world.entity.EntityType");
            Class<?> level = Class.forName("net.minecraft.world.level.Level");
            Class<?> nmsStack = Class.forName("net.minecraft.world.item.ItemStack");
            Class<?> transformation = Class.forName("com.mojang.math.Transformation");
            Class<?> matrix4f = Class.forName("org.joml.Matrix4f");
            Class<?> matrix4fc = Class.forName("org.joml.Matrix4fc");
            Class<?> billboard = Class.forName("net.minecraft.world.entity.Display$BillboardConstraints");
            Class<?> context = Class.forName("net.minecraft.world.item.ItemDisplayContext");
            Class<?> synched = Class.forName("net.minecraft.network.syncher.SynchedEntityData");
            itemDisplayType = null;
            for (Field f : entityType.getFields()) if (f.getName().equals("ITEM_DISPLAY")) itemDisplayType = f.get(null);
            if (itemDisplayType == null) for (Field f : entityType.getFields()) if (f.getGenericType().getTypeName().contains("Display$ItemDisplay")) itemDisplayType = f.get(null);
            if (itemDisplayType == null) { Object opt = entityType.getMethod("byString", String.class).invoke(null, "item_display"); if (opt instanceof java.util.Optional<?> o && o.isPresent()) itemDisplayType = o.get(); }
            if (itemDisplayType == null) throw new IllegalStateException("EntityType.ITEM_DISPLAY not found");
            itemDisplayCtor = itemDisplay.getConstructor(entityType, level);
            billboardCenter = billboard.getField("CENTER").get(null);
            contextFixed = context.getField("FIXED").get(null);
            setTransformation = display.getMethod("setTransformation", transformation);
            setInterpDuration = display.getMethod("setTransformationInterpolationDuration", int.class);
            setInterpDelay = display.getMethod("setTransformationInterpolationDelay", int.class);
            setBillboard = display.getMethod("setBillboardConstraints", billboard);
            setViewRange = display.getMethod("setViewRange", float.class);
            setItemStack = itemDisplay.getMethod("setItemStack", nmsStack);
            setItemTransform = itemDisplay.getMethod("setItemTransform", context);
            getId = entity.getMethod("getId");
            getEntityData = entity.getMethod("getEntityData");
            setPos = entity.getMethod("setPos", double.class, double.class, double.class);
            nonDefaultValues = synched.getMethod("getNonDefaultValues");
            packDirty = synched.getMethod("packDirty");
            transformationCtor = transformation.getConstructor(matrix4fc);
            matrixCtor = matrix4f.getConstructor();
            matrixSet = matrix4f.getMethod("set", float.class, float.class, float.class, float.class, float.class, float.class, float.class, float.class, float.class, float.class, float.class, float.class, float.class, float.class, float.class, float.class);
            addEntityCtor = Class.forName("net.minecraft.network.protocol.game.ClientboundAddEntityPacket").getConstructor(entity, int.class, Class.forName("net.minecraft.core.BlockPos"));
            setDataCtor = Class.forName("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket").getConstructor(int.class, List.class);
            passengersCtor = Class.forName("net.minecraft.network.protocol.game.ClientboundSetPassengersPacket").getConstructor(entity);
            removeCtor = Class.forName("net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket").getConstructor(int[].class);
            asNms = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack").getMethod("asNMSCopy", ItemStack.class);
            ok = true; problem = "";
        } catch (Throwable t) {
            ok = false; problem = t.toString();
        }
    }

    static boolean available() { return ok; }
    static String problem() { return problem; }

    /** One client-side item_display: the server-side entity object exists only to build packets. */
    static final class Part {
        final Object entity; final int id;
        Part(Object entity, int id) { this.entity = entity; this.id = id; }
    }

    /** Make an item_display showing `stack` (fixed context, billboard CENTER = camera-aligned), not in the world. */
    static Part create(Player viewer, ItemStack stack, float viewRange) throws Exception {
        Object world = viewer.getWorld();
        if (worldHandle == null) worldHandle = world.getClass().getMethod("getHandle");
        Object level = worldHandle.invoke(world);
        Object e = itemDisplayCtor.newInstance(itemDisplayType, level);
        setItemStack.invoke(e, asNms.invoke(null, stack));
        setItemTransform.invoke(e, contextFixed);
        setBillboard.invoke(e, billboardCenter);
        setViewRange.invoke(e, viewRange);
        var loc = viewer.getLocation();
        setPos.invoke(e, loc.getX(), loc.getY(), loc.getZ());
        return new Part(e, (Integer) getId.invoke(e));
    }

    /** Set the entity's transformation (a 4x4, row-major) and how many ticks the client blends into it. */
    static void transform(Part p, double[][] m, int durationTicks) throws Exception {
        Object mat = matrixCtor.newInstance();
        // joml set(...) takes column-major arguments m00 m01 m02 m03 (first COLUMN) ...
        matrixSet.invoke(mat,
            (float) m[0][0], (float) m[1][0], (float) m[2][0], (float) m[3][0],
            (float) m[0][1], (float) m[1][1], (float) m[2][1], (float) m[3][1],
            (float) m[0][2], (float) m[1][2], (float) m[2][2], (float) m[3][2],
            (float) m[0][3], (float) m[1][3], (float) m[2][3], (float) m[3][3]);
        setTransformation.invoke(p.entity, transformationCtor.newInstance(mat));
        setInterpDuration.invoke(p.entity, durationTicks);
        setInterpDelay.invoke(p.entity, 0);
    }

    static void item(Part p, ItemStack stack) throws Exception { setItemStack.invoke(p.entity, asNms.invoke(null, stack)); }

    /** Spawn packet + full metadata for a fresh part. */
    static void spawn(Player viewer, Part p) throws Exception {
        Object conn = NmsRecoil.connection(NmsRecoil.getHandle(viewer));
        NmsRecoil.send(conn, addEntityCtor.newInstance(p.entity, 0, Class.forName("net.minecraft.core.BlockPos").getField("ZERO").get(null)));
        Object data = getEntityData.invoke(p.entity);
        List<?> values = (List<?>) nonDefaultValues.invoke(data);
        if (values != null && !values.isEmpty()) NmsRecoil.send(conn, setDataCtor.newInstance(p.id, values));
        packDirty.invoke(data);   // clear the dirty flags we just sent
    }

    /** Send only what changed since the last send. */
    static void update(Player viewer, Part p) throws Exception {
        Object data = getEntityData.invoke(p.entity);
        List<?> values = (List<?>) packDirty.invoke(data);
        if (values == null || values.isEmpty()) return;
        NmsRecoil.send(NmsRecoil.connection(NmsRecoil.getHandle(viewer)), setDataCtor.newInstance(p.id, values));
    }

    /** Make the parts ride the viewer (client-side), so they follow the camera position every frame. */
    static void ride(Player viewer, List<Part> parts) throws Exception {
        Object handle = NmsRecoil.getHandle(viewer);
        // The passengers packet reads the vehicle's passenger list; we cannot touch the real list, so write the packet by hand:
        // entity id of the vehicle + the ids. The packet class only has the (Entity) constructor, so build it through its
        // StreamCodec-free path: a FriendlyByteBuf-free constructor does not exist in 26.x, hence the small detour below.
        Object conn = NmsRecoil.connection(handle);
        NmsRecoil.send(conn, PassengersPacket.make(handle, parts));
    }

    static void remove(Player viewer, List<Part> parts) throws Exception {
        if (parts.isEmpty()) return;
        int[] ids = new int[parts.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = parts.get(i).id;
        NmsRecoil.send(NmsRecoil.connection(NmsRecoil.getHandle(viewer)), removeCtor.newInstance((Object) ids));
    }

    /** ClientboundSetPassengersPacket has only an (Entity) constructor that copies the entity's real passenger list.
     *  The packet's fields are (int vehicle, int[] passengers): they are set by reflection after constructing it from
     *  the viewer (whose real list is normally empty). */
    static final class PassengersPacket {
        private static Field vehicleField, passengersField;
        static Object make(Object vehicle, List<Part> parts) throws Exception {
            Object pkt = passengersCtor.newInstance(vehicle);
            if (passengersField == null) {
                for (Field f : pkt.getClass().getDeclaredFields()) {
                    if (f.getType() == int[].class) passengersField = f;
                    else if (f.getType() == int.class) vehicleField = f;
                }
                if (passengersField == null) throw new IllegalStateException("passengers field not found");
                passengersField.setAccessible(true);
                if (vehicleField != null) vehicleField.setAccessible(true);
            }
            int[] ids = new int[parts.size()];
            for (int i = 0; i < ids.length; i++) ids[i] = parts.get(i).id;
            passengersField.set(pkt, ids);
            return pkt;
        }
    }

    static List<Integer> ids(List<Part> parts) { List<Integer> out = new ArrayList<>(); for (Part p : parts) out.add(p.id); return out; }
}
