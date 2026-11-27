package fi.alavesa.firearms;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Blood as a grid of connected puddle tiles. Every tile covers exactly one block face (1 x 1), flush on the face's real
 * collision surface (slab tops, snow layers, carpets, stair faces, walls and ceilings alike), and is drawn by one
 * {@link ItemDisplay} showing one of 32 pack models {@code blood/<splat|pool>_<mask>}: {@code mask} is the 4-bit
 * adjacency of the tile's neighbours in the face's 2D tangent space (1 = +V "up/north", 2 = +U "right/east", 4 = -V,
 * 8 = -U), so neighbouring tiles share flush edges and read as one 2x1, 2x2, 3x2... puddle; an isolated tile is a round
 * splatter. Blood adds volume to a tile; past saturation the surplus spills into a neighbouring face (downhill on walls,
 * along the shot on floors), onto whatever that neighbour's own surface height is, and a tile dries and disappears
 * after {@code blood.seconds}. Breaking or covering a block removes its tiles and re-shapes the tiles around them.
 * <p>
 * Tangent frames (U, V, n right-handed: U x V = n): UP (+X, -Z), DOWN (+X, +Z), NORTH (-X, +Y), SOUTH (+X, +Y),
 * EAST (-Z, +Y), WEST (+Z, +Y). The decal model's visible face has texture-right along +U and texture-up along +V.
 */
public final class ModularBloodPuddleManager implements Listener {
    public static final String TAG = "firearms_blood";
    static final String MODEL_DIR = "blood/";

    /** A tile: one block face. */
    record Key(UUID world, int x, int y, int z, BlockFace face) { }

    static final class Cell {
        final Key key; final double coord;     // coord = world coordinate of the surface along the face's normal axis
        double volume; boolean drying; int born; UUID entity; String model;
        Cell(Key key, double coord, int born) { this.key = key; this.coord = coord; this.born = born; }
    }

    private final FirearmsPlugin plugin;
    private final Registry registry;
    private final Map<Key, Cell> cells = new HashMap<>();
    private final Deque<Key> order = new ArrayDeque<>();
    private org.bukkit.scheduler.BukkitTask sweeper;

    public ModularBloodPuddleManager(FirearmsPlugin plugin, Registry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        sweeper = plugin.getServer().getScheduler().runTaskTimer(plugin, this::sweep, 20L, 10L);
        // leftovers of a crash: entities are non-persistent, but a world that was not saved cleanly may still hold them
        int swept = 0;
        for (World w : plugin.getServer().getWorlds()) for (Entity e : w.getEntitiesByClass(ItemDisplay.class)) if (e.getScoreboardTags().contains(TAG)) { e.remove(); swept++; }
        if (swept > 0) plugin.getLogger().info("Blood: removed " + swept + " leftover tile(s).");
    }

    public boolean enabled() { return plugin.getConfig().getBoolean("blood.enabled", true); }
    public int cellCount() { return cells.size(); }
    private double cfg(String k, double d) { return plugin.getConfig().getDouble("blood." + k, d); }
    private int cfgI(String k, int d) { return plugin.getConfig().getInt("blood." + k, d); }

    // ------------------------------------------------------------------ entry points

    /** A bullet wound: a burst of particles at the wound, a spurt along the bullet onto the wall behind, a pool under the
     *  victim and one random splatter. */
    public void bleed(LivingEntity target, Location wound, Vector dir) {
        if (!enabled() || dir == null || target instanceof ArmorStand) return;
        World w = target.getWorld();
        w.spawnParticle(Particle.BLOCK, wound, 14, 0.15, 0.2, 0.15, 0.08, Material.REDSTONE_BLOCK.createBlockData());
        double per = cfg("per-hit", 0.45), range = cfg("range", 4.0);
        splash(w, wound, dir, range, per);
        var rnd = ThreadLocalRandom.current();
        int extra = cfgI("extra", 2);
        for (int i = 0; i < extra; i++) {
            Location from = target.getLocation().add(rnd.nextDouble() * 1.2 - 0.6, 0.6 + rnd.nextDouble() * 0.8, rnd.nextDouble() * 1.2 - 0.6);
            Vector d = i == 0 ? new Vector(rnd.nextDouble() * 0.6 - 0.3, -1, rnd.nextDouble() * 0.6 - 0.3) : new Vector(rnd.nextDouble() * 2 - 1, rnd.nextDouble() * 0.6 - 0.5, rnd.nextDouble() * 2 - 1);
            if (d.lengthSquared() < 1e-4) continue;
            splash(w, from, d.normalize(), i == 0 ? 2.5 : range, per * (i == 0 ? 0.8 : 0.6));
        }
    }

