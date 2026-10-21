# native 构建：libastcenc.so（ASTC 编解码）+ libtex2png.so（KTEX/DXT 解码）
# 依赖仓库根目录下的 third_party/astcenc 与 third_party/squish
cmake_minimum_required(VERSION 3.22.1)
project(dsttex CXX)

set(ROOT_DIR   ${CMAKE_CURRENT_SOURCE_DIR}/../../../..)
set(ASTC_DIR   ${ROOT_DIR}/third_party/astcenc/Source)
set(SQUISH_DIR ${ROOT_DIR}/third_party/squish)
set(NATIVE_DIR ${CMAKE_CURRENT_SOURCE_DIR})

# astcenccli_*.cpp 需要一个版本头文件；这里生成，避免依赖它的 CMake 工程
set(GEN_DIR ${CMAKE_CURRENT_BINARY_DIR}/gen)
file(MAKE_DIRECTORY ${GEN_DIR})
file(WRITE ${GEN_DIR}/astcenccli_version.h
"#ifndef ASTCENCCLI_VERSION_INCLUDED\n#define ASTCENCCLI_VERSION_INCLUDED\n#define VERSION_STRING \"5.0.0\"\n#define YEAR_STRING \"2026\"\n#endif\n")

# ---------------- libastcenc ----------------
file(GLOB ASTC_SOURCES ${ASTC_DIR}/*.cpp)
# 排除 CLI 入口（它定义 main/veneer，我们要自己从 JNI 调 astcenc_main）
list(FILTER ASTC_SOURCES EXCLUDE REGEX "astcenccli_entry\\.cpp$")

add_library(astcenc SHARED ${ASTC_SOURCES} ${NATIVE_DIR}/jni_astcenc.cpp)

target_compile_definitions(astcenc PRIVATE
        ASTCENC_NEON=1
        ASTCENC_SVE=0
        ASTCENC_SSE=0
        ASTCENC_AVX=0
        ASTCENC_POPCNT=0
        ASTCENC_F16C=0)

target_include_directories(astcenc PRIVATE ${ASTC_DIR} ${GEN_DIR})
set_target_properties(astcenc PROPERTIES
        CXX_STANDARD 14
        CXX_STANDARD_REQUIRED ON
        CXX_EXTENSIONS OFF)
target_link_libraries(astcenc PRIVATE m)

# ---------------- libtex2png ----------------
add_library(tex2png SHARED
        ${NATIVE_DIR}/tex2png.cpp
        ${NATIVE_DIR}/jni_tex2png.cpp
        ${SQUISH_DIR}/alpha.cpp
        ${SQUISH_DIR}/clusterfit.cpp
        ${SQUISH_DIR}/colourblock.cpp
        ${SQUISH_DIR}/colourfit.cpp
        ${SQUISH_DIR}/colourset.cpp
        ${SQUISH_DIR}/maths.cpp
        ${SQUISH_DIR}/rangefit.cpp
        ${SQUISH_DIR}/singlecolourfit.cpp
        ${SQUISH_DIR}/squish.cpp)

target_include_directories(tex2png PRIVATE ${SQUISH_DIR} ${NATIVE_DIR})
set_target_properties(tex2png PROPERTIES
        CXX_STANDARD 14
        CXX_STANDARD_REQUIRED ON
        CXX_EXTENSIONS OFF)
target_link_libraries(tex2png PRIVATE m)
