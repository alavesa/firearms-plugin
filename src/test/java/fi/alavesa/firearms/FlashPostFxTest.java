package fi.alavesa.firearms;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlashPostFxTest {

    @Test
    void everyLevelIsAValidPostEffect() {
        var files = FlashPostFx.files();
        assertEquals(FlashPostFx.LEVELS + 1, files.size());
        for (int level = 1; level <= FlashPostFx.LEVELS; level++) {
            String json = new String(files.get("assets/firearms/post_effect/flash_" + level + ".json"));
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            assertEquals(2, root.getAsJsonArray("passes").size());
            JsonObject first = root.getAsJsonArray("passes").get(0).getAsJsonObject();
            assertEquals("firearms:post/flashbang", first.get("fragment_shader").getAsString());
            assertEquals(4, first.getAsJsonObject("uniforms").getAsJsonArray("FlashConfig").size());
            assertEquals("minecraft:main", first.getAsJsonArray("inputs").get(0).getAsJsonObject().get("target").getAsString());
        }
    }

    @Test
    void theWhiteGoesFirstAndTheTailLingers() {
        double[] low = FlashPostFx.params(4), mid = FlashPostFx.params(8), top = FlashPostFx.params(FlashPostFx.LEVELS);
        assertEquals(1.0, top[0], 1e-9);              // the top level is a full white-out
        assertEquals(0.0, low[0], 1e-9);              // below a third of the strength there is no white at all...
        assertTrue(low[1] > 0.2 && low[2] > 0.25);     // ...but the colour is drained and the picture still swims
        for (int level = 1; level < FlashPostFx.LEVELS; level++) {
            double[] a = FlashPostFx.params(level), b = FlashPostFx.params(level + 1);
            for (int i = 0; i < 4; i++) assertTrue(b[i] >= a[i] - 1e-12, "level " + level + " param " + i);
        }
        assertTrue(mid[0] < top[0]);
    }

    @Test
    void theShaderIsAPostShaderWithItsUniformBlocks() {
        assertTrue(FlashPostFx.SHADER.contains("uniform FlashConfig"));
        assertTrue(FlashPostFx.SHADER.contains("InSampler"));
        assertFalse(FlashPostFx.SHADER.contains("gl_VertexID")); // SPIR-V: would be gl_VertexIndex, and a post shader has no vertex stage here anyway
    }
}