    /** Blood flying from `from` along `dir`: the first block face within `range` receives `volume` (1.0 = one full tile).
     *  Public for other plugins (melee, explosions). Returns whether a tile was hit. */
    public boolean splash(World w, Location from, Vector dir, double range, double volume) {
        if (!enabled() || volume <= 0) return false;
        RayTraceResult hit = w.rayTraceBlocks(from, dir, range, FluidCollisionMode.NEVER, true);
        if (hit == null || hit.getHitBlock() == null || hit.getHitBlockFace() == null) return false;
        BlockFace face = hit.getHitBlockFace();
        if (normal(face) == null) return false;
        return deposit(hit.getHitBlock(), face, hit.getHitPosition().toLocation(w), volume, dir, 0);
    }

    // ------------------------------------------------------------------ cells

    /** Add volume to the tile of this face (creating it on the face's real surface), spilling the surplus around. */
    boolean deposit(Block b, BlockFace face, Location hitPoint, double volume, Vector flow, int depth) {
        World w = b.getWorld();
        double lx = clamp01(hitPoint.getX() - b.getX()), ly = clamp01(hitPoint.getY() - b.getY()), lz = clamp01(hitPoint.getZ() - b.getZ());
        Double c = faceCoord(b, face, lx, ly, lz);
        if (c == null) return false;
        Key key = new Key(w.getUID(), b.getX(), b.getY(), b.getZ(), face);
        Cell cell = cells.get(key);
        if (cell == null) cell = create(w, key, coordWorld(b, face, c));
        if (cell == null) return false;
        pour(cell, volume, flow, depth);
        return true;
    }

    private void pour(Cell cell, double volume, Vector flow, int depth) {
        cell.volume += volume;
        cell.drying = false;
        double surplus = cell.volume - 1.0;
        if (surplus > 0) cell.volume = 1.0;
        refresh(cell);
        refreshAround(cell);
        if (surplus > 0.02 && depth < cfgI("spill-depth", 3)) spill(cell, surplus, flow, depth + 1);
    }

    /** The surplus runs into a neighbouring face: down on walls, along the shot on floors and ceilings. */
    private void spill(Cell cell, double surplus, Vector flow, int depth) {
        BlockFace face = cell.key.face();
        int[] U = uAxis(face), V = vAxis(face);
        List<double[]> cand = new ArrayList<>();   // du, dv, weight
        if (face == BlockFace.UP || face == BlockFace.DOWN) {
            double fu = flow == null ? 0 : flow.getX() * U[0] + flow.getY() * U[1] + flow.getZ() * U[2];
            double fv = flow == null ? 0 : flow.getX() * V[0] + flow.getY() * V[1] + flow.getZ() * V[2];
            cand.add(new double[]{fu >= 0 ? 1 : -1, 0, 1 + 2 * Math.abs(fu)});
            cand.add(new double[]{0, fv >= 0 ? 1 : -1, 1 + 2 * Math.abs(fv)});
            cand.add(new double[]{fu >= 0 ? -1 : 1, 0, 0.6});
            cand.add(new double[]{0, fv >= 0 ? -1 : 1, 0.6});
        } else {
            cand.add(new double[]{0, -1, 3});     // down the wall
            cand.add(new double[]{1, 0, 1});
            cand.add(new double[]{-1, 0, 1});
            cand.add(new double[]{0, 1, 0.25});
        }
        var rnd = ThreadLocalRandom.current();
        World w = plugin.getServer().getWorld(cell.key.world());
        if (w == null) return;
        while (!cand.isEmpty()) {
            double total = 0; for (double[] c : cand) total += c[2];
            double r = rnd.nextDouble() * total; int pick = 0;
            for (int i = 0; i < cand.size(); i++) { r -= cand.get(i)[2]; if (r <= 0) { pick = i; break; } }
            double[] c = cand.remove(pick);
            Cell n = neighbourSurface(w, cell, (int) c[0], (int) c[1], true);
            if (n == null) continue;
            pour(n, surplus, flow, depth);
            return;
        }
    }

