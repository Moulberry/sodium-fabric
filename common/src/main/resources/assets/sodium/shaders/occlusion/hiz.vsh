#version 450

in vec3 corner;
out vec2 uv;

void main() {
    uv = corner.xy;
    gl_Position = vec4(corner.xy*2-1, 0, 1);
}