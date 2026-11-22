#pragma once

#include <RmlUi/Core.h>
#include <GLES3/gl3.h>

#include <cstdint>
#include <unordered_map>
#include <vector>

namespace minefade {

class RmlUiOpenGLES final : public Rml::RenderInterface {
public:
    RmlUiOpenGLES() = default;
    ~RmlUiOpenGLES() override;

    bool Initialise();
    void Shutdown();
    void SetViewport(int width, int height);

    Rml::CompiledGeometryHandle CompileGeometry(Rml::Span<const Rml::Vertex> vertices,
                                                Rml::Span<const int> indices) override;
    void RenderGeometry(Rml::CompiledGeometryHandle geometry, Rml::Vector2f translation,
                        Rml::TextureHandle texture) override;
    void ReleaseGeometry(Rml::CompiledGeometryHandle geometry) override;
    Rml::TextureHandle LoadTexture(Rml::Vector2i& textureDimensions, const Rml::String& source) override;
    Rml::TextureHandle GenerateTexture(Rml::Span<const Rml::byte> source,
                                       Rml::Vector2i sourceDimensions) override;
    void ReleaseTexture(Rml::TextureHandle texture) override;
    void EnableScissorRegion(bool enable) override;
    void SetScissorRegion(Rml::Rectanglei region) override;

private:
    struct Geometry {
        GLuint vao{0};
        GLuint vbo{0};
        GLuint ebo{0};
        GLsizei indexCount{0};
    };

    GLuint CompileShader(GLenum type, const char* source);
    void ReleaseGeometryObject(Geometry& geometry);

    GLuint program_{0};
    GLint viewportLocation_{-1};
    GLint translationLocation_{-1};
    GLint texturedLocation_{-1};
    GLint samplerLocation_{-1};
    int width_{1};
    int height_{1};
    bool scissorEnabled_{false};
    std::uintptr_t nextGeometry_{1};
    std::uintptr_t nextTexture_{1};
    std::unordered_map<std::uintptr_t, Geometry> geometries_;
    std::unordered_map<std::uintptr_t, GLuint> textures_;
};

} // namespace minefade
