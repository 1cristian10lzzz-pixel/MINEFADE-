#include "RmlUiOpenGLES.h"

#include <android/log.h>

#include <algorithm>
#include <cstddef>
#include <string>
#include <cstring>

namespace minefade {
namespace {
constexpr const char* kTag = "MinefadeRmlUi";
#define UI_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, kTag, __VA_ARGS__)

constexpr char kVertexShader[] = R"(
#version 300 es
precision highp float;
layout(location=0) in vec2 aPosition;
layout(location=1) in vec4 aColour;
layout(location=2) in vec2 aTexCoord;
uniform vec2 uViewport;
uniform vec2 uTranslation;
out vec4 vColour;
out vec2 vTexCoord;
void main() {
    vec2 p = aPosition + uTranslation;
    vec2 clip = vec2((p.x / uViewport.x) * 2.0 - 1.0,
                     1.0 - (p.y / uViewport.y) * 2.0);
    gl_Position = vec4(clip, 0.0, 1.0);
    vColour = aColour;
    vTexCoord = aTexCoord;
}
)";

constexpr char kFragmentShader[] = R"(
#version 300 es
precision mediump float;
in vec4 vColour;
in vec2 vTexCoord;
uniform sampler2D uTexture;
uniform int uTextured;
out vec4 outColour;
void main() {
    vec4 texel = uTextured == 1 ? texture(uTexture, vTexCoord) : vec4(1.0);
    outColour = vColour * texel;
}
)";
}

RmlUiOpenGLES::~RmlUiOpenGLES() { Shutdown(); }

GLuint RmlUiOpenGLES::CompileShader(GLenum type, const char* source) {
    const GLuint shader = glCreateShader(type);
    if (shader == 0) return 0;
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint success = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &success);
    if (success != GL_TRUE) {
        GLint length = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &length);
        std::string log(static_cast<std::size_t>(std::max(length, 1)), '\0');
        glGetShaderInfoLog(shader, length, nullptr, log.data());
        UI_LOGE("RmlUi shader compile failed: %s", log.c_str());
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

bool RmlUiOpenGLES::Initialise() {
    Shutdown();
    const GLuint vertex = CompileShader(GL_VERTEX_SHADER, kVertexShader);
    const GLuint fragment = CompileShader(GL_FRAGMENT_SHADER, kFragmentShader);
    if (vertex == 0 || fragment == 0) {
        if (vertex != 0) glDeleteShader(vertex);
        if (fragment != 0) glDeleteShader(fragment);
        return false;
    }
    program_ = glCreateProgram();
    glAttachShader(program_, vertex);
    glAttachShader(program_, fragment);
    glLinkProgram(program_);
    glDeleteShader(vertex);
    glDeleteShader(fragment);

    GLint linked = GL_FALSE;
    glGetProgramiv(program_, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        GLint length = 0;
        glGetProgramiv(program_, GL_INFO_LOG_LENGTH, &length);
        std::string log(static_cast<std::size_t>(std::max(length, 1)), '\0');
        glGetProgramInfoLog(program_, length, nullptr, log.data());
        UI_LOGE("RmlUi shader link failed: %s", log.c_str());
        glDeleteProgram(program_);
        program_ = 0;
        return false;
    }

    viewportLocation_ = glGetUniformLocation(program_, "uViewport");
    translationLocation_ = glGetUniformLocation(program_, "uTranslation");
    texturedLocation_ = glGetUniformLocation(program_, "uTextured");
    samplerLocation_ = glGetUniformLocation(program_, "uTexture");
    if (viewportLocation_ < 0 || translationLocation_ < 0 || texturedLocation_ < 0 || samplerLocation_ < 0) {
        UI_LOGE("RmlUi shader uniforms missing");
        Shutdown();
        return false;
    }
    glUseProgram(program_);
    glUniform1i(samplerLocation_, 0);
    glUseProgram(0);
    return true;
}

void RmlUiOpenGLES::ReleaseGeometryObject(Geometry& geometry) {
    if (geometry.ebo != 0) glDeleteBuffers(1, &geometry.ebo);
    if (geometry.vbo != 0) glDeleteBuffers(1, &geometry.vbo);
    if (geometry.vao != 0) glDeleteVertexArrays(1, &geometry.vao);
    geometry = {};
}