    private Cell create(World w, Key key, double coordWorld) {
        if (cells.size() >= cfgI("max-cells", 600)) { Key oldest = order.pollFirst(); if (oldest != null) remove(oldest, true); }
        Cell cell = new Cell(key, coordWorld, plugin.getServer().getCurrentTick());
        BlockFace face = key.face();
        int[] n = normal(face);
        Location at = new Location(w, key.x() + 0.5, key.y() + 0.5, key.z() + 0.5);
        int axis = axisOf(n);
        double lift = cfg("surface-offset", 0.002) * (n[axis] > 0 ? 1 : -1);
        if (axis == 0) at.setX(coordWorld + lift); else if (axis == 1) at.setY(coordWorld + lift); else at.setZ(coordWorld + lift);
        float sc = (float) cfg("tile-scale", 1.0);
        Quaternionf q = frame(face);
        String model = modelName(false, 0);
        try {
            ItemDisplay d = w.spawn(at, ItemDisplay.class, disp -> {
                disp.setItemStack(registry.buildDecal(model));
                disp.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                disp.setPersistent(false);
                disp.addScoreboardTag(TAG);
                disp.setViewRange((float) cfg("view-range", 1.0));
                disp.setTransformation(new Transformation(new Vector3f(), q, new Vector3f(sc, sc, sc), new Quaternionf()));
            });
            cell.entity = d.getUniqueId();
            cell.model = model;
        } catch (Exception e) {
            plugin.getLogger().fine("blood tile failed: " + e);
            return null;
        }
        cells.put(key, cell);
        order.addLast(key);
        return cell;
    }

    private void remove(Key key, boolean refreshNeighbours) {
        Cell c = cells.remove(key);
        order.remove(key);
        if (c == null) return;
        if (c.entity != null) { Entity e = Bukkit.getEntity(c.entity); if (e != null) e.remove(); }
        if (refreshNeighbours) refreshAround(c);
    }

    /** Re-pick the tile's model from its volume and its neighbours. */
    private void refresh(Cell cell) {
        boolean pool = !cell.drying && cell.volume >= cfg("pool-threshold", 0.6);
        String model = modelName(pool, mask(cell));
        if (model.equals(cell.model)) return;
        Entity e = cell.entity == null ? null : Bukkit.getEntity(cell.entity);
        if (!(e instanceof ItemDisplay d)) { cells.remove(cell.key); order.remove(cell.key); return; }   // chunk unloaded: the tile is gone
        d.setItemStack(registry.buildDecal(model));
        cell.model = model;
    }

    private void refreshAround(Cell cell) {
        World w = plugin.getServer().getWorld(cell.key.world());
        if (w == null) return;
        for (int[] d : new int[][]{{0, 1}, {1, 0}, {0, -1}, {-1, 0}}) {
            Cell n = neighbourCell(cell, d[0], d[1]);
            if (n != null) refresh(n);
        }
    }

    /** 4-bit adjacency: 1 = +V, 2 = +U, 4 = -V, 8 = -U. */
    int mask(Cell cell) {
        int m = 0;
        if (neighbourCell(cell, 0, 1) != null) m |= 1;
        if (neighbourCell(cell, 1, 0) != null) m |= 2;
        if (neighbourCell(cell, 0, -1) != null) m |= 4;
        if (neighbourCell(cell, -1, 0) != null) m |= 8;
        return m;
    }

