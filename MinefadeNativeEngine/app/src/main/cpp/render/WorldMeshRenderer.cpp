#include "WorldMeshRenderer.h"

#include <algorithm>
#include <cstddef>
#include <limits>
#include <set>
#include <utility>
#include <vector>

namespace minefade {

WorldMeshRenderer::~WorldMeshRenderer() {
    Shutdown();
}

void WorldMeshRenderer::ReleasePass(GLuint& vao, GLuint& vbo, GLsizei& drawCount) {
    if (vbo != 0) glDeleteBuffers(1, &vbo);
    if (vao != 0) glDeleteVertexArrays(1, &vao);
    vao = 0;
    vbo = 0;
    drawCount = 0;
}

bool WorldMeshRenderer::UploadPass(GLuint& vao, GLuint& vbo, GLsizei& drawCount,
                                   const std::vector<WorldVertex>& source,
                                   std::size_t first, std::size_t count) {
    if (count == 0u) {
        ReleasePass(vao, vbo, drawCount);
        return true;
    }
    if (first > source.size() || count > source.size() - first ||
        count > static_cast<std::size_t>(std::numeric_limits<GLsizei>::max())) {
        ReleasePass(vao, vbo, drawCount);
        return false;
    }
    if (vao == 0) glGenVertexArrays(1, &vao);
    if (vbo == 0) glGenBuffers(1, &vbo);
    if (vao == 0 || vbo == 0) {
        ReleasePass(vao, vbo, drawCount);
        return false;
    }

    glBindVertexArray(vao);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER,
                 static_cast<GLsizeiptr>(count * sizeof(WorldVertex)),
                 source.data() + first, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, sizeof(WorldVertex),
                          reinterpret_cast<const void*>(offsetof(WorldVertex, x)));
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, sizeof(WorldVertex),
                          reinterpret_cast<const void*>(offsetof(WorldVertex, nx)));
    glEnableVertexAttribArray(2);
    glVertexAttribPointer(2, 3, GL_FLOAT, GL_FALSE, sizeof(WorldVertex),
                          reinterpret_cast<const void*>(offsetof(WorldVertex, r)));
    glEnableVertexAttribArray(3);
    glVertexAttribPointer(3, 2, GL_FLOAT, GL_FALSE, sizeof(WorldVertex),
                          reinterpret_cast<const void*>(offsetof(WorldVertex, u)));
    glEnableVertexAttribArray(4);
    glVertexAttribPointer(4, 1, GL_FLOAT, GL_FALSE, sizeof(WorldVertex),
                          reinterpret_cast<const void*>(offsetof(WorldVertex, atlasTile)));
    glBindVertexArray(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    if (glGetError() != GL_NO_ERROR) {
        ReleasePass(vao, vbo, drawCount);
        return false;
    }
    drawCount = static_cast<GLsizei>(count);
    return true;
}

bool WorldMeshRenderer::Sync(const std::vector<ChunkMeshView>& chunkViews) {
    std::set<std::pair<int, int>> active;
    std::size_t opaqueTotal = 0;
    std::size_t transparentTotal = 0;
    bool success = true;

    for (const ChunkMeshView& view : chunkViews) {
        if (view.opaque == nullptr || view.transparent == nullptr) continue;
        const auto key = std::make_pair(view.chunkX, view.chunkZ);
        active.insert(key);
        GpuChunk& gpu = chunks_[key];

        if (gpu.revision != view.revision) {
            const bool opaqueOk = UploadPass(gpu.opaqueVao, gpu.opaqueVbo, gpu.opaqueCount,
                *view.opaque, 0u, view.opaque->size());
            const bool transparentOk = UploadPass(gpu.transparentVao, gpu.transparentVbo,
                gpu.transparentCount, *view.transparent, 0u, view.transparent->size());
            if (!opaqueOk || !transparentOk) {
                gpu.revision = 0;
                gpu.opaqueCount = 0;
                gpu.transparentCount = 0;
                success = false;
                continue;
            }
            gpu.revision = view.revision;
            gpu.minY = view.minY;
            gpu.maxY = view.maxY;
        }

        opaqueTotal += static_cast<std::size_t>(gpu.opaqueCount);
        transparentTotal += static_cast<std::size_t>(gpu.transparentCount);
    }

    for (auto it = chunks_.begin(); it != chunks_.end();) {
        if (active.find(it->first) == active.end()) {
            GpuChunk& gpu = it->second;
            ReleasePass(gpu.opaqueVao, gpu.opaqueVbo, gpu.opaqueCount);
            ReleasePass(gpu.transparentVao, gpu.transparentVbo, gpu.transparentCount);
            it = chunks_.erase(it);
        } else {
            ++it;
        }
    }

    opaqueVertexCount_ = static_cast<GLsizei>(std::min(
        opaqueTotal, static_cast<std::size_t>(std::numeric_limits<GLsizei>::max())));
    transparentVertexCount_ = static_cast<GLsizei>(std::min(
        transparentTotal, static_cast<std::size_t>(std::numeric_limits<GLsizei>::max())));
    return success;
}