void RmlUiOpenGLES::Shutdown() {
    for (auto& item : geometries_) ReleaseGeometryObject(item.second);
    geometries_.clear();
    for (const auto& item : textures_) {
        const GLuint texture = item.second;
        if (texture != 0) glDeleteTextures(1, &texture);
    }
    textures_.clear();
    blockAtlasPixels_.clear();
    blockAtlasWidth_=blockAtlasHeight_=0;
    if (program_ != 0) glDeleteProgram(program_);
    program_ = 0;
    viewportLocation_ = translationLocation_ = texturedLocation_ = samplerLocation_ = -1;
}

void RmlUiOpenGLES::SetViewport(int width, int height) {
    width_ = std::max(width, 1);
    height_ = std::max(height, 1);
}

void RmlUiOpenGLES::SetBlockAtlas(const std::uint8_t* pixels,std::size_t bytes,
                                  int width,int height) {
    if(pixels==nullptr||width<=0||height<=0) {
        blockAtlasPixels_.clear();
        blockAtlasWidth_=blockAtlasHeight_=0;
        return;
    }
    const std::size_t expected=static_cast<std::size_t>(width)*
        static_cast<std::size_t>(height)*4u;
    if(bytes<expected) {
        UI_LOGE("RmlUi block atlas is truncated: %zu < %zu",bytes,expected);
        blockAtlasPixels_.clear();
        blockAtlasWidth_=blockAtlasHeight_=0;
        return;
    }
    blockAtlasPixels_.assign(pixels,pixels+expected);
    blockAtlasWidth_=width;
    blockAtlasHeight_=height;
}

Rml::CompiledGeometryHandle RmlUiOpenGLES::CompileGeometry(
    Rml::Span<const Rml::Vertex> vertices, Rml::Span<const int> indices) {
    if (vertices.empty() || indices.empty()) return 0;
    Geometry geometry;
    glGenVertexArrays(1, &geometry.vao);
    glGenBuffers(1, &geometry.vbo);
    glGenBuffers(1, &geometry.ebo);
    if (geometry.vao == 0 || geometry.vbo == 0 || geometry.ebo == 0) {
        ReleaseGeometryObject(geometry);
        return 0;
    }

    std::vector<std::uint32_t> packedIndices;
    packedIndices.reserve(indices.size());
    for (const int index : indices) {
        if (index < 0 || static_cast<std::size_t>(index) >= vertices.size()) {
            ReleaseGeometryObject(geometry);
            return 0;
        }
        packedIndices.push_back(static_cast<std::uint32_t>(index));
    }

    glBindVertexArray(geometry.vao);
    glBindBuffer(GL_ARRAY_BUFFER, geometry.vbo);
    glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(vertices.size() * sizeof(Rml::Vertex)),
                 vertices.data(), GL_STATIC_DRAW);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, geometry.ebo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER,
                 static_cast<GLsizeiptr>(packedIndices.size() * sizeof(std::uint32_t)),
                 packedIndices.data(), GL_STATIC_DRAW);

    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, sizeof(Rml::Vertex),
                          reinterpret_cast<const void*>(offsetof(Rml::Vertex, position)));
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 4, GL_UNSIGNED_BYTE, GL_TRUE, sizeof(Rml::Vertex),
                          reinterpret_cast<const void*>(offsetof(Rml::Vertex, colour)));
    glEnableVertexAttribArray(2);
    glVertexAttribPointer(2, 2, GL_FLOAT, GL_FALSE, sizeof(Rml::Vertex),
                          reinterpret_cast<const void*>(offsetof(Rml::Vertex, tex_coord)));
    glBindVertexArray(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    geometry.indexCount = static_cast<GLsizei>(packedIndices.size());
    const std::uintptr_t id = nextGeometry_++;
    geometries_.emplace(id, geometry);
    return static_cast<Rml::CompiledGeometryHandle>(id);
}

void RmlUiOpenGLES::RenderGeometry(Rml::CompiledGeometryHandle handle, Rml::Vector2f translation,
                                   Rml::TextureHandle textureHandle) {
    const auto geometry = geometries_.find(static_cast<std::uintptr_t>(handle));
    if (geometry == geometries_.end() || program_ == 0) return;

    GLuint texture = 0;
    if (textureHandle != 0) {
        const auto found = textures_.find(static_cast<std::uintptr_t>(textureHandle));
        if (found != textures_.end()) texture = found->second;
    }

    glUseProgram(program_);
    glUniform2f(viewportLocation_, static_cast<float>(width_), static_cast<float>(height_));
    glUniform2f(translationLocation_, translation.x, translation.y);
    glUniform1i(texturedLocation_, texture != 0 ? 1 : 0);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glBindVertexArray(geometry->second.vao);
    glDrawElements(GL_TRIANGLES, geometry->second.indexCount, GL_UNSIGNED_INT, nullptr);
    glBindVertexArray(0);
    glBindTexture(GL_TEXTURE_2D, 0);
    glUseProgram(0);
}

