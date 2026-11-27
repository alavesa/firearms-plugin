package fi.alavesa.firearms;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Hand detection, the skin's arm regions and the geometry -> arm-face mapping. */
class HandSkinCompilerTest {

    @Test
    void aliasesAreHandsAndLookalikesAreNot() {
        for (String n : new String[]{"left_hand", "right_hand", "Left Arm", "l_hand", "hand_r", "RightHand", "lefthand2", "arm_l", "hand", "bone_arm_01"})
            assertTrue(BBModelHandSkinCompiler.isHandName(n, null), n);
        for (String n : new String[]{"armor", "alarm", "handle", "charging_handle", "", null, "body"})
            assertFalse(BBModelHandSkinCompiler.isHandName(n, null), String.valueOf(n));
        assertTrue(BBModelHandSkinCompiler.isHandName("glove", List.of("glove")), "configured keyword");
    }

    @Test
    void strictDetectionRejectsContainersAndPositioningBones() {
        for (String n : new String[]{"left_hand", "righthand", "lefthand2", "hand_l", "l_hand", "arm_r", "right_arm_slim", "arm", "Left Arm", "hand01"})
            assertTrue(BBModelHandSkinCompiler.isHandBoneName(n, null), n);
        for (String n : new String[]{"gun_and_righthand", "mag_and_lefthand", "righthand_pos", "thirdperson_hand", "handguard_tactical", "armor", "finger1", "root", "", null})
            assertFalse(BBModelHandSkinCompiler.isHandBoneName(n, null), String.valueOf(n));
        assertTrue(BBModelHandSkinCompiler.isHandBoneName("glove_l", List.of("glove")), "configured keyword");
    }

    @Test
    void cubeSizedSlicesCostOnlyTheCubesOwnPixels() {
        JsonObject faces = JsonParser.parseString("{\"north\":{\"uv\":[0,0,1,1],\"texture\":\"#0\"},\"east\":{\"uv\":[0,0,3,1],\"texture\":\"#0\"},\"up\":{\"uv\":[0,0,1,3],\"texture\":\"#0\"}}").getAsJsonObject();
        // a 1x1x3 finger along Z, hand end north
        var o = BBModelHandSkinCompiler.orient(new double[]{0, 0, 0}, new double[]{1, 1, 3}, new double[]{0.5, 0.5, -5}, BBModelHandSkinCompiler.Side.RIGHT);
        JsonObject out = BBModelHandSkinCompiler.remapFaces(faces, o, BBModelHandSkinCompiler.Side.RIGHT, false, false, "t", new double[]{0, 0, 0}, new double[]{1, 1, 3});
        var east = out.getAsJsonObject("east").getAsJsonArray("uv");
        assertEquals(1, Math.abs(east.get(2).getAsInt() - east.get(0).getAsInt()), "1 px wide");
        assertEquals(3, Math.abs(east.get(3).getAsInt() - east.get(1).getAsInt()), "3 px long");
        assertEquals(32, east.get(3).getAsInt(), "anchored at the hand end of the outer face [40,20]-[44,32]");
        assertTrue(east.get(0).getAsInt() >= 40 && east.get(2).getAsInt() <= 44, "inside the outer face");
        var north = out.getAsJsonObject("north").getAsJsonArray("uv");
        assertEquals(1, Math.abs(north.get(2).getAsInt() - north.get(0).getAsInt()));
        // a full arm box still gets the whole region
        JsonObject full = BBModelHandSkinCompiler.remapFaces(faces, o, BBModelHandSkinCompiler.Side.RIGHT, false, false, "t", new double[]{6, 2, 8}, new double[]{10, 6, 20});
        var fe = full.getAsJsonObject("east").getAsJsonArray("uv");
        assertEquals(40, fe.get(0).getAsInt()); assertEquals(20, fe.get(1).getAsInt()); assertEquals(44, fe.get(2).getAsInt()); assertEquals(32, fe.get(3).getAsInt());
    }

    @Test
    void sideComesFromTheNearestNameThatSaysSo() {
        assertEquals(BBModelHandSkinCompiler.Side.LEFT, BBModelHandSkinCompiler.sideOf(List.of("cube", "left_hand", "arms")));
        assertEquals(BBModelHandSkinCompiler.Side.LEFT, BBModelHandSkinCompiler.sideOf(List.of("l_hand")));
        assertEquals(BBModelHandSkinCompiler.Side.LEFT, BBModelHandSkinCompiler.sideOf(List.of("LeftArm")));
        assertEquals(BBModelHandSkinCompiler.Side.RIGHT, BBModelHandSkinCompiler.sideOf(List.of("hand_r", "left_group")), "nearest first");
        assertEquals(BBModelHandSkinCompiler.Side.RIGHT, BBModelHandSkinCompiler.sideOf(List.of("hand")), "default right");
    }

