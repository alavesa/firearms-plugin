package fi.alavesa.firearms;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The flashbang's screen overlay as 26.3 post effects: {@link #LEVELS} effects {@code firearms:flash_1..N}, one
 * fragment shader ({@code firearms:post/flashbang}, a POST shader - never a core shader, so a mistake in it cannot make
 * the client drop the pack) and a strength per level. A post effect's uniforms are fixed in its json, so the plugin
 * fades a flash by swapping the player's effect from level to level (Flash.java).
 * <p>
 * One level = one overall strength s = level / LEVELS, shown as
 * <ul>
 *   <li>whiteout - the white-out itself, only above s = 0.3, so it ends first,</li>
 *   <li>blur - a 13-tap golden-angle blur whose radius follows s (the vision swims),</li>
 *   <li>desaturation - the colour drains (the "ringing" tail, longest),</li>
 *   <li>glow - a soft bright bloom, strongest in the middle of the screen.</li>
 * </ul>
 */
final class FlashPostFx {
    static final int LEVELS = 24;

    private FlashPostFx() { }

    static String id(int level) { return "firearms:flash_" + level; }

    /** {whiteout, desaturation, blur radius (0..1), glow} of a level. */
    static double[] params(int level) {
        double s = Math.max(0, Math.min(1, level / (double) LEVELS));
        double white = Math.pow(Math.max(0, (s - 0.30) / 0.70), 0.85);
        double desat = Math.min(1, s * 1.5) * 0.9;
        double blur = Math.pow(s, 0.7);
        double glow = s * 0.35;
        return new double[] {white, desat, blur, glow};
    }

    static final String SHADER = """
        #version 330
        #extension GL_ARB_separate_shader_objects : require

        uniform sampler2D InSampler;

        layout(location = 0) in vec2 texCoord;

        layout(std140) uniform SamplerInfo {
            vec2 OutSize;
            vec2 InSize;
        };

        layout(std140) uniform FlashConfig {
            float Whiteout;
            float Desat;
            float Blur;
            float Glow;
        };

        layout(location = 0) out vec4 fragColor;

        void main() {
            vec2 px = 1.0 / OutSize;
            float radius = Blur * 16.0;
            vec3 acc = texture(InSampler, texCoord).rgb;
            float total = 1.0;
            for (int i = 1; i <= 12; i++) {
                float a = float(i) * 2.399963;
                float d = sqrt(float(i) / 12.0) * radius;
                float w = 1.0 - 0.5 * float(i) / 12.0;
                acc += texture(InSampler, texCoord + vec2(cos(a), sin(a)) * d * px).rgb * w;
                total += w;
            }
            vec3 c = acc / total;
            float luma = dot(c, vec3(0.299, 0.587, 0.114));
            c = mix(c, vec3(luma), Desat);
            c += vec3(1.0, 0.97, 0.9) * Glow * (1.0 - 0.8 * length(texCoord - 0.5));
            c = mix(c, vec3(1.0, 0.99, 0.96), Whiteout);
            fragColor = vec4(c, 1.0);
        }
        """;

    /** The pack files: path -> bytes. */
    static Map<String, byte[]> files() {
        Map<String, byte[]> out = new LinkedHashMap<>();
        out.put("assets/firearms/shaders/post/flashbang.fsh", SHADER.getBytes(StandardCharsets.UTF_8));
        for (int level = 1; level <= LEVELS; level++) out.put("assets/firearms/post_effect/flash_" + level + ".json", effect(level).getBytes(StandardCharsets.UTF_8));
        return out;
    }

    static String effect(int level) {
        double[] p = params(level);
        JsonObject root = new JsonObject();
        JsonObject targets = new JsonObject();
        targets.add("swap", new JsonObject());
        root.add("targets", targets);
        JsonArray passes = new JsonArray();

        JsonObject tint = new JsonObject();
        tint.addProperty("vertex_shader", "minecraft:core/screenquad");
        tint.addProperty("fragment_shader", "firearms:post/flashbang");
        tint.add("inputs", input("minecraft:main"));
        tint.addProperty("output", "swap");
        JsonObject uniforms = new JsonObject();
        JsonArray cfg = new JsonArray();
        cfg.add(uniform("Whiteout", p[0]));
        cfg.add(uniform("Desat", p[1]));
        cfg.add(uniform("Blur", p[2]));
        cfg.add(uniform("Glow", p[3]));
        uniforms.add("FlashConfig", cfg);
        tint.add("uniforms", uniforms);
        passes.add(tint);

        JsonObject blit = new JsonObject();
        blit.addProperty("vertex_shader", "minecraft:core/screenquad");
        blit.addProperty("fragment_shader", "minecraft:post/blit");
        blit.add("inputs", input("swap"));
        JsonObject blitUniforms = new JsonObject();
        JsonArray bc = new JsonArray();
        JsonObject mod = new JsonObject();
        mod.addProperty("name", "ColorModulate");
        mod.addProperty("type", "vec4");
        JsonArray ones = new JsonArray();
        for (int i = 0; i < 4; i++) ones.add(1.0);
        mod.add("value", ones);
        bc.add(mod);
        blitUniforms.add("BlitConfig", bc);
        blit.add("uniforms", blitUniforms);
        blit.addProperty("output", "minecraft:main");
        passes.add(blit);
        root.add("passes", passes);
        return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root);
    }

    private static JsonArray input(String target) {
        JsonObject in = new JsonObject();
        in.addProperty("sampler_name", "In");
        in.addProperty("target", target);
        JsonArray a = new JsonArray();
        a.add(in);
        return a;
    }

    private static JsonObject uniform(String name, double value) {
        JsonObject u = new JsonObject();
        u.addProperty("name", name);
        u.addProperty("type", "float");
        u.addProperty("value", Math.round(value * 10000) / 10000.0);
        return u;
    }
}