void RmlUiOpenGLES::ReleaseGeometry(Rml::CompiledGeometryHandle handle) {
    const auto found = geometries_.find(static_cast<std::uintptr_t>(handle));
    if (found == geometries_.end()) return;
    ReleaseGeometryObject(found->second);
    geometries_.erase(found);
}

Rml::TextureHandle RmlUiOpenGLES::LoadTexture(Rml::Vector2i& textureDimensions,const Rml::String& source) {
    // Block thumbnails are cropped from Minefade's runtime atlas; other external
    // bitmap paths remain intentionally unsupported until an asset decoder is added.
    constexpr const char* prefix="minefade://block/";
    const std::string uri=source;
    if(uri.rfind(prefix,0)!=0) {
        UI_LOGE("External RmlUi texture is not supported: %s",source.c_str());
        return 0;
    }
    if(blockAtlasPixels_.empty()||blockAtlasWidth_<576||blockAtlasHeight_<18)return 0;
    int blockId=-1;
    try {
        std::size_t parsed=0;
        blockId=std::stoi(uri.substr(std::strlen(prefix)),&parsed);
        if(parsed!=uri.size()-std::strlen(prefix)||blockId<0)return 0;
    } catch(...) { return 0; }
    constexpr int columns=32,cell=18,tileSize=16;
    const int tile=blockId*3+1; // side-facing thumbnail for the block.
    const int column=tile%columns,row=tile/columns;
    const int x0=column*cell+1,y0=row*cell+1;
    if(x0+tileSize>blockAtlasWidth_||y0+tileSize>blockAtlasHeight_)return 0;
    std::vector<Rml::byte> pixels(static_cast<std::size_t>(tileSize*tileSize*4));
    for(int y=0;y<tileSize;++y) {
        const std::size_t sourceOffset=(static_cast<std::size_t>(y0+y)*
            static_cast<std::size_t>(blockAtlasWidth_)+static_cast<std::size_t>(x0))*4u;
        const std::size_t destinationOffset=static_cast<std::size_t>(y*tileSize*4);
        std::memcpy(pixels.data()+destinationOffset,
                    blockAtlasPixels_.data()+sourceOffset,static_cast<std::size_t>(tileSize*4));
    }
    textureDimensions={tileSize,tileSize};
    return GenerateTexture(Rml::Span<const Rml::byte>(pixels.data(),pixels.size()),textureDimensions);
}

Rml::TextureHandle RmlUiOpenGLES::GenerateTexture(Rml::Span<const Rml::byte> source,
                                                   Rml::Vector2i sourceDimensions) {
    if (source.empty() || sourceDimensions.x <= 0 || sourceDimensions.y <= 0) return 0;
    const std::size_t expected = static_cast<std::size_t>(sourceDimensions.x) *
                                 static_cast<std::size_t>(sourceDimensions.y) * 4u;
    if (source.size() < expected) return 0;
    GLuint texture = 0;
    glGenTextures(1, &texture);
    if (texture == 0) return 0;
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, sourceDimensions.x, sourceDimensions.y,
                 0, GL_RGBA, GL_UNSIGNED_BYTE, source.data());
    glBindTexture(GL_TEXTURE_2D, 0);
    const std::uintptr_t id = nextTexture_++;
    textures_.emplace(id, texture);
    return static_cast<Rml::TextureHandle>(id);
}

void RmlUiOpenGLES::ReleaseTexture(Rml::TextureHandle handle) {
    const auto found = textures_.find(static_cast<std::uintptr_t>(handle));
    if (found == textures_.end()) return;
    glDeleteTextures(1, &found->second);
    textures_.erase(found);
}

void RmlUiOpenGLES::EnableScissorRegion(bool enable) {
    scissorEnabled_ = enable;
    if (enable) glEnable(GL_SCISSOR_TEST);
    else glDisable(GL_SCISSOR_TEST);
}

void RmlUiOpenGLES::SetScissorRegion(Rml::Rectanglei region) {
    const int left = std::clamp(region.Left(), 0, width_);
    const int top = std::clamp(region.Top(), 0, height_);
    const int right = std::clamp(region.Right(), left, width_);
    const int bottom = std::clamp(region.Bottom(), top, height_);
    const int width = std::max(right - left, 0);
    const int height = std::max(bottom - top, 0);
    glScissor(left, std::max(height_ - bottom, 0), width, height);
}

} // namespace minefade
