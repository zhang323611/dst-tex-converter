// tex2png — 解析饥荒 KTEX，解压 DXT1/3/5 或 RGBA/RGB，输出 PNG
// 用法: tex2png input.tex output.png
//
// 注意: 这是被 JNI 调用的库代码, 绝不能调用 exit() —— 那会直接杀掉整个 App 进程。
// 所有失败都通过返回码上报(见下方 ERR_* 定义), 由 Java 侧抛异常。
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <vector>
#include <string>
#include <algorithm>
#include <new>

#include "squish.h"

#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

// 返回码
enum {
    ERR_OK          = 0,
    ERR_USAGE       = 2,   // 参数不对
    ERR_READ        = 3,   // 文件打不开/读失败
    ERR_NOT_KTEX    = 4,   // 不是 KTEX
    ERR_HEADER      = 5,   // 头部字段非法(尺寸/mipmap 数)
    ERR_TRUNCATED   = 6,   // 数据长度不足
    ERR_UNSUPPORTED = 7,   // 不支持的 compression
    ERR_TOO_LARGE   = 8,   // 尺寸过大, 拒绝分配
    ERR_PNG_WRITE   = 9    // PNG 写出失败
};

// 单边最大边长 / 最大像素数 —— 防止伪造头部导致 GB 级分配或越界读
static const uint32_t MAX_DIM = 16384;
static const long     MAX_PIXELS = 8192L * 8192L;

static int readFile(const char* path, std::vector<uint8_t>& out) {
    FILE* f = fopen(path, "rb");
    if (!f) { fprintf(stderr, "无法打开 %s\n", path); return ERR_READ; }
    if (fseek(f, 0, SEEK_END) != 0) { fclose(f); return ERR_READ; }
    long sz = ftell(f);
    if (sz < 0) { fclose(f); return ERR_READ; }
    if (fseek(f, 0, SEEK_SET) != 0) { fclose(f); return ERR_READ; }
    try {
        out.resize((size_t) sz);
    } catch (const std::bad_alloc&) {
        fclose(f);
        fprintf(stderr, "文件过大, 无法分配 %ld 字节\n", sz);
        return ERR_TOO_LARGE;
    }
    if (sz > 0 && fread(out.data(), 1, (size_t) sz, f) != (size_t) sz) {
        fclose(f);
        fprintf(stderr, "读取 %s 失败(可能被截断)\n", path);
        return ERR_READ;
    }
    fclose(f);
    return ERR_OK;
}

static uint16_t rd16(const uint8_t* p) { return (uint16_t)(p[0] | (p[1] << 8)); }
static uint32_t rd32(const uint8_t* p) {
    return (uint32_t)(p[0] | (p[1] << 8) | (p[2] << 16) | ((uint32_t)p[3] << 24));
}

// 按格式算压缩数据所需字节数(DXT: 4x4 块; 未压缩: 逐像素)
static uint64_t requiredBytes(int compression, uint32_t w, uint32_t h) {
    uint64_t bw = (w + 3) / 4, bh = (h + 3) / 4, bcount = bw * bh;
    switch (compression) {
        case 0: return bcount * 8;              // DXT1: 8 字节/块
        case 1:                                 // DXT3
        case 2: return bcount * 16;             // DXT5
        case 4: return (uint64_t) w * h * 4;    // RGBA
        case 5: return (uint64_t) w * h * 3;    // RGB
        default: return 0;
    }
}

