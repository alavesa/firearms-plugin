package fi.alavesa.firearms;

import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 0.17.1: shells land on the real collision shape (slab, carpet, stairs), not on the block's full cube. */
class CasingLandingTest {
    @Test
    void landsOnACarpetTopNotTheBlockAbove() {
        BoundingBox carpet = new BoundingBox(10, 64, 10, 11, 64.0625, 11);
        double y = Casings.landingY(List.of(carpet), new Vector(10.5, 64.3, 10.5), new Vector(10.5, 64.02, 10.5));
        assertEquals(64.0625, y, 1e-9);
    }

    @Test
    void landsOnABottomSlabHalfway() {
        BoundingBox slab = new BoundingBox(0, 64, 0, 1, 64.5, 1);
        assertEquals(64.5, Casings.landingY(List.of(slab), new Vector(0.5, 64.8, 0.5), new Vector(0.5, 64.4, 0.5)), 1e-9);
        assertTrue(Double.isNaN(Casings.landingY(List.of(slab), new Vector(0.5, 64.9, 0.5), new Vector(0.5, 64.7, 0.5))), "still above the slab = still falling");
    }

    @Test
    void fallsThroughShapelessBlocksAndMissesBoxesBeside() {
        assertTrue(Double.isNaN(Casings.landingY(List.of(), new Vector(0.5, 64.5, 0.5), new Vector(0.5, 64.1, 0.5))));
        BoundingBox fence = new BoundingBox(0.375, 64, 0.375, 0.625, 65.5, 0.625);
        assertTrue(Double.isNaN(Casings.landingY(List.of(fence), new Vector(0.9, 65.6, 0.9), new Vector(0.9, 65.3, 0.9))));
        assertEquals(65.5, Casings.landingY(List.of(fence), new Vector(0.5, 65.6, 0.5), new Vector(0.5, 65.3, 0.5)), 1e-9);
    }

    @Test
    void fastShellDoesNotSkipAThinBox() {
        BoundingBox snow = new BoundingBox(0, 64, 0, 1, 64.125, 1);
        assertEquals(64.125, Casings.landingY(List.of(snow), new Vector(0.5, 64.9, 0.5), new Vector(0.5, 63.6, 0.5)), 1e-9);
    }

    @Test
    void highestSurfaceWinsOnStairs() {
        BoundingBox lower = new BoundingBox(0, 64, 0, 1, 64.5, 1), upper = new BoundingBox(0.5, 64.5, 0, 1, 65, 1);
        assertEquals(65, Casings.landingY(List.of(lower, upper), new Vector(0.75, 65.2, 0.5), new Vector(0.75, 64.3, 0.5)), 1e-9);
        assertEquals(64.5, Casings.landingY(List.of(lower, upper), new Vector(0.25, 65.2, 0.5), new Vector(0.25, 64.3, 0.5)), 1e-9);
    }
}
