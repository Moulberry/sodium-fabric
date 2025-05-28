#version 450

uniform sampler2D sampler0;
out vec4 fragColor;
in vec2 uv;

void main() {
    vec4 depths = textureGather(sampler0, uv, 0); // todo: can we use textureGather? Isn't it GL4.0+?
    gl_FragDepth = max(max(depths.x, depths.y), max(depths.z, depths.w));
}