    /** The existing tile next to `cell` in its tangent space, on the same level or one block in/out along the normal
     *  (a slab beside a full block), if its surface is within blood.connect-step of this one. */
    private Cell neighbourCell(Cell cell, int du, int dv) {
        BlockFace face = cell.key.face();
        int[] U = uAxis(face), V = vAxis(face), n = normal(face);
        double step = cfg("connect-step", 0.5);
        for (int s : new int[]{0, -1, 1}) {
            Key k = new Key(cell.key.world(), cell.key.x() + du * U[0] + dv * V[0] + s * n[0], cell.key.y() + du * U[1] + dv * V[1] + s * n[1], cell.key.z() + du * U[2] + dv * V[2] + s * n[2], face);
            Cell c = cells.get(k);
            if (c != null && Math.abs(c.coord - cell.coord) <= step + 1e-6) return c;
        }
        return null;
    }

    /** The tile beside `cell` (existing or, when `create`, made on the neighbour block's real surface), or null when there
     *  is no surface there: air with nothing below, a face covered by another block, a liquid. */
    private Cell neighbourSurface(World w, Cell cell, int du, int dv, boolean create) {
        Cell existing = neighbourCell(cell, du, dv);
        if (existing != null || !create) return existing;
        BlockFace face = cell.key.face();
        int[] U = uAxis(face), V = vAxis(face), n = normal(face);
        int bx = cell.key.x() + du * U[0] + dv * V[0], by = cell.key.y() + du * U[1] + dv * V[1], bz = cell.key.z() + du * U[2] + dv * V[2];
        for (int s : new int[]{0, -1, 1}) {
            int x = bx + s * n[0], y = by + s * n[1], z = bz + s * n[2];
            if (y < w.getMinHeight() || y >= w.getMaxHeight()) continue;
            Block b = w.getBlockAt(x, y, z);
            Double c = faceCoord(b, face, 0.5, 0.5, 0.5);
            if (c == null) continue;
            Key k = new Key(w.getUID(), x, y, z, face);
            if (cells.containsKey(k)) return cells.get(k);
            return create(w, k, coordWorld(b, face, c));
        }
        return null;
    }

    // ------------------------------------------------------------------ the surface solver

    /**
     * Where the surface of `face` really is on this block, under the block-local point (lx, ly, lz), from the block's
     * collision shape: the extreme of the collision boxes whose footprint holds the point (a bottom slab's top is at 0.5,
     * three snow layers at 0.25, a trapdoor at 0.1875, a stair's step at 0.5 or 1.0 depending where you hit). Returned as
     * the block-relative coordinate along the face's normal axis (0..1), or null when there is nothing to lie on: an empty
     * collision shape (air, flowers, one snow layer), a liquid, or a face pressed against the next block.
     */
    static Double faceCoord(Block b, BlockFace face, double lx, double ly, double lz) {
        int[] n = normal(face);
        if (n == null || b.isLiquid()) return null;
        Collection<BoundingBox> boxes;
        try { boxes = b.getCollisionShape().getBoundingBoxes(); } catch (Exception e) { return null; }
        if (boxes == null || boxes.isEmpty()) return null;
        int axis = axisOf(n);
        boolean positive = n[axis] > 0;
        double[] p = {lx, ly, lz};
        Double best = null, any = null;
        for (BoundingBox box : boxes) {
            double v = positive ? max(box, axis) : min(box, axis);
            any = any == null ? v : positive ? Math.max(any, v) : Math.min(any, v);
            if (contains(box, axis, p)) best = best == null ? v : positive ? Math.max(best, v) : Math.min(best, v);
        }
        double coord = best != null ? best : any;
        // a surface flush with the block boundary is only a surface if the next block does not press against it
        if (positive ? coord >= 1 - 1e-4 : coord <= 1e-4) {
            Block next = b.getRelative(n[0], n[1], n[2]);
            if (next.isLiquid()) return null;
            try {
                for (BoundingBox box : next.getCollisionShape().getBoundingBoxes()) {
                    boolean touching = positive ? min(box, axis) <= 1e-4 : max(box, axis) >= 1 - 1e-4;
                    if (touching && contains(box, axis, p)) return null;
                }
            } catch (Exception ignored) { }
        }
        return coord;
    }

