#pragma once

#include "../world/VoxelWorld.h"

#include <GLES3/gl3.h>

#include <cstdint>
#include <map>
#include <utility>
#include <vector>

namespace minefade {

// Owns only terrain GPU resources. VoxelWorld remains the CPU authority for
// chunks/meshes; this class updates a GPU buffer only when that chunk's mesh
// revision changes.
class WorldMeshRenderer {
public:
    WorldMeshRenderer() = default;
    ~WorldMeshRenderer();

    WorldMeshRenderer(const WorldMeshRenderer&) = delete;
    WorldMeshRenderer& operator=(const WorldMeshRenderer&) = delete;

    bool Sync(const std::vector<ChunkMeshView>& chunkViews);
    void Shutdown();

    GLsizei OpaqueVertexCount() const noexcept { return opaqueVertexCount_; }
    GLsizei TransparentVertexCount() const noexcept { return transparentVertexCount_; }
    std::size_t ResidentChunkCount() const noexcept { return chunks_.size(); }

    void DrawOpaque(const CameraFrustum& frustum) const;
    void DrawTransparent(const CameraFrustum& frustum, const Vec3& cameraPosition) const;

private:
    struct GpuChunk {
        GLuint opaqueVao{0};
        GLuint opaqueVbo{0};
        GLuint transparentVao{0};
        GLuint transparentVbo{0};
        GLsizei opaqueCount{0};
        GLsizei transparentCount{0};
        std::uint64_t revision{0};
        float minY{0.0f};
        float maxY{0.0f};
    };

    static bool UploadPass(GLuint& vao, GLuint& vbo, GLsizei& drawCount,
                           const std::vector<WorldVertex>& source,
                           std::size_t first, std::size_t count);
    static void ReleasePass(GLuint& vao, GLuint& vbo, GLsizei& drawCount);
    static bool IsVisible(const ChunkMeshRange& range, const CameraFrustum& frustum) noexcept;

    std::map<std::pair<int, int>, GpuChunk> chunks_;
    GLsizei opaqueVertexCount_{0};
    GLsizei transparentVertexCount_{0};
};

} // namespace minefade
