#version 330
#extension GL_ARB_separate_shader_objects : require

// Reprojects six cube faces into a panorama with the CPU's exact integer arithmetic (see
// PanoramaSampling), so the output equals the CPU reprojection byte for byte. The faces lie
// side by side in atlases, as rendered: rows bottom-up, so a top-down face row r is atlas
// row FaceSize - 1 - r.

uniform sampler2D FaceColor;
uniform sampler2D FaceVisible;
uniform sampler2D FaceUnoccluded;
// Two ints per output pixel, row-major from the top: face << 20 | qx, then qy.
uniform usamplerBuffer Samples;

layout(std140) uniform ReprojectInfo {
    // Output width, output height, face size.
    ivec4 Size;
};

layout(location = 0) out vec4 colorAndVisible;
layout(location = 1) out vec4 unoccludedOut;

ivec2 atlasTexel(int face, int column, int row) {
    return ivec2(face * Size.z + column, Size.z - 1 - row);
}

ivec3 rgbAt(int face, int column, int row) {
    return ivec3(round(texelFetch(FaceColor, atlasTexel(face, column, row), 0).rgb * 255.0));
}

void main() {
    int width = Size.x;
    int height = Size.y;
    int size = Size.z;
    int last = size - 1;
    int x = int(gl_FragCoord.x);
    // The framebuffer's rows run bottom-up; readback flips them.
    int y = height - 1 - int(gl_FragCoord.y);
    uvec2 entry = texelFetch(Samples, y * width + x).rg;
    int face = int(entry.r >> 20u);
    int qx = int(entry.r & 0xFFFFFu);
    int qy = int(entry.g);

    ivec2 nearest = atlasTexel(face, min(qx >> 8, last), min(qy >> 8, last));
    float visible = texelFetch(FaceVisible, nearest, 0).r;
    float unoccluded = texelFetch(FaceUnoccluded, nearest, 0).r;

    int sx = qx - 128;
    int sy = qy - 128;
    int x0 = sx >> 8;
    int y0 = sy >> 8;
    int wx = sx & 255;
    int wy = sy & 255;
    if (x0 < 0) {
        x0 = 0;
        wx = 0;
    } else if (x0 >= last) {
        x0 = last;
        wx = 0;
    }

    if (y0 < 0) {
        y0 = 0;
        wy = 0;
    } else if (y0 >= last) {
        y0 = last;
        wy = 0;
    }

    int x1 = min(x0 + 1, last);
    int y1 = min(y0 + 1, last);
    ivec3 value = rgbAt(face, x0, y0) * ((256 - wx) * (256 - wy))
        + rgbAt(face, x1, y0) * (wx * (256 - wy))
        + rgbAt(face, x0, y1) * ((256 - wx) * wy)
        + rgbAt(face, x1, y1) * (wx * wy);
    ivec3 rgb = (value + 32768) >> 16;

    colorAndVisible = vec4(vec3(rgb) / 255.0, visible);
    unoccludedOut = vec4(unoccluded, 0.0, 0.0, 1.0);
}
