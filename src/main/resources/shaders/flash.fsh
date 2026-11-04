#version 330
#extension GL_ARB_separate_shader_objects : require

// Firearms flashbang: the whole screen washed to white by Amount (0..1). One post effect per level (flash_1 .. flash_N)
// is in the pack; the plugin switches a blinded player through them as the flash fades.
uniform sampler2D InSampler;

layout(std140) uniform FlashConfig {
    float Amount;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 c = texture(InSampler, texCoord);
    fragColor = vec4(mix(c.rgb, vec3(1.0), Amount), 1.0);
}