    private static double coordWorld(Block b, BlockFace face, double coord) {
        int axis = axisOf(normal(face));
        return (axis == 0 ? b.getX() : axis == 1 ? b.getY() : b.getZ()) + coord;
    }
    private static double min(BoundingBox box, int axis) { return axis == 0 ? box.getMinX() : axis == 1 ? box.getMinY() : box.getMinZ(); }
    private static double max(BoundingBox box, int axis) { return axis == 0 ? box.getMaxX() : axis == 1 ? box.getMaxY() : box.getMaxZ(); }
    /** Does the box's footprint on the two axes other than `axis` hold the point (with a little slack)? */
    private static boolean contains(BoundingBox box, int axis, double[] p) {
        for (int a = 0; a < 3; a++) {
            if (a == axis) continue;
            if (p[a] < min(box, a) - 1e-4 || p[a] > max(box, a) + 1e-4) return false;
        }
        return true;
    }
    private static double clamp01(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    // ------------------------------------------------------------------ tangent frames

    static int[] normal(BlockFace f) {
        return switch (f) {
            case UP -> new int[]{0, 1, 0}; case DOWN -> new int[]{0, -1, 0};
            case NORTH -> new int[]{0, 0, -1}; case SOUTH -> new int[]{0, 0, 1};
            case EAST -> new int[]{1, 0, 0}; case WEST -> new int[]{-1, 0, 0};
            default -> null;
        };
    }
    /** Texture-right of the tile, in world axes. */
    static int[] uAxis(BlockFace f) {
        return switch (f) {
            case UP, DOWN, SOUTH -> new int[]{1, 0, 0};
            case NORTH -> new int[]{-1, 0, 0};
            case EAST -> new int[]{0, 0, -1};
            case WEST -> new int[]{0, 0, 1};
            default -> new int[]{1, 0, 0};
        };
    }
    /** Texture-up of the tile, in world axes. */
    static int[] vAxis(BlockFace f) {
        return switch (f) {
            case UP -> new int[]{0, 0, -1};
            case DOWN -> new int[]{0, 0, 1};
            default -> new int[]{0, 1, 0};
        };
    }
    static int axisOf(int[] n) { return n[0] != 0 ? 0 : n[1] != 0 ? 1 : 2; }

    /** The display rotation that lays the flat model on the face: model X -> U, model Y -> V, model Z -> normal. (The
     *  item display renderer turns the item 180 degrees about Y before drawing; the model's north face is the visible one,
     *  textured so it reads right that way.) */
    static Quaternionf frame(BlockFace f) {
        int[] u = uAxis(f), v = vAxis(f), n = normal(f);
        Matrix3f m = new Matrix3f(u[0], u[1], u[2], v[0], v[1], v[2], n[0], n[1], n[2]);   // column-major: columns = U, V, n
        return new Quaternionf().setFromNormalized(m);
    }

    // ------------------------------------------------------------------ lifetime and world changes

    private void sweep() {
        if (cells.isEmpty()) return;
        int now = plugin.getServer().getCurrentTick();
        int life = (int) Math.max(20, cfg("seconds", 90) * 20);
        int dryAt = (int) (life * Math.max(0.1, Math.min(0.99, cfg("dry-at", 0.7))));
        List<Cell> gone = new ArrayList<>(), dry = new ArrayList<>();
        for (Cell c : cells.values()) {
            int age = now - c.born;
            if (age >= life || c.entity == null || Bukkit.getEntity(c.entity) == null) gone.add(c);
            else if (age >= dryAt && !c.drying) dry.add(c);
        }
        for (Cell c : gone) remove(c.key, true);
        for (Cell c : dry) { c.drying = true; refresh(c); }
    }

    /** A block changed: its own tiles go, and so do the tiles on its neighbours that face it (covered or uncovered). */
    private void blockChanged(Block b) {
        if (cells.isEmpty()) return;
        UUID w = b.getWorld().getUID();
        List<Key> drop = new ArrayList<>();
        for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            drop.add(new Key(w, b.getX(), b.getY(), b.getZ(), f));
            int[] n = normal(f);
            drop.add(new Key(w, b.getX() - n[0], b.getY() - n[1], b.getZ() - n[2], f));   // the neighbour whose face points at this block
        }
        for (Key k : drop) if (cells.containsKey(k)) remove(k, true);
    }