    @Test
    void armRegionsSitInTheStandardSkinLayout() {
        // right arm base layer lives in [40,16]-[56,32], its sleeve in [40,32]-[56,48]
        for (BBModelHandSkinCompiler.ArmFace f : BBModelHandSkinCompiler.ArmFace.values()) {
            int[] uv = BBModelHandSkinCompiler.uv(BBModelHandSkinCompiler.Side.RIGHT, false, false, f);
            assertTrue(uv[0] >= 40 && uv[2] <= 56 && uv[1] >= 16 && uv[3] <= 32, f + " right base " + java.util.Arrays.toString(uv));
            int[] sl = BBModelHandSkinCompiler.uv(BBModelHandSkinCompiler.Side.RIGHT, false, true, f);
            assertTrue(sl[0] >= 40 && sl[2] <= 56 && sl[1] >= 32 && sl[3] <= 48, f + " right sleeve " + java.util.Arrays.toString(sl));
            // left arm base in [32,48]-[48,64], sleeve in [48,48]-[64,64]
            int[] lu = BBModelHandSkinCompiler.uv(BBModelHandSkinCompiler.Side.LEFT, false, false, f);
            assertTrue(lu[0] >= 32 && lu[2] <= 48 && lu[1] >= 48 && lu[3] <= 64, f + " left base " + java.util.Arrays.toString(lu));
            int[] ls = BBModelHandSkinCompiler.uv(BBModelHandSkinCompiler.Side.LEFT, false, true, f);
            assertTrue(ls[0] >= 48 && ls[2] <= 64 && ls[1] >= 48 && ls[3] <= 64, f + " left sleeve " + java.util.Arrays.toString(ls));
        }
        // the exact vanilla rectangles
        assertArrayEquals(new int[]{44, 20, 48, 32}, BBModelHandSkinCompiler.uv(BBModelHandSkinCompiler.Side.RIGHT, false, false, BBModelHandSkinCompiler.ArmFace.FRONT));
        assertArrayEquals(new int[]{40, 20, 44, 32}, BBModelHandSkinCompiler.uv(BBModelHandSkinCompiler.Side.RIGHT, false, false, BBModelHandSkinCompiler.ArmFace.OUTER));
        assertArrayEquals(new int[]{48, 16, 52, 20}, BBModelHandSkinCompiler.uv(BBModelHandSkinCompiler.Side.RIGHT, false, false, BBModelHandSkinCompiler.ArmFace.HAND));
        assertArrayEquals(new int[]{36, 52, 40, 64}, BBModelHandSkinCompiler.uv(BBModelHandSkinCompiler.Side.LEFT, false, false, BBModelHandSkinCompiler.ArmFace.FRONT));
        assertArrayEquals(new int[]{32, 52, 36, 64}, BBModelHandSkinCompiler.uv(BBModelHandSkinCompiler.Side.LEFT, false, false, BBModelHandSkinCompiler.ArmFace.INNER));
        // slim: 3 px wide front/back/ends, the sides stay 4 deep
        assertArrayEquals(new int[]{44, 20, 47, 32}, BBModelHandSkinCompiler.uv(BBModelHandSkinCompiler.Side.RIGHT, true, false, BBModelHandSkinCompiler.ArmFace.FRONT));
        assertArrayEquals(new int[]{47, 20, 51, 32}, BBModelHandSkinCompiler.uv(BBModelHandSkinCompiler.Side.RIGHT, true, false, BBModelHandSkinCompiler.ArmFace.INNER));
        assertArrayEquals(new int[]{51, 20, 54, 32}, BBModelHandSkinCompiler.uv(BBModelHandSkinCompiler.Side.RIGHT, true, false, BBModelHandSkinCompiler.ArmFace.BACK));
    }

    @Test
    void sleevePixelsFallBackToTheArmUnderThem() {
        assertArrayEquals(new int[]{44, 20}, BBModelHandSkinCompiler.sleeveBase(44, 36));
        assertArrayEquals(new int[]{36, 52}, BBModelHandSkinCompiler.sleeveBase(52, 52));
        assertNull(BBModelHandSkinCompiler.sleeveBase(44, 20), "a base pixel has no base");
        assertNull(BBModelHandSkinCompiler.sleeveBase(8, 8));
    }

