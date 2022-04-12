#include "terrain_fog.frag"
#include "terrain_buffers.frag"
#include "terrain_textures.frag"
#include "terrain_input.frag"

layout(early_fragment_tests) in;

void main() {
    vec4 frag_diffuse = texture(tex_diffuse, vs_out.tex_diffuse_coord);

    vec4 frag_light = texture(tex_light, vs_out.tex_light_coord);
    vec4 frag_mixed = vec4((frag_diffuse.rgb * frag_light.rgb) * vs_out.color * vs_out.shade, frag_diffuse.a);

    frag_final = _apply_fog(frag_mixed, vs_out.fog_depth, fog_color, fog_start, fog_end);
}