    @EventHandler(ignoreCancelled = true) public void onBreak(BlockBreakEvent e) { blockChanged(e.getBlock()); }
    @EventHandler(ignoreCancelled = true) public void onPlace(BlockPlaceEvent e) { blockChanged(e.getBlock()); }
    @EventHandler(ignoreCancelled = true) public void onBurn(BlockBurnEvent e) { blockChanged(e.getBlock()); }
    @EventHandler(ignoreCancelled = true) public void onFade(BlockFadeEvent e) { blockChanged(e.getBlock()); }
    @EventHandler(ignoreCancelled = true) public void onExplode(BlockExplodeEvent e) { for (Block b : e.blockList()) blockChanged(b); }
    @EventHandler(ignoreCancelled = true) public void onEntityExplode(EntityExplodeEvent e) { for (Block b : e.blockList()) blockChanged(b); }
    @EventHandler(ignoreCancelled = true) public void onPiston(BlockPistonExtendEvent e) { blockChanged(e.getBlock()); for (Block b : e.getBlocks()) { blockChanged(b); blockChanged(b.getRelative(e.getDirection())); } }
    @EventHandler(ignoreCancelled = true) public void onPistonBack(BlockPistonRetractEvent e) { blockChanged(e.getBlock()); for (Block b : e.getBlocks()) { blockChanged(b); blockChanged(b.getRelative(e.getDirection())); } }
    @EventHandler public void onWorldUnload(WorldUnloadEvent e) {
        UUID w = e.getWorld().getUID();
        for (Key k : new ArrayList<>(cells.keySet())) if (k.world().equals(w)) { cells.remove(k); order.remove(k); }
    }

    /** Remove every tile (disable / reload). */
    public void clearAll() {
        for (Key k : new ArrayList<>(cells.keySet())) remove(k, false);
        cells.clear(); order.clear();
        for (World w : plugin.getServer().getWorlds()) for (Entity e : w.getEntitiesByClass(ItemDisplay.class)) if (e.getScoreboardTags().contains(TAG)) e.remove();
        if (sweeper != null) { sweeper.cancel(); sweeper = null; }
    }

    // ------------------------------------------------------------------ the pack: 32 tile models

    /** custom_model_data string of a tile model: blood/pool_5, blood/splat_0 ... */
    public static String modelName(boolean pool, int mask) { return MODEL_DIR + (pool ? "pool" : "splat") + "_" + (mask & 15); }