int tex2png_main(int argc, char** argv) {
    if (argc < 3) {
        fprintf(stderr, "用法: tex2png input.tex output.png\n");
        return ERR_USAGE;
    }
    std::vector<uint8_t> tex;
    int rc = readFile(argv[1], tex);
    if (rc != ERR_OK) return rc;

    if (tex.size() < 18 || memcmp(tex.data(), "KTEX", 4) != 0) {
        fprintf(stderr, "不是有效的 KTEX 文件\n");
        return ERR_NOT_KTEX;
    }
    uint32_t hdr = rd32(tex.data() + 4);
    int compression = (hdr >> 4) & 0x1F;
    int mipmapCount = (hdr >> 13) & 0x1F;
    if (mipmapCount < 1) {
        fprintf(stderr, "无 mipmap\n");
        return ERR_HEADER;
    }

    // 第一个 mipmap 的 pre（偏移 8）
    uint32_t w = rd16(tex.data() + 8);
    uint32_t h = rd16(tex.data() + 10);
    uint32_t datasz = rd32(tex.data() + 14);

    if (w == 0 || h == 0 || w > MAX_DIM || h > MAX_DIM || (long) w * (long) h > MAX_PIXELS) {
        fprintf(stderr, "尺寸异常或过大: %ux%u\n", w, h);
        return ERR_TOO_LARGE;
    }

    // 数据在所有 mipmap pre 之后（8 + mipmapCount*10）—— 先校验该偏移本身在文件内
    const uint64_t dataOff = 8 + (uint64_t) mipmapCount * 10;
    if (dataOff > tex.size()) {
        fprintf(stderr, "mipmap 头超出文件长度(mip=%d)\n", mipmapCount);
        return ERR_HEADER;
    }

    if (compression == 24) {
        fprintf(stderr, "错误: 这是 ASTC 压缩(comp=24)，不是 DXT，无法解压为 PNG\n");
        return ERR_UNSUPPORTED;
    }

    const uint64_t need = requiredBytes(compression, w, h);
    if (need == 0) {
        fprintf(stderr, "不支持的 compression=%d\n", compression);
        return ERR_UNSUPPORTED;
    }
    // 关键: 校验文件里真的存在这么多字节, 绝不能让 squish 盲读越界。
    // datasz 只作参考(个别工具写 0 或不精确), 只要文件实际长度够就用实际长度。
    if (dataOff + need > tex.size()) {
        fprintf(stderr, "数据长度不足: 需要=%llu 文件=%zu\n",
                (unsigned long long) need, tex.size());
        return ERR_TRUNCATED;
    }
    if (datasz < need) {
        fprintf(stderr, "警告: datasz(%u) 小于格式所需(%llu), 按文件实际长度处理\n",
                datasz, (unsigned long long) need);
    }

    const uint8_t* data = tex.data() + (size_t) dataOff;
    fprintf(stderr, "KTEX %ux%u compression=%d datasz=%u\n", w, h, compression, datasz);

    const size_t px = (size_t) w * (size_t) h;
    std::vector<uint8_t> rgba;
    try {
        rgba.resize(px * 4);
    } catch (const std::bad_alloc&) {
        fprintf(stderr, "无法分配 %zu 字节像素缓冲\n", px * 4);
        return ERR_TOO_LARGE;
    }

    int squishFlags = 0;
    switch (compression) {
        case 0: squishFlags = squish::kDxt1; break;
        case 1: squishFlags = squish::kDxt3; break;
        case 2: squishFlags = squish::kDxt5; break;
        case 4: // RGBA 未压缩，直接复制(长度已校验)
            memcpy(rgba.data(), data, px * 4);
            break;
        case 5: { // RGB 未压缩，扩展 alpha
            for (size_t i = 0; i < px; i++) {
                rgba[i * 4 + 0] = data[i * 3 + 0];
                rgba[i * 4 + 1] = data[i * 3 + 1];
                rgba[i * 4 + 2] = data[i * 3 + 2];
                rgba[i * 4 + 3] = 255;
            }
            break;
        }
        default:
            return ERR_UNSUPPORTED;
    }

    if (squishFlags != 0) {
        squish::DecompressImage(rgba.data(), (int) w, (int) h, data, squishFlags);
    }

    if (!stbi_write_png(argv[2], (int) w, (int) h, 4, rgba.data(), (int) w * 4)) {
        fprintf(stderr, "PNG 写入失败\n");
        return ERR_PNG_WRITE;
    }
    fprintf(stderr, "已输出 %s (%ux%u)\n", argv[2], w, h);
    return ERR_OK;
}
