cmake_minimum_required(VERSION 3.22.1)
project(minefade_native_engine LANGUAGES C CXX)

include(FetchContent)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build third-party dependencies as static libraries" FORCE)

# RmlUi's default font engine uses FreeType. Disable optional decoders so the Android
# build remains self-contained and does not depend on zlib/png/harfbuzz system packages.
set(FT_DISABLE_ZLIB ON CACHE BOOL "Disable system zlib" FORCE)
set(FT_DISABLE_BZIP2 ON CACHE BOOL "Disable bzip2" FORCE)
set(FT_DISABLE_PNG ON CACHE BOOL "Disable PNG" FORCE)
set(FT_DISABLE_HARFBUZZ ON CACHE BOOL "Disable HarfBuzz" FORCE)
set(FT_DISABLE_BROTLI ON CACHE BOOL "Disable Brotli" FORCE)
set(SKIP_INSTALL_ALL ON CACHE BOOL "Skip FreeType install rules" FORCE)

FetchContent_Declare(
    freetype
    GIT_REPOSITORY https://github.com/freetype/freetype.git
    GIT_TAG VER-2-13-3
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(freetype)

if(TARGET freetype-interface AND NOT TARGET Freetype::Freetype)
    add_library(Freetype::Freetype ALIAS freetype-interface)
elseif(TARGET freetype AND NOT TARGET Freetype::Freetype)
    add_library(Freetype::Freetype ALIAS freetype)
endif()

set(RMLUI_SAMPLES OFF CACHE BOOL "Do not build desktop samples" FORCE)
set(RMLUI_FONT_ENGINE freetype CACHE STRING "Use FreeType for RmlUi text rendering" FORCE)
set(RMLUI_THIRDPARTY_CONTAINERS ON CACHE BOOL "Enable RmlUi optimized containers" FORCE)
set(RMLUI_PRECOMPILED_HEADERS OFF CACHE BOOL "Avoid PCH memory overhead on Android" FORCE)
set(RMLUI_COMPILER_OPTIONS OFF CACHE BOOL "Use parent project compiler options" FORCE)
set(RMLUI_WARNINGS_AS_ERRORS OFF CACHE BOOL "Do not fail third-party build on warnings" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "Disable RmlUi tests" FORCE)

FetchContent_Declare(
    rmlui
    GIT_REPOSITORY https://github.com/mikke89/RmlUi.git
    GIT_TAG 6.3
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(rmlui)

if(NOT TARGET RmlUi::Core)
    message(FATAL_ERROR "RmlUi 6.3 did not provide the RmlUi::Core target")
endif()

add_library(minefade_native SHARED
    NativeEngine.cpp
    engine/Engine.cpp
    game/Player.cpp
    world/VoxelWorld.cpp
    blocks/BlockRegistry.cpp
    ui/RmlUiOpenGLES.cpp
    ui/RmlUiRuntime.cpp
)

target_include_directories(minefade_native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_compile_features(minefade_native PRIVATE cxx_std_20)
target_compile_options(minefade_native PRIVATE
    -Wall -Wextra -Wpedantic -Wconversion -Wshadow
    -ffunction-sections -fdata-sections
)
target_link_options(minefade_native PRIVATE -Wl,--gc-sections)

find_library(log-lib log REQUIRED)
find_library(android-lib android REQUIRED)
find_library(gles-lib GLESv3 REQUIRED)
find_library(m-lib m REQUIRED)

target_link_libraries(minefade_native PRIVATE
    RmlUi::Core
    Freetype::Freetype
    ${gles-lib}
    ${android-lib}
    ${log-lib}
    ${m-lib}
)

# The RmlUi dependency is pinned to 6.3 and FreeType to 2.13.3 for reproducible builds.
message(STATUS "Minefade: C++20, OpenGL ES 3, RmlUi 6.3, FreeType 2.13.3")