    /**
     * A 16x16 tile texture for one adjacency mask. The puddle is a rounded rectangle: on a connected side it runs to the
     * tile edge, on an open side it pulls in behind a slightly wavy rim, and two open sides meet in a rounded corner. The
     * rim profile of a side is the same in every tile and symmetric along the edge, so two tiles that are both open on
     * that side meet without a step and a connected edge is cut identically on both tiles. The pool stage is a dense dark
     * pool, the splat stage a thinner stain with spatter dots around an isolated tile.
     */
    public static byte[] tilePng(int mask, boolean pool, long seed) throws IOException {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        boolean openTop = (mask & 1) == 0, openRight = (mask & 2) == 0, openBottom = (mask & 4) == 0, openLeft = (mask & 8) == 0;
        double base = pool ? 2.5 : 4.0, wob = pool ? 1.0 : 1.3, R = pool ? 5.0 : 4.0;
        for (int py = 0; py < 16; py++) for (int px = 0; px < 16; px++) {
            double cx = px + 0.5, cy = py + 0.5;
            double eT = cy, eR = 16 - cx, eB = 16 - cy, eL = cx;                                   // distance to each tile edge
            double cT = openTop ? rim(cx, base, wob, 1) : 0, cR = openRight ? rim(cy, base, wob, 2) : 0;
            double cB = openBottom ? rim(cx, base, wob, 3) : 0, cL = openLeft ? rim(cy, base, wob, 4) : 0;
            boolean inside = eT >= cT && eR >= cR && eB >= cB && eL >= cL
                && corner(eT, cT, openTop, eR, cR, openRight, R) && corner(eT, cT, openTop, eL, cL, openLeft, R)
                && corner(eB, cB, openBottom, eR, cR, openRight, R) && corner(eB, cB, openBottom, eL, cL, openLeft, R);
            double toRim = Math.min(Math.min(openTop ? eT - cT : 99, openRight ? eR - cR : 99), Math.min(openBottom ? eB - cB : 99, openLeft ? eL - cL : 99));
            double h = hash(px, py, seed);
            int argb;
            if (inside) {
                double shade = 0.82 + 0.18 * h, edge = toRim < 1.2 ? 0.72 + 0.28 * Math.max(0, toRim) / 1.2 : 1;
                int r = (int) ((pool ? 118 : 150) * shade * edge), g = (int) ((pool ? 8 : 16) * shade), b = (int) ((pool ? 10 : 18) * shade);
                int a = pool ? 242 : 205;
                if (!pool && h > 0.88) a -= 60;                               // a thin stain shows the ground through
                argb = (a << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
            } else if (!pool && mask == 0 && toRim > -2.6 && h < 0.14 && eT >= 1 && eR >= 1 && eB >= 1 && eL >= 1) {
                argb = (170 << 24) | (140 << 16) | (14 << 8) | 16;            // spatter dots around a lone splat, never on the outer ring
            } else argb = 0;
            img.setRGB(px, py, argb);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
    /** The inset of an open side at `along` (0..16): symmetric about the edge's middle and identical in every tile. */
    private static double rim(double along, double base, double wob, int side) {
        double k1 = side % 2 == 0 ? 1 : 0.7, k2 = side > 2 ? -0.5 : 0.5;
        return base + wob * k1 * Math.cos(2 * Math.PI * along / 16) + wob * k2 * Math.cos(4 * Math.PI * along / 16);
    }
    /** Rounded corner of radius R where two OPEN sides meet (true = this pixel is not cut off by the corner). */
    private static boolean corner(double eA, double cA, boolean openA, double eB, double cB, boolean openB, double R) {
        if (!openA || !openB) return true;
        double a = cA + R - eA, b = cB + R - eB;
        if (a <= 0 || b <= 0) return true;
        return a * a + b * b <= R * R;
    }
    private static double hash(int x, int y, long seed) {
        long h = x * 374761393L + y * 668265263L + seed * 2246822519L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        return ((h ^ (h >>> 16)) & 0xFFFFFF) / (double) 0x1000000;
    }
    private static int clamp(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }

    /** Is the middle of the tile edge on side `bit` (1 top, 2 right, 4 bottom, 8 left) opaque? A connected side must be. */
    static boolean edgeOpaqueMiddle(BufferedImage img, int bit) {
        for (int i = 6; i < 10; i++) if ((edgePixel(img, bit, i) >>> 24) < 128) return false;
        return true;
    }
    /** Is the whole tile edge on side `bit` transparent? An open side must be. */
    static boolean edgeClear(BufferedImage img, int bit) {
        for (int i = 0; i < 16; i++) if ((edgePixel(img, bit, i) >>> 24) >= 128) return false;
        return true;
    }
    private static int edgePixel(BufferedImage img, int bit, int i) {
        return switch (bit) { case 1 -> img.getRGB(i, 0); case 2 -> img.getRGB(15, i); case 4 -> img.getRGB(i, 15); default -> img.getRGB(0, i); };
    }
}
