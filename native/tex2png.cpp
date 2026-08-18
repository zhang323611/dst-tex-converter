// tex2png — 解析饥荒 KTEX，解压 DXT1/3/5 或 RGBA/RGB，输出 PNG
// 用法: tex2png input.tex output.png
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <vector>
#include <string>
#include <algorithm>

#include "squish.h"

#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

static std::vector<uint8_t> readFile(const char* path) {
    FILE* f = fopen(path, "rb");
    if (!f) { fprintf(stderr, "无法打开 %s\n", path); exit(1); }
    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    fseek(f, 0, SEEK_SET);
    std::vector<uint8_t> buf(sz);
    if (sz > 0) fread(buf.data(), 1, sz, f);
    fclose(f);
    return buf;
}

static uint16_t rd16(const uint8_t* p) { return (uint16_t)(p[0] | (p[1] << 8)); }
static uint32_t rd32(const uint8_t* p) { return (uint32_t)(p[0] | (p[1] << 8) | (p[2] << 16) | ((uint32_t)p[3] << 24)); }

int tex2png_main(int argc, char** argv) {
    if (argc < 3) {
        fprintf(stderr, "用法: tex2png input.tex output.png\n");
        return 1;
    }
    std::vector<uint8_t> tex = readFile(argv[1]);
    if (tex.size() < 18 || memcmp(tex.data(), "KTEX", 4) != 0) {
        fprintf(stderr, "不是有效的 KTEX 文件\n");
        return 1;
    }
    uint32_t hdr = rd32(tex.data() + 4);
    int compression = (hdr >> 4) & 0x1F;
    int mipmapCount = (hdr >> 13) & 0x1F;
    if (mipmapCount < 1) {
        fprintf(stderr, "无 mipmap\n");
        return 1;
    }

    // 第一个 mipmap 的 pre（偏移 8）
    int w = rd16(tex.data() + 8);
    int h = rd16(tex.data() + 10);
    // int pitch = rd16(tex.data() + 12);
    uint32_t datasz = rd32(tex.data() + 14);

    // 数据在所有 mipmap pre 之后（8 + mipmapCount*10）
    const uint8_t* data = tex.data() + 8 + (size_t)mipmapCount * 10;

    fprintf(stderr, "KTEX %dx%d compression=%d datasz=%u\n", w, h, compression, datasz);

    std::vector<uint8_t> rgba((size_t)w * h * 4);

    int squishFlags = 0;
    switch (compression) {
        case 0: squishFlags = squish::kDxt1; break;
        case 1: squishFlags = squish::kDxt3; break;
        case 2: squishFlags = squish::kDxt5; break;
        case 4: // RGBA 未压缩，直接复制
            if (datasz >= (uint32_t)w * h * 4) memcpy(rgba.data(), data, (size_t)w * h * 4);
            else { fprintf(stderr, "RGBA 数据长度不足\n"); return 1; }
            break;
        case 5: { // RGB 未压缩，扩展 alpha
            if (datasz >= (uint32_t)w * h * 3) {
                for (int i = 0; i < w * h; i++) {
                    rgba[i*4+0] = data[i*3+0];
                    rgba[i*4+1] = data[i*3+1];
                    rgba[i*4+2] = data[i*3+2];
                    rgba[i*4+3] = 255;
                }
            } else { fprintf(stderr, "RGB 数据长度不足\n"); return 1; }
            break;
        }
        case 24:
            fprintf(stderr, "错误: 这是 ASTC 压缩(comp=24)，不是 DXT，无法解压为 PNG\n");
            return 1;
        default:
            fprintf(stderr, "不支持的 compression=%d\n", compression);
            return 1;
    }

    if (squishFlags != 0) {
        squish::DecompressImage(rgba.data(), w, h, data, squishFlags);
    }

    if (!stbi_write_png(argv[2], w, h, 4, rgba.data(), w * 4)) {
        fprintf(stderr, "PNG 写入失败\n");
        return 1;
    }
    fprintf(stderr, "已输出 %s (%dx%d)\n", argv[2], w, h);
    return 0;
}
