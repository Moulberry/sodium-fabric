#version 430

// Modified version of https://github.com/ARM-software/opengl-es-sdk-for-android/blob/c3caf759bb2e71fa9a118b3e3abd996cf00e660a/samples/advanced_samples/OcclusionCulling/assets/hiz_cull_no_lod.cs
/* Copyright (c) 2014-2017, ARM Limited and Contributors
 *
 * SPDX-License-Identifier: MIT
 *
 * Permission is hereby granted, free of charge,
 * to any person obtaining a copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software,
 * and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */


layout(local_size_x = 64) in;

uniform mat4 projectionMatrix;
uniform mat4 modelViewMatrix;

layout(location = 0) uniform int inputCount;
layout(binding = 0) uniform sampler2DShadow depthSampler;

layout(std430, binding = 0) buffer InputData
{
    readonly ivec4 sections[];
} inputData;

layout(std430, binding = 1) buffer OutputData
{
    writeonly int data[];
} outputData;

void main() {
    uint id = gl_GlobalInvocationID.x;
    if (id >= inputCount) {
        return;
    }

    ivec3 sectionOffset = inputData.sections[id].xyz;
    vec3 worldCenter = vec3(sectionOffset << 4) + vec3(8.0, 8.0, 8.0);
    vec3 view_center = (modelViewMatrix * vec4(worldCenter, 1.0)).xyz;
    float radius = 16.0; // more than enough to cover the section
    float nearest_z = view_center.z + radius;

    if (nearest_z >= 0.0) {
        outputData.data[id] = 1; // VISIBLE
        return;
    }

    float az_plane_horiz_length = length(view_center.xz);
    float az_plane_vert_length = length(view_center.yz);
    vec2 az_plane_horiz_norm = view_center.xz / az_plane_horiz_length;
    vec2 az_plane_vert_norm = view_center.yz / az_plane_vert_length;

    vec2 t = sqrt(vec2(az_plane_horiz_length, az_plane_vert_length) * vec2(az_plane_horiz_length, az_plane_vert_length) - radius * radius);
    vec4 w = vec4(t, radius, radius) / vec2(az_plane_horiz_length, az_plane_vert_length).xyxy;

    vec4 horiz_cos_sin = az_plane_horiz_norm.xyyx * t.x * vec4(w.xx, -w.z, w.z);
    vec4 vert_cos_sin  = az_plane_vert_norm.xyyx * t.y * vec4(w.yy, -w.w, w.w);

    vec2 horiz0 = horiz_cos_sin.xy + horiz_cos_sin.zw;
    vec2 horiz1 = horiz_cos_sin.xy - horiz_cos_sin.zw;
    vec2 vert0  = vert_cos_sin.xy + vert_cos_sin.zw;
    vec2 vert1  = vert_cos_sin.xy - vert_cos_sin.zw;

    vec4 projected = -0.5 * vec4(projectionMatrix[0][0], projectionMatrix[0][0], projectionMatrix[1][1], projectionMatrix[1][1]) *
        vec4(horiz0.x, horiz1.x, vert0.x, vert1.x) /
        vec4(horiz0.y, horiz1.y, vert0.y, vert1.y) + 0.5;

    vec2 min_xy = projected.yw;
    vec2 max_xy = projected.xz;

    if (min_xy.x > 1.0 || min_xy.y > 1.0 || max_xy.x < 0.0 || max_xy.y < 0.0) {
        outputData.data[id] = 2; // UNKNOWN
        return;
    }

    bool isPotentiallyVisibleOffScreen = min_xy.x < 0.0 || min_xy.y < 0.0 || max_xy.x > 1.0 || max_xy.y > 1.0;
    min_xy = max(min_xy, 0.0);
    max_xy = min(max_xy, 1.0);

    vec2 zw = mat2(projectionMatrix[2].zw, projectionMatrix[3].zw) * vec2(nearest_z, 1.0);
    nearest_z = 0.5 * zw.x / zw.y + 0.5;

    vec2 diff_pix = (max_xy - min_xy) * vec2(textureSize(depthSampler, 0));
    float max_diff = max(max(diff_pix.x, diff_pix.y), 1.0);
    float lod = ceil(log2(max_diff)) - 1;
    vec2 mid_pix = 0.5 * (max_xy + min_xy);

    // maybe make each invocation do 32?
    float value = textureLod(depthSampler, vec3(mid_pix, nearest_z), lod);
    if (textureLod(depthSampler, vec3(mid_pix, nearest_z), lod) > 0.0) {
        outputData.data[id] = 1; // VISIBLE
    } else if (isPotentiallyVisibleOffScreen) {
        outputData.data[id] = 2; // UNKNOWN
    } else {
        outputData.data[id] = 0; // OCCLUDED
    }
}