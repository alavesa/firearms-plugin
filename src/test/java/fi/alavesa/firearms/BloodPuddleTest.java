package fi.alavesa.firearms;

import org.bukkit.block.BlockFace;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Tangent frames, the connected-texture tiles and their seams (the world-touching parts need a server). */
class BloodPuddleTest {
    static final BlockFace[] FACES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    @Test
    void tangentFramesAreRightHandedOnEveryFace() {
        for (BlockFace f : FACES) {
            int[] u = ModularBloodPuddleManager.uAxis(f), v = ModularBloodPuddleManager.vAxis(f), n = ModularBloodPuddleManager.normal(f);
            int[] cross = { u[1]*v[2] - u[2]*v[1], u[2]*v[0] - u[0]*v[2], u[0]*v[1] - u[1]*v[0] };
            assertArrayEquals(n, cross, f + ": U x V must be the normal");
            assertEquals(0, u[0]*v[0] + u[1]*v[1] + u[2]*v[2], f + ": U and V perpendicular");
        }
        assertArrayEquals(new int[]{0, 1, 0}, ModularBloodPuddleManager.vAxis(BlockFace.NORTH), "texture-up on a wall is world up");
        assertArrayEquals(new int[]{0, 0, -1}, ModularBloodPuddleManager.vAxis(BlockFace.UP), "texture-up on a floor is north");
    }

    @Test
    void frameQuaternionMapsModelAxesOntoTheTangentFrame() {
        for (BlockFace f : FACES) {
            Quaternionf q = ModularBloodPuddleManager.frame(f);
            int[] u = ModularBloodPuddleManager.uAxis(f), v = ModularBloodPuddleManager.vAxis(f), n = ModularBloodPuddleManager.normal(f);
            Vector3f x = q.transform(new Vector3f(1, 0, 0)), y = q.transform(new Vector3f(0, 1, 0)), z = q.transform(new Vector3f(0, 0, 1));
            assertEquals(u[0], x.x, 1e-5); assertEquals(u[1], x.y, 1e-5); assertEquals(u[2], x.z, 1e-5);
            assertEquals(v[0], y.x, 1e-5); assertEquals(v[1], y.y, 1e-5); assertEquals(v[2], y.z, 1e-5);
            assertEquals(n[0], z.x, 1e-5); assertEquals(n[1], z.y, 1e-5); assertEquals(n[2], z.z, 1e-5);
        }
    }

    @Test
    void modelNamesCoverThe32Tiles() {
        assertEquals("blood/pool_5", ModularBloodPuddleManager.modelName(true, 5));
        assertEquals("blood/splat_0", ModularBloodPuddleManager.modelName(false, 0));
        assertEquals("blood/splat_15", ModularBloodPuddleManager.modelName(false, 31), "mask is 4 bits");
    }

    static BufferedImage tile(int mask, boolean pool) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(ModularBloodPuddleManager.tilePng(mask, pool, 7 + mask * 2L + (pool ? 1 : 0))));
    }

    @Test
    void connectedSidesReachTheEdgeAndOpenSidesDoNot() throws Exception {
        for (boolean pool : new boolean[]{false, true}) for (int mask = 0; mask < 16; mask++) {
            BufferedImage img = tile(mask, pool);
            assertEquals(16, img.getWidth()); assertEquals(16, img.getHeight());
            for (int bit = 1; bit <= 8; bit <<= 1) {
                boolean connected = (mask & bit) != 0;
                String what = (pool ? "pool" : "splat") + "_" + mask + " side " + bit;
                if (connected) assertTrue(ModularBloodPuddleManager.edgeOpaqueMiddle(img, bit), what + " must reach the edge");
                else assertTrue(ModularBloodPuddleManager.edgeClear(img, bit), what + " must stay off the edge");
            }
        }
    }

    @Test
    void seamsMatchBetweenNeighbouringTiles() throws Exception {
        // two pool tiles side by side, both open at the top: A connected right (mask 2), B connected left (mask 8).
        // The rim profile is periodic along the edge, so A's right column equals B's left column pixel for pixel.
        BufferedImage a = tile(2, true), b = tile(8, true);
        for (int y = 0; y < 16; y++) assertEquals((a.getRGB(15, y) >>> 24) >= 128, (b.getRGB(0, y) >>> 24) >= 128, "row " + y);
        // the centre of a fully connected tile is solid everywhere
        BufferedImage c = tile(15, true);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) assertTrue((c.getRGB(x, y) >>> 24) >= 128, "centre tile pixel " + x + "," + y);
        // an isolated tile has a transparent corner and an opaque middle
        BufferedImage lone = tile(0, true);
        assertEquals(0, lone.getRGB(0, 0) >>> 24);
        assertTrue((lone.getRGB(8, 8) >>> 24) >= 128);
    }

    @Test
    void customOrGeneratedTexturesAreDeterministic() throws Exception {
        assertArrayEquals(ModularBloodPuddleManager.tilePng(6, true, 19), ModularBloodPuddleManager.tilePng(6, true, 19));
        assertFalse(java.util.Arrays.equals(ModularBloodPuddleManager.tilePng(6, true, 19), ModularBloodPuddleManager.tilePng(6, false, 19)), "the two stages differ");
    }
}
