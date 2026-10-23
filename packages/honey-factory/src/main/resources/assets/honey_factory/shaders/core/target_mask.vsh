#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:globals.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;

layout(location = 0) out vec2 texCoord0;

void main() {
    // Match terrain's camera-relative position: block-local vertex, integer offset of the
    // target from CameraBlockPos (ModelOffset), then the camera's fractional offset.
    vec3 pos = Position + ModelOffset + CameraOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    texCoord0 = UV0;
}