bool WorldMeshRenderer::IsVisible(const ChunkMeshRange& range,
                                  const CameraFrustum& frustum) noexcept {
    const float minX = static_cast<float>(range.chunkX * VoxelWorld::kChunkSize);
    const float minZ = static_cast<float>(range.chunkZ * VoxelWorld::kChunkSize);
    const float minY = std::max(0.0f, range.minY - 1.0f);
    const float maxY = std::min(static_cast<float>(VoxelWorld::kWorldHeight),
                                range.maxY + 1.0f);
    return frustum.IntersectsAabb(minX, minY, minZ,
        minX + static_cast<float>(VoxelWorld::kChunkSize), maxY,
        minZ + static_cast<float>(VoxelWorld::kChunkSize));
}

void WorldMeshRenderer::DrawOpaque(const CameraFrustum& frustum) const {
    for (const auto& entry : chunks_) {
        const auto& key = entry.first;
        const GpuChunk& gpu = entry.second;
        if (gpu.opaqueVao == 0 || gpu.opaqueCount <= 0) continue;
        const float minX = static_cast<float>(key.first * VoxelWorld::kChunkSize);
        const float minZ = static_cast<float>(key.second * VoxelWorld::kChunkSize);
        const float minY = std::max(0.0f, gpu.minY - 1.0f);
        const float maxY = std::min(static_cast<float>(VoxelWorld::kWorldHeight),
                                    gpu.maxY + 1.0f);
        if (!frustum.IntersectsAabb(minX, minY, minZ,
                minX + static_cast<float>(VoxelWorld::kChunkSize), maxY,
                minZ + static_cast<float>(VoxelWorld::kChunkSize))) continue;
        glBindVertexArray(gpu.opaqueVao);
        glDrawArrays(GL_TRIANGLES, 0, gpu.opaqueCount);
    }
    glBindVertexArray(0);
}

void WorldMeshRenderer::DrawTransparent(const CameraFrustum& frustum,
                                        const Vec3& cameraPosition) const {
    struct VisibleChunk {
        float distanceSquared{0.0f};
        const GpuChunk* gpu{nullptr};
    };
    std::vector<VisibleChunk> visible;
    visible.reserve(chunks_.size());
    for (const auto& entry : chunks_) {
        const auto& key = entry.first;
        const GpuChunk& gpu = entry.second;
        if (gpu.transparentVao == 0 || gpu.transparentCount <= 0) continue;
        const float minX = static_cast<float>(key.first * VoxelWorld::kChunkSize);
        const float minZ = static_cast<float>(key.second * VoxelWorld::kChunkSize);
        const float minY = std::max(0.0f, gpu.minY - 1.0f);
        const float maxY = std::min(static_cast<float>(VoxelWorld::kWorldHeight),
                                    gpu.maxY + 1.0f);
        if (!frustum.IntersectsAabb(minX, minY, minZ,
                minX + static_cast<float>(VoxelWorld::kChunkSize), maxY,
                minZ + static_cast<float>(VoxelWorld::kChunkSize))) continue;
        const float dx = minX + VoxelWorld::kChunkSize * 0.5f - cameraPosition.x;
        const float dy = (minY + maxY) * 0.5f - cameraPosition.y;
        const float dz = minZ + VoxelWorld::kChunkSize * 0.5f - cameraPosition.z;
        visible.push_back({dx * dx + dy * dy + dz * dz, &gpu});
    }
    std::sort(visible.begin(), visible.end(),
        [](const VisibleChunk& a, const VisibleChunk& b) {
            return a.distanceSquared > b.distanceSquared;
        });
    for (const VisibleChunk& chunk : visible) {
        glBindVertexArray(chunk.gpu->transparentVao);
        glDrawArrays(GL_TRIANGLES, 0, chunk.gpu->transparentCount);
    }
    glBindVertexArray(0);
}

void WorldMeshRenderer::Shutdown() {
    for (auto& entry : chunks_) {
        GpuChunk& gpu = entry.second;
        ReleasePass(gpu.opaqueVao, gpu.opaqueVbo, gpu.opaqueCount);
        ReleasePass(gpu.transparentVao, gpu.transparentVbo, gpu.transparentCount);
    }
    chunks_.clear();
    opaqueVertexCount_ = 0;
    transparentVertexCount_ = 0;
}

} // namespace minefade