    @Test
    void orientationReadsTheArmOffTheCube() {
        // a 4x4x12 forearm along Z, the gun's centre further north (-Z): the north end is the hand
        var o = BBModelHandSkinCompiler.orient(new double[]{6, 2, 8}, new double[]{10, 6, 20}, new double[]{8, 6, -5}, BBModelHandSkinCompiler.Side.RIGHT);
        assertArrayEquals(new int[]{0, 0, -1}, o.hand);
        assertArrayEquals(new int[]{1, 0, 0}, o.outer, "right hand: outer = +X");
        assertArrayEquals(new int[]{0, 1, 0}, o.front, "front of a forward arm faces up");
        Map<String, BBModelHandSkinCompiler.ArmFace> f = o.faces();
        assertEquals(BBModelHandSkinCompiler.ArmFace.HAND, f.get("north"));
        assertEquals(BBModelHandSkinCompiler.ArmFace.SHOULDER, f.get("south"));
        assertEquals(BBModelHandSkinCompiler.ArmFace.OUTER, f.get("east"));
        assertEquals(BBModelHandSkinCompiler.ArmFace.INNER, f.get("west"));
        assertEquals(BBModelHandSkinCompiler.ArmFace.FRONT, f.get("up"));
        assertEquals(BBModelHandSkinCompiler.ArmFace.BACK, f.get("down"));
        // the left hand mirrors the outer side
        var l = BBModelHandSkinCompiler.orient(new double[]{6, 2, 8}, new double[]{10, 6, 20}, new double[]{8, 6, -5}, BBModelHandSkinCompiler.Side.LEFT);
        assertArrayEquals(new int[]{-1, 0, 0}, l.outer);
        // a vertical arm: hand toward the gun above it, front toward the muzzle
        var v = BBModelHandSkinCompiler.orient(new double[]{0, 0, 0}, new double[]{4, 12, 4}, new double[]{2, 20, 2}, BBModelHandSkinCompiler.Side.RIGHT);
        assertArrayEquals(new int[]{0, 1, 0}, v.hand);
        assertArrayEquals(new int[]{0, 0, -1}, v.front);
    }

    @Test
    void remappedFacesPointAtTheSkinWithTheArmRunningTowardTheHand() {
        JsonObject faces = JsonParser.parseString("{\"north\":{\"uv\":[0,0,4,4],\"texture\":\"#0\"},\"south\":{\"uv\":[0,0,4,4],\"texture\":\"#0\"},\"east\":{\"uv\":[0,0,12,4],\"texture\":\"#0\"},\"west\":{\"uv\":[0,0,12,4],\"texture\":\"#0\"},\"up\":{\"uv\":[0,0,4,12],\"texture\":\"#0\"}}").getAsJsonObject();
        var o = BBModelHandSkinCompiler.orient(new double[]{6, 2, 8}, new double[]{10, 6, 20}, new double[]{8, 6, -5}, BBModelHandSkinCompiler.Side.RIGHT);
        JsonObject out = BBModelHandSkinCompiler.remapFaces(faces, o, BBModelHandSkinCompiler.Side.RIGHT, false, false, "skin_template_3");
        assertFalse(out.has("down"), "a face the cube did not have stays absent");
        assertEquals("skin_template_3", out.getAsJsonObject("east").get("texture").getAsString());
        // east = outer side: [40,20]-[44,32]
        var uv = out.getAsJsonObject("east").getAsJsonArray("uv");
        assertEquals(40, uv.get(0).getAsInt()); assertEquals(20, uv.get(1).getAsInt()); assertEquals(44, uv.get(2).getAsInt()); assertEquals(32, uv.get(3).getAsInt());
        // the east face's texture-down is -Y by default; the hand is -Z, so the face is turned a quarter: 90 or 270, never 0/180
        int rot = out.getAsJsonObject("east").has("rotation") ? out.getAsJsonObject("east").get("rotation").getAsInt() : 0;
        assertTrue(rot == 90 || rot == 270, "rotation " + rot);
        // the up face (front): texture-down is +Z (south) by default, the hand is north -> turned 180
        assertEquals(180, out.getAsJsonObject("up").get("rotation").getAsInt());
        // end faces keep rotation 0 and map to the 4x4 regions
        assertFalse(out.getAsJsonObject("north").has("rotation"));
        assertEquals(48, out.getAsJsonObject("north").getAsJsonArray("uv").get(0).getAsInt(), "north = hand end");
        assertEquals(44, out.getAsJsonObject("south").getAsJsonArray("uv").get(0).getAsInt(), "south = shoulder end");
    }

    @Test
    void templateIsOpaqueEverywhereTheArmLives() {
        var img = BBModelHandSkinCompiler.template();
        assertEquals(64, img.getWidth()); assertEquals(64, img.getHeight());
        for (int y = 16; y < 48; y++) for (int x = 40; x < 56; x++) assertEquals(255, img.getRGB(x, y) >>> 24, "right arm + sleeve");
        for (int y = 48; y < 64; y++) for (int x = 32; x < 64; x++) assertEquals(255, img.getRGB(x, y) >>> 24, "left arm + sleeve");
    }
}
