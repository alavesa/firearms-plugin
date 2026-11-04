#version 330
#extension GL_ARB_separate_shader_objects : require

// Firearms: Minecraft 26.3's core item vertex shader (assets/minecraft/shaders/core/item.vsh) plus DYNAMIC VERTEX
// MANIPULATION for gun parts that carry the plugin's signal tint. Everything else renders exactly as vanilla.
//
// Signal: the plugin dyes a rig part (dyed_color) 0xA5 GG BB - GG = the game tick of the last shot (mod 256, latency
// compensated), BB = 0xC0 | strength (0..63). GameTime = (gameTime % 24000 + partialTick) / 24000 (GlobalSettingsUniform),
// so the GPU knows, every frame, how long ago the shot was: the recoil kick and the idle sway are computed here at the
// client's frame rate, with one item update per shot instead of frames. The tint itself is removed again (the part is
// drawn white = its own texture). Hands (skin tints) get the same motion from the server at tick rate.

#include <minecraft:light.glsl>
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
#include <minecraft:globals.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
#ifdef GLINT_SPECIAL
layout(location = 5) in vec2 UV3;
#endif
layout(location = 6) in vec3 Normal;

#ifndef OIT_ALPHA_ONLY
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
#endif
layout(location = 2) out vec4 vertexColor;
#ifndef OIT_ALPHA_ONLY
layout(location = 3) out vec4 lightMapColor;
layout(location = 4) out vec4 overlayColor;
#endif

layout(location = 5) out vec2 texCoord0;
#ifdef GLINT
layout(location = 6) out vec2 texCoordGlint;
#endif

// ---- Firearms signal ----------------------------------------------------------------------------------------
const float FA_RECOIL_TICKS = 7.0;      // the kick lasts this many ticks (mirrored by DisplayRig.fx on the server)
const float FA_KICK_PITCH = 0.07;       // radians up at full strength
const float FA_KICK_BACK = 0.06;        // blocks toward the camera at full strength

bool fa_signal(vec4 c, out float shotTick, out float strength) {
    float r = c.r * 255.0, g = c.g * 255.0, b = c.b * 255.0;
    if (abs(r - 165.0) > 0.6 || b < 191.5) return false;
    shotTick = floor(g + 0.5);
    strength = clamp((floor(b + 0.5) - 192.0) / 63.0, 0.0, 1.0);
    return true;
}

// The same curve as DisplayRig.fx (server, hands): kick = strength * sin(pi u) * (1 - 0.6 u), u = ticks / 7.
vec3 fa_fx(vec3 v, float shotTick, float strength) {
    float now = GameTime * 24000.0;
    float t = mod(now - shotTick, 256.0);
    float kick = 0.0;
    if (strength > 0.0 && t < FA_RECOIL_TICKS) {
        float u = t / FA_RECOIL_TICKS;
        kick = strength * sin(u * 3.14159265) * (1.0 - u * 0.6);
    }
    float pitch = kick * FA_KICK_PITCH + sin(now * 0.11) * 0.004 + sin(now * 0.037) * 0.003;
    float yaw = sin(now * 0.073) * 0.003;
    // view space: +X right, +Y up, -Z forward; rotate about the eye (X then Y), then pull toward the camera
    float cp = cos(pitch), sp = sin(pitch), cy = cos(yaw), sy = sin(yaw);
    vec3 a = vec3(v.x, v.y * cp - v.z * sp, v.y * sp + v.z * cp);
    vec3 b = vec3(a.x * cy + a.z * sy, a.y, -a.x * sy + a.z * cy);
    b.z += kick * FA_KICK_BACK;
    return b;
}
// --------------------------------------------------------------------------------------------------------------

void main() {
    vec4 color = Color;
    float shotTick, strength;
    bool signal = fa_signal(Color, shotTick, strength);
    vec4 view = ModelViewMat * vec4(Position, 1.0);
    if (signal) {
        color = vec4(1.0, 1.0, 1.0, Color.a);
        view.xyz = fa_fx(view.xyz, shotTick, strength);
    }
    gl_Position = ProjMat * view;

    #ifndef OIT_ALPHA_ONLY
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    #endif
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, color);
    #ifndef OIT_ALPHA_ONLY
    lightMapColor = sample_lightmap(Sampler2, UV2);
    overlayColor = texelFetch(Sampler1, UV1, 0);
    #endif

    texCoord0 = UV0;
    #ifdef GLINT
    #ifdef GLINT_SPECIAL
    texCoordGlint = (TextureMat * vec4(UV3, 0.0, 1.0)).xy;
    #else
    texCoordGlint = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
    #endif
    #endif
